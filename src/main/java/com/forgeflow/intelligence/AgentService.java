package com.forgeflow.intelligence;

import com.forgeflow.billing.Entitlements;
import com.forgeflow.billing.Quota;
import com.forgeflow.billing.UsageKind;
import com.forgeflow.billing.UsageMeter;
import com.forgeflow.execution.BuildResult;
import com.forgeflow.execution.ExecutionService;
import com.forgeflow.intelligence.retrieval.CodeIndex;
import org.springframework.context.ApplicationEventPublisher;
import com.forgeflow.shared.tracing.Tracer;
import com.forgeflow.intelligence.dto.GenerateResponse;
import com.forgeflow.shared.llm.LlmClient;
import com.forgeflow.shared.llm.LlmMessage;
import com.forgeflow.shared.llm.LlmResponse;
import com.forgeflow.shared.llm.ToolCall;
import com.forgeflow.shared.llm.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
    private final ExecutionService execution;
    private final Entitlements entitlements;
    private final UsageMeter usage;
    private final CodeIndex index;
    private final ApplicationEventPublisher events;
    private final Tracer tracer;

    private final int maxToolCalls;
    private final long timeoutSeconds;
    private final int maxInputTokens;
    private final int maxRepairRounds;

    public AgentService(LlmClient llm,
                        AgentTools tools,
                        GenerationRunRepository runs,
                        ExecutionService execution,
                        Entitlements entitlements,
                        UsageMeter usage,
                        CodeIndex index,
                        ApplicationEventPublisher events,
                        Tracer tracer,
                        @Value("${forgeflow.agent.max-tool-calls}") int maxToolCalls,
                        @Value("${forgeflow.agent.timeout-seconds}") long timeoutSeconds,
                        @Value("${forgeflow.agent.max-input-tokens}") int maxInputTokens,
                        @Value("${forgeflow.agent.max-repair-rounds:3}") int maxRepairRounds) {
        this.llm = llm;
        this.tools = tools;
        this.runs = runs;
        this.execution = execution;
        this.entitlements = entitlements;
        this.usage = usage;
        this.index = index;
        this.events = events;
        this.tracer = tracer;
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
        return generate(projectId, userId, prompt, List.of(), null, listener);
    }

    /**
     * @param priorTurns earlier turns of the same conversation, oldest first,
     *                   as plain USER / MODEL text. This is what gives a chat
     *                   session memory: "make the header blue" means something
     *                   only if the model can see what it built last time.
     * @param sessionId  the chat session this run answers, or null for a
     *                   one-off generate.
     */
    public GenerateResponse generate(Long projectId, Long userId, String prompt,
                                     List<LlmMessage> priorTurns, Long sessionId,
                                     Consumer<AgentEvent> listener) {
        // One span for the whole run; each model call and build inside it is
        // a child, so a slow run shows exactly which step was slow.
        try (Tracer.Span span = tracer.start("agent.run")) {
            span.tag("project.id", projectId).tag("session.id", sessionId);
            try {
                GenerateResponse r = run(projectId, userId, prompt, priorTurns, sessionId, listener);
                span.tag("run.id", r.runId()).tag("run.status", r.status()).tag("run.stop_reason", r.stopReason())
                    .tag("run.tokens", r.totalTokens()).tag("run.repair_rounds", r.repairRounds());
                return r;
            } catch (RuntimeException e) {
                span.error(e);
                throw e;
            }
        }
    }

    private GenerateResponse run(Long projectId, Long userId, String prompt,
                                 List<LlmMessage> priorTurns, Long sessionId,
                                 Consumer<AgentEvent> listener) {

        // Before anything is spent. A soft limit: a run that starts under the
        // daily allowance is allowed to finish, so the overshoot is bounded by
        // one run's own token budget (max-input-tokens) - never a half-built app.
        entitlements.requireRoomFor(userId, Quota.AI_TOKENS_PER_DAY);

        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + timeoutSeconds * 1000L;

        GenerationRun run = new GenerationRun();
        run.setProjectId(projectId);
        run.setSessionId(sessionId);
        run.setUserId(userId);
        run.setStatus("RUNNING");
        run.setModel(llm.modelName());
        run.setTraceId(Tracer.currentTraceId());
        run = runs.save(run);

        List<LlmMessage> history = new ArrayList<>(priorTurns == null ? List.of() : priorTurns);
        // RAG: on a project too big to send whole, the request travels with
        // the excerpts most relevant to it. Retrieval failing is never fatal -
        // the agent still has list_files, read_file and search_code.
        String request = prompt;
        try {
            request = index.contextFor(projectId, prompt).map(ctx -> prompt + "\n\n" + ctx).orElse(prompt);
        } catch (RuntimeException e) {
            log.warn("retrieval for project {} failed, continuing without it: {}", projectId, e.toString());
        }
        // The runtime half of self-healing: what the app actually did in a
        // browser since the last build, which the build gate can't see.
        List<String> runtime = execution.runtimeErrors(projectId, MAX_RUNTIME_ERRORS);
        if (!runtime.isEmpty()) {
            emit(listener, AgentEvent.status("Reviewing " + runtime.size() + " error"
                    + (runtime.size() == 1 ? "" : "s") + " from the preview"));
            request = request + "\n\n" + runtimeSection(runtime);
        }
        history.add(LlmMessage.user(request));

        Set<String> written = new LinkedHashSet<>();
        int toolCallCount = 0;
        // Which tools, how often - so an eval can tell an edit from a rewrite.
        Map<String, Integer> toolUsage = new TreeMap<>();
        int round = 0;
        int repairRounds = 0;
        int promptTokens = 0;
        int completionTokens = 0;
        int totalTokens = 0;
        int cachedTokens = 0;
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

                LlmResponse response;
                try (Tracer.Span call = tracer.start("llm.chat")) {
                    call.tag("llm.model", llm.modelName()).tag("agent.round", round);
                    try {
                        response = llm.chat(AgentPrompt.SYSTEM, history, tools.specs());
                    } catch (RuntimeException e) {
                        call.error(e);
                        throw e;
                    }
                    call.tag("llm.total_tokens", response.totalTokens()).tag("llm.cached_tokens", response.cachedTokens());
                }

                promptTokens += response.promptTokens();
                completionTokens += response.completionTokens();
                totalTokens += response.totalTokens();
                cachedTokens += response.cachedTokens();

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
                    toolUsage.merge(call.name(), 1, Integer::sum);
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

                    ToolResult result = tools.execute(projectId, userId, call);
                    results.add(result);

                    if (!result.ok()) {
                        emit(listener, AgentEvent.toolFailed(call.name(), result.output()));
                    } else if ("write_file".equals(call.name()) && path != null) {
                        written.add(path);
                        Object content = call.args().get("content");
                        emit(listener, AgentEvent.file(path, content == null ? 0 : content.toString().length()));
                    } else if ("edit_file".equals(call.name()) && path != null) {
                        written.add(path);
                        Object replacement = call.args().get("new_text");
                        emit(listener, AgentEvent.edited(path, replacement == null ? 0 : replacement.toString().length()));
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
        run.setCachedTokens(cachedTokens);
        run.setCompletionTokens(completionTokens);
        run.setToolCallCount(toolCallCount);
        run.setFilesWritten(written.size());
        run.setRepairRounds(repairRounds);
        run.setBuildPassed(buildPassed);
        run.setDurationMs(durationMs);
        run = runs.save(run);

        // Charged to whoever asked - including for failed runs, because the
        // model calls were made either way. Charging the person who typed the
        // prompt (not the project owner) means inviting someone onto your
        // project never lets them spend your allowance.
        usage.record(userId, projectId, UsageKind.AI_TOKENS, totalTokens, "run:" + run.getId());

        if (!written.isEmpty()) {
            events.publishEvent(new CodeGenerated(projectId, run.getId(), userId, status,
                    List.copyOf(written), java.time.Instant.now()));
        }

        log.info("run {} {} ({}) - {} tool calls, {} repair round(s), build {}, {} tokens, {} ms",
                run.getId(), status, stopReason, toolCallCount, repairRounds,
                buildPassed == null ? "not run" : (buildPassed ? "passed" : "FAILED"),
                totalTokens, durationMs);

        GenerateResponse result = new GenerateResponse(run.getId(), status, stopReason, summary,
                List.copyOf(written), toolCallCount, repairRounds, buildPassed, totalTokens, durationMs,
                run.getErrorMessage(), java.util.Collections.unmodifiableMap(toolUsage));

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
        // Through ExecutionService, so the gate's builds show up in the Logs Stream.
        BuildResult check = execution.build(projectId);
        emit(listener, AgentEvent.build(check.passed(), check.output()));
        return check;
    }

    static final int MAX_RUNTIME_ERRORS = 10;

    static String runtimeSection(List<String> errors) {
        StringBuilder sb = new StringBuilder("RUNTIME ERRORS - reported by the browser running this project's "
                + "preview since the last build. The build check cannot see these. If the request is about them, "
                + "or they are clearly bugs in the code, find the cause and fix it:\n");
        for (String e : errors) {
            sb.append("- ").append(e).append('\n');
        }
        return sb.toString().strip();
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
