package com.forgeflow.intelligence;

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
import java.util.Set;

/**
 * The agent loop. This is the project.
 *
 *   context -> model -> tool calls -> results -> model -> ...
 *
 * and it stops when the model calls finish, or when one of the caps trips.
 * Every exit records WHY in stop_reason, so a run that did not finish cleanly
 * can be diagnosed without re-running it.
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final LlmClient llm;
    private final AgentTools tools;
    private final GenerationRunRepository runs;

    private final int maxToolCalls;
    private final long timeoutSeconds;
    private final int maxInputTokens;

    public AgentService(LlmClient llm,
                        AgentTools tools,
                        GenerationRunRepository runs,
                        @Value("${forgeflow.agent.max-tool-calls}") int maxToolCalls,
                        @Value("${forgeflow.agent.timeout-seconds}") long timeoutSeconds,
                        @Value("${forgeflow.agent.max-input-tokens}") int maxInputTokens) {
        this.llm = llm;
        this.tools = tools;
        this.runs = runs;
        this.maxToolCalls = maxToolCalls;
        this.timeoutSeconds = timeoutSeconds;
        this.maxInputTokens = maxInputTokens;
    }

    public GenerateResponse generate(Long projectId, Long userId, String prompt) {

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
        int promptTokens = 0;
        int completionTokens = 0;
        int totalTokens = 0;
        String summary = null;
        String stopReason = null;

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

                LlmResponse response = llm.chat(AgentPrompt.SYSTEM, history, tools.specs());

                promptTokens += response.promptTokens();
                completionTokens += response.completionTokens();
                totalTokens += response.totalTokens();

                if (!response.wantsTools()) {
                    // The model replied in prose instead of calling a tool.
                    // That is a finished turn, but a badly finished one - we
                    // record it separately so the eval harness can count it.
                    summary = response.text();
                    stopReason = "NO_TOOL_CALL";
                    break;
                }

                history.add(LlmMessage.model(response.rawParts()));

                List<ToolResult> results = new ArrayList<>();
                boolean finished = false;

                for (ToolCall call : response.toolCalls()) {
                    toolCallCount++;
                    log.debug("run {} -> tool {} {}", run.getId(), call.name(), call.args().keySet());

                    ToolResult result = tools.execute(projectId, call);
                    results.add(result);

                    if ("write_file".equals(call.name()) && result.ok()) {
                        Object p = call.args().get("path");
                        if (p != null) {
                            written.add(p.toString());
                        }
                    }
                    if ("finish".equals(call.name())) {
                        Object s = call.args().get("summary");
                        summary = s == null ? "Done." : s.toString();
                        finished = true;
                    }
                }

                history.add(LlmMessage.toolResults(results));

                if (finished) {
                    stopReason = "FINISH_TOOL";
                    break;
                }
            }

            run.setStatus("FINISH_TOOL".equals(stopReason) ? "SUCCEEDED" : "CAPPED");

        } catch (Exception e) {
            log.error("run {} failed", run.getId(), e);
            run.setStatus("FAILED");
            stopReason = "ERROR";
            run.setErrorMessage(e.getMessage());
        }

        long durationMs = System.currentTimeMillis() - startedAt;

        run.setStopReason(stopReason);
        run.setPromptTokens(promptTokens);
        run.setCompletionTokens(completionTokens);
        run.setToolCallCount(toolCallCount);
        run.setFilesWritten(written.size());
        run.setDurationMs(durationMs);
        run = runs.save(run);

        log.info("run {} {} ({}) - {} tool calls, {} files, {} tokens, {} ms",
                run.getId(), run.getStatus(), stopReason, toolCallCount,
                written.size(), totalTokens, durationMs);

        return new GenerateResponse(run.getId(), run.getStatus(), stopReason,
                summary, List.copyOf(written), toolCallCount, totalTokens, durationMs);
    }
}
