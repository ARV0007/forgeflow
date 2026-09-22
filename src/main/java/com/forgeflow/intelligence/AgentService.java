package com.forgeflow.intelligence;

import com.forgeflow.execution.BuildResult;
import com.forgeflow.execution.SandboxProvider;
import com.forgeflow.intelligence.dto.GenerateResponse;
import com.forgeflow.shared.llm.LlmClient;
import com.forgeflow.shared.llm.LlmMessage;
import com.forgeflow.shared.llm.LlmResponse;
import com.forgeflow.shared.llm.ToolCall;
import com.forgeflow.shared.llm.ToolResult;
import com.forgeflow.workspace.ProjectFileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The agent loop, with a build gate.
 *
 *   context -> model -> tool calls -> results -> model -> ... -> finish
 *
 * finish is not a formality. Calling it runs the build, and if the build
 * fails, finish comes back as an ERROR telling the model what is broken. The
 * model fixes it and calls finish again. So "finished" means "the code works",
 * not "the model says it is done" - the only way out goes through the check.
 *
 * Every exit records WHY in stop_reason, so a run that did not end cleanly can
 * be diagnosed without re-running it.
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final LlmClient llm;
    private final AgentTools tools;
    private final GenerationRunRepository runs;
    private final SandboxProvider sandbox;
    private final ProjectFileService files;

    private final int maxToolCalls;
    private final long timeoutSeconds;
    private final int maxInputTokens;
    private final int maxRepairRounds;

    public AgentService(LlmClient llm,
                        AgentTools tools,
                        GenerationRunRepository runs,
                        SandboxProvider sandbox,
                        ProjectFileService files,
                        @Value("${forgeflow.agent.max-tool-calls}") int maxToolCalls,
                        @Value("${forgeflow.agent.timeout-seconds}") long timeoutSeconds,
                        @Value("${forgeflow.agent.max-input-tokens}") int maxInputTokens,
                        @Value("${forgeflow.agent.max-repair-rounds:3}") int maxRepairRounds) {
        this.llm = llm;
        this.tools = tools;
        this.runs = runs;
        this.sandbox = sandbox;
        this.files = files;
        this.maxToolCalls = maxToolCalls;
        this.timeoutSeconds = timeoutSeconds;
        this.maxInputTokens = maxInputTokens;
        this.maxRepairRounds = maxRepairRounds;
    }

    /** Non-streaming entry point. */
    public GenerateResponse generate(Long projectId, Long userId, String prompt) {
        return generate(projectId, userId, prompt, event -> { });
    }

    public GenerateResponse generate(Long projectId, Long userId, String prompt,
                                     Consumer<AgentEvent> listener) {

        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + timeoutSeconds * 1000L;

        GenerationRun run = new GenerationRun();
        run.setProjectId(projectId);
        run.setUserId(userId);
        run.setStatus("RUNNING");
        run.setModel(llm.modelName());
        run = runs.save(run);

        List<LlmMessage> history = new ArrayList<>();
        history.add(LlmMessage.user(prompt));

        Set<String> written = new LinkedHashSet<>();
        int toolCallCount = 0;
        int round = 0;
        int repairRounds = 0;
        int promptTokens = 0;
        int completionTokens = 0;
        int totalTokens = 0;
        Boolean buildPassed = null;
        String summary = null;
        String stopReason = null;

        emit(listener, AgentEvent.status("Planning"));

        try {
            while (true) {

                if (System.currentTimeMillis() > deadline) {
                    stopReason = "TIMEOUT";
                    break;
                }
                if (toolCallCount >= maxToolCalls) {
                    stopReason = "MAX_TOOL_CALLS";
                    break;
                }
                if (promptTokens >= maxInputTokens) {
                    stopReason = "TOKEN_BUDGET";
                    break;
                }

                round++;
                emit(listener, AgentEvent.thinking(round, promptTokens));

                LlmResponse response = llm.chat(AgentPrompt.SYSTEM, history, tools.specs());

                promptTokens += response.promptTokens();
                completionTokens += response.completionTokens();
                totalTokens += response.totalTokens();

                history.add(LlmMessage.model(response.rawParts()));

                // ---- the model stopped replying without calling finish ----
                if (!response.wantsTools()) {
                    summary = response.text();

                    // Verify anyway. Status comes from whether the code works,
                    // not from whether the model signed off politely.
                    BuildResult check = verify(projectId, listener);
                    if (check.passed()) {
                        buildPassed = true;
                        stopReason = "NO_TOOL_CALL";
                        break;
                    }
                    if (repairRounds >= maxRepairRounds) {
                        buildPassed = false;
                        stopReason = "MAX_REPAIRS";
                        break;
                    }
                    repairRounds++;
                    emit(listener, AgentEvent.repair(repairRounds, maxRepairRounds));
                    // The last turn was the model's, so a user turn here keeps
                    // the conversation alternating the way Gemini expects.
                    history.add(LlmMessage.user(repairInstruction(check)));
                    continue;
                }

                // ---- the model called tools ----
                List<ToolResult> results = new ArrayList<>();
                boolean done = false;

                for (ToolCall call : response.toolCalls()) {
                    toolCallCount++;
                    Object rawPath = call.args().get("path");
                    String path = rawPath == null ? null : rawPath.toString();

                    emit(listener, AgentEvent.tool(call.name(), path));
                    log.debug("run {} -> tool {} {}", run.getId(), call.name(), call.args().keySet());

                    if ("finish".equals(call.name())) {
                        // THE GATE. finish only succeeds if the build passes.
                        BuildResult check = verify(projectId, listener);

                        if (check.passed()) {
                            buildPassed = true;
                            Object s = call.args().get("summary");
                            summary = s == null ? "Done." : s.toString();
                            results.add(ToolResult.ok("finish", "Build passed. The run is complete."));
                            done = true;

                        } else if (repairRounds >= maxRepairRounds) {
                            buildPassed = false;
                            results.add(ToolResult.failed("finish",
                                    "The build is still failing after " + repairRounds
                                            + " repair rounds. Stopping.\n" + check.output()));
                            done = true;

                        } else {
                            repairRounds++;
                            emit(listener, AgentEvent.repair(repairRounds, maxRepairRounds));
                            results.add(ToolResult.failed("finish", repairInstruction(check)));
                        }
                        continue;
                    }

                    ToolResult result = tools.execute(projectId, call);
                    results.add(result);

                    if (!result.ok()) {
                        emit(listener, AgentEvent.toolFailed(call.name(), result.output()));
                    } else if ("write_file".equals(call.name()) && path != null) {
                        written.add(path);
                        Object content = call.args().get("content");
                        emit(listener, AgentEvent.file(path, content == null ? 0 : content.toString().length()));
                    }
                }

                history.add(LlmMessage.toolResults(results));

                if (done) {
                    stopReason = Boolean.TRUE.equals(buildPassed) ? "FINISH_TOOL" : "MAX_REPAIRS";
                    break;
                }
            }

        } catch (Exception e) {
            log.error("run {} failed", run.getId(), e);
            stopReason = "ERROR";
            run.setErrorMessage(e.getMessage());
            emit(listener, AgentEvent.error(e.getMessage()));
        }

        String status = statusFor(stopReason, buildPassed);
        long durationMs = System.currentTimeMillis() - startedAt;

        run.setStatus(status);
        run.setStopReason(stopReason);
        run.setPromptTokens(promptTokens);
        run.setCompletionTokens(completionTokens);
        run.setToolCallCount(toolCallCount);
        run.setFilesWritten(written.size());
        run.setRepairRounds(repairRounds);
        run.setBuildPassed(buildPassed);
        run.setDurationMs(durationMs);
        run = runs.save(run);

        log.info("run {} {} ({}) - {} tool calls, {} repair round(s), build {}, {} tokens, {} ms",
                run.getId(), status, stopReason, toolCallCount, repairRounds,
                buildPassed == null ? "not run" : (buildPassed ? "passed" : "FAILED"),
                totalTokens, durationMs);

        GenerateResponse result = new GenerateResponse(run.getId(), status, stopReason, summary,
                List.copyOf(written), toolCallCount, repairRounds, buildPassed, totalTokens, durationMs);

        emit(listener, AgentEvent.done(result));
        return result;
    }

    /**
     * Status is the OUTCOME; stop_reason is the MECHANISM.
     *
     *   build passed                      -> SUCCEEDED (however the loop ended)
     *   a cap tripped (time/calls/tokens) -> CAPPED
     *   repairs exhausted, or an error    -> FAILED
     */
    private String statusFor(String stopReason, Boolean buildPassed) {
        if (Boolean.TRUE.equals(buildPassed)) {
            return "SUCCEEDED";
        }
        if ("TIMEOUT".equals(stopReason) || "MAX_TOOL_CALLS".equals(stopReason)
                || "TOKEN_BUDGET".equals(stopReason)) {
            return "CAPPED";
        }
        return "FAILED";
    }

    private BuildResult verify(Long projectId, Consumer<AgentEvent> listener) {
        emit(listener, AgentEvent.status("Building"));
        BuildResult check = sandbox.build(projectId, files.snapshot(projectId));
        emit(listener, AgentEvent.build(check.passed(), check.output()));
        return check;
    }

    private String repairInstruction(BuildResult check) {
        return "You cannot finish yet - the build failed. Problems:\n\n"
                + check.output()
                + "\n\nFix each one. Read an affected file first if you need to see its "
                + "current content, then write the corrected version. When everything is "
                + "fixed, call finish again.";
    }

    /** A listener that throws must not take the run down with it. */
    private void emit(Consumer<AgentEvent> listener, AgentEvent event) {
        try {
            listener.accept(event);
        } catch (Exception e) {
            log.debug("listener rejected event {}: {}", event.type(), e.toString());
        }
    }
}
