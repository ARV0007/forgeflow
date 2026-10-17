package com.forgeflow.intelligence;

import com.forgeflow.billing.Entitlements;
import com.forgeflow.billing.Quota;
import com.forgeflow.billing.UsageKind;
import com.forgeflow.billing.UsageMeter;
import com.forgeflow.execution.BuildResult;
import com.forgeflow.execution.ExecutionService;
import com.forgeflow.intelligence.retrieval.CodeIndex;
import com.forgeflow.intelligence.retrieval.KnowledgeBase;
import com.forgeflow.workspace.CheckpointService;
import com.forgeflow.workspace.ProjectService;
import com.forgeflow.shared.events.CodeGenerated;
import com.forgeflow.shared.events.EventBus;
import com.forgeflow.shared.events.Topics;
import com.forgeflow.shared.tracing.Tracer;
import com.forgeflow.intelligence.dto.AgentReport;
import com.forgeflow.intelligence.dto.GenerateResponse;
import com.forgeflow.shared.llm.BackoffClock;
import com.forgeflow.shared.llm.ImagePart;
import com.forgeflow.shared.llm.LlmClient;
import com.forgeflow.shared.llm.LlmMessage;
import com.forgeflow.shared.llm.LlmPricing;
import com.forgeflow.shared.llm.LlmResponse;
import com.forgeflow.shared.llm.ToolCall;
import com.forgeflow.shared.llm.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
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
    private final KnowledgeBase knowledge;
    private final EventBus events;
    private final CheckpointService checkpoints;
    private final Tracer tracer;
    private final ProjectService projects;
    private final LlmPricing pricing;
    private final Models models;
    private final CodeReviewer reviewer;
    private final com.forgeflow.intelligence.retrieval.AgenticRetriever agentic;
    private final boolean reviewByDefault;
    private final tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();

    private final int maxToolCalls;
    private final long timeoutSeconds;
    private final int maxInputTokens;
    private final int maxRepairRounds;
    private final BigDecimal maxCostUsd;
    private final double wrapUpAt;

    public AgentService(LlmClient llm,
                        AgentTools tools,
                        GenerationRunRepository runs,
                        ExecutionService execution,
                        Entitlements entitlements,
                        UsageMeter usage,
                        CodeIndex index,
                        KnowledgeBase knowledge,
                        EventBus events,
                        CheckpointService checkpoints,
                        Tracer tracer,
                        ProjectService projects,
                        LlmPricing pricing,
                        Models models,
                        CodeReviewer reviewer,
                        com.forgeflow.intelligence.retrieval.AgenticRetriever agentic,
                        @Value("${forgeflow.agent.review.enabled:true}") boolean reviewByDefault,
                        @Value("${forgeflow.agent.max-tool-calls}") int maxToolCalls,
                        @Value("${forgeflow.agent.timeout-seconds}") long timeoutSeconds,
                        @Value("${forgeflow.agent.max-input-tokens}") int maxInputTokens,
                        @Value("${forgeflow.agent.max-repair-rounds:3}") int maxRepairRounds,
                        @Value("${forgeflow.agent.max-cost-usd:0.10}") BigDecimal maxCostUsd,
                        @Value("${forgeflow.agent.wrap-up-at:0.8}") double wrapUpAt) {
        this.llm = llm;
        this.tools = tools;
        this.runs = runs;
        this.execution = execution;
        this.entitlements = entitlements;
        this.usage = usage;
        this.index = index;
        this.knowledge = knowledge;
        this.events = events;
        this.checkpoints = checkpoints;
        this.tracer = tracer;
        this.projects = projects;
        this.pricing = pricing;
        this.models = models;
        this.reviewer = reviewer;
        this.agentic = agentic;
        this.reviewByDefault = reviewByDefault;
        this.maxToolCalls = maxToolCalls;
        this.timeoutSeconds = timeoutSeconds;
        this.maxInputTokens = maxInputTokens;
        this.maxRepairRounds = maxRepairRounds;
        this.maxCostUsd = maxCostUsd;
        this.wrapUpAt = wrapUpAt;
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
        return generate(projectId, userId, prompt, List.of(), priorTurns, sessionId, listener);
    }

    /**
     * @param images screenshots, mockups or sketches sent with the request -
     *               the model sees them next to the words ("screenshot to app")
     */
    public GenerateResponse generate(Long projectId, Long userId, String prompt, List<ImagePart> images,
                                     List<LlmMessage> priorTurns, Long sessionId,
                                     Consumer<AgentEvent> listener) {
        return generate(projectId, userId, prompt, images, priorTurns, null, sessionId, listener);
    }

    /**
     * @param recalled older parts of this chat that look relevant to the
     *                 request (long memory, beyond priorTurns), or null
     */
    public GenerateResponse generate(Long projectId, Long userId, String prompt, List<ImagePart> images,
                                     List<LlmMessage> priorTurns, RecalledContext recalled, Long sessionId,
                                     Consumer<AgentEvent> listener) {
        return generate(projectId, userId, prompt, images, priorTurns, recalled, RunOptions.DEFAULT, sessionId, listener);
    }

    /** A one-off generate with its own retrieval settings - how the evals measure RAG. */
    public GenerateResponse generate(Long projectId, Long userId, String prompt, RunOptions rag,
                                     Consumer<AgentEvent> listener) {
        return generate(projectId, userId, prompt, List.of(), null, null, rag, null, listener);
    }

    public GenerateResponse generate(Long projectId, Long userId, String prompt, List<ImagePart> images,
                                     List<LlmMessage> priorTurns, RecalledContext recalled, RunOptions rag,
                                     Long sessionId, Consumer<AgentEvent> listener) {
        // One span for the whole run; each model call and build inside it is
        // a child, so a slow run shows exactly which step was slow.
        try (Tracer.Span span = tracer.start("agent.run")) {
            span.tag("project.id", projectId).tag("session.id", sessionId);
            try {
                GenerateResponse r = run(projectId, userId, prompt, images, priorTurns, recalled,
                        rag == null ? RunOptions.DEFAULT : rag, sessionId, listener);
                span.tag("run.id", r.runId()).tag("run.status", r.status()).tag("run.stop_reason", r.stopReason())
                    .tag("run.tokens", r.totalTokens()).tag("run.repair_rounds", r.repairRounds());
                return r;
            } catch (RuntimeException e) {
                span.error(e);
                throw e;
            }
        }
    }

    private GenerateResponse run(Long projectId, Long userId, String prompt, List<ImagePart> images,
                                 List<LlmMessage> priorTurns, RecalledContext recalled, RunOptions rag,
                                 Long sessionId, Consumer<AgentEvent> listener) {

        // Before anything is spent. A soft limit: a run that starts under the
        // daily allowance is allowed to finish, so the overshoot is bounded by
        // one run's own token budget (max-input-tokens) - never a half-built app.
        entitlements.requireRoomFor(userId, Quota.AI_TOKENS_PER_DAY);

        // Version history: if this project already has files and no history,
        // remember how it was before the AI touches it - so even the first
        // run can be undone.
        try {
            checkpoints.ensureBaseline(projectId, userId);
        } catch (RuntimeException e) {
            log.warn("baseline checkpoint for project {} failed: {}", projectId, e.toString());
        }

        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + timeoutSeconds * 1000L;

        // Routing: one decision per run - a new build, a request with pictures
        // or a long brief goes to the strong model; an edit to an existing app
        // to the fast one.
        boolean newBuild = tools.fileCount(projectId) == 0;
        Models.Tier tier = models.route(newBuild, images != null && !images.isEmpty(), prompt);
        String model = models.modelFor(tier);

        GenerationRun run = new GenerationRun();
        run.setProjectId(projectId);
        run.setSessionId(sessionId);
        run.setUserId(userId);
        run.setStatus("RUNNING");
        run.setModel(model);
        run.setTraceId(Tracer.currentTraceId());
        run = runs.save(run);

        // The project's stack picks the instructions: same loop, different target.
        String systemPrompt = AgentPrompt.forStack(projects.stackOf(projectId));
        emit(listener, AgentEvent.model(model, models.routes() ? tier.name() : null));
        // Guardrail notices for the run report (and the UI, as they happen).
        List<String> guards = new ArrayList<>();
        List<LlmMessage> history = new ArrayList<>(priorTurns == null ? List.of() : priorTurns);
        // RAG: on a project too big to send whole, the request travels with
        // the excerpts most relevant to it. Retrieval failing is never fatal -
        // the agent still has list_files, read_file and search_code.
        String request = prompt;
        Map<String, Integer> ragUsed = new TreeMap<>(Map.of("codeExcerpts", 0, "notes", 0, "recalled", 0));
        try {
            Optional<CodeIndex.RetrievedContext> context = rag.code() == RunOptions.Code.OFF ? Optional.empty()
                    : index.contextFor(projectId, prompt, rag.code() == RunOptions.Code.ALWAYS);
            if (context.isPresent()) {
                scan("the retrieved code", context.get().text(), guards, listener);
                request = prompt + "\n\n" + context.get().text();
                ragUsed.put("codeExcerpts", context.get().excerpts());
                emit(listener, AgentEvent.retrieved(context.get().excerpts(), context.get().files()));
            }
        } catch (RuntimeException e) {
            log.warn("retrieval for project {} failed, continuing without it: {}", projectId, e.toString());
        }
        // Long chat memory: older messages of this conversation that the
        // recent window no longer shows but this request seems to refer to.
        if (recalled != null && recalled.messages() > 0) {
            scan("earlier chat messages", recalled.text(), guards, listener);
            request = request + "\n\n" + recalled.text();
            ragUsed.put("recalled", recalled.messages());
            emit(listener, AgentEvent.recalled(recalled.messages()));
        }
        // RAG over the knowledge base: the few reference notes most relevant
        // to this request, for this project's stack. Never fatal either.
        try {
            Optional<KnowledgeBase.Notes> notes = rag.notes() == null
                    ? knowledge.notesFor(prompt, projects.stackOf(projectId))
                    : knowledge.notesFor(prompt, projects.stackOf(projectId), rag.notes());
            if (notes.isPresent()) {
                scan("the reference notes", notes.get().text(), guards, listener);
                request = request + "\n\n" + notes.get().text();
                ragUsed.put("notes", notes.get().hits().size());
                emit(listener, AgentEvent.notes(notes.get().hits().stream().map(KnowledgeBase.Hit::source).toList()));
            }
        } catch (RuntimeException e) {
            log.warn("knowledge notes for project {} failed, continuing without them: {}", projectId, e.toString());
        }
        // The runtime half of self-healing: what the app actually did in a
        // browser since the last build, which the build gate can't see.
        List<String> runtime = execution.runtimeErrors(projectId, MAX_RUNTIME_ERRORS);
        if (!runtime.isEmpty()) {
            emit(listener, AgentEvent.status("Reviewing " + runtime.size() + " error"
                    + (runtime.size() == 1 ? "" : "s") + " from the preview"));
            scan("the preview's error messages", String.join("\n", runtime), guards, listener);
            request = request + "\n\n" + runtimeSection(runtime);
        }
        if (images != null && !images.isEmpty()) {
            emit(listener, AgentEvent.status("Looking at " + images.size() + " image" + (images.size() == 1 ? "" : "s")));
            request = imageBrief(images.size()) + "\n\n" + request;
        }
        history.add(LlmMessage.user(request, images));

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
        BigDecimal costUsd = BigDecimal.ZERO;
        boolean fellBack = false;
        boolean wrapUpSent = false;
        // The agent's own step plan (update_plan), and how often finish was
        // turned back because of it.
        List<AgentReport.PlanStep> plan = new ArrayList<>();
        int planChecks = 0;
        // The reviewer agent: what each changed file looked like before this
        // run touched it, how many reviews ran, and the latest verdict.
        boolean reviewOn = rag.review() != null ? rag.review() : reviewByDefault;
        Map<String, String> originals = new java.util.LinkedHashMap<>();
        int reviews = 0;
        CodeReviewer.Verdict lastReview = null;
        // Agentic retrieval: one line per thorough search (queries tried, results kept).
        List<String> searches = new ArrayList<>();
        // Every tool call in order, with its path - the run's trajectory.
        List<String> trajectory = new ArrayList<>();

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
                if (maxCostUsd.signum() > 0 && costUsd.compareTo(maxCostUsd) >= 0) {
                    stopReason = "COST_BUDGET";
                    note(guards, listener, "Stopped at this run's cost limit ($" + maxCostUsd.toPlainString() + ")");
                    break;
                }

                round++;
                emit(listener, AgentEvent.thinking(round, promptTokens));

                LlmResponse response;
                try (Tracer.Span call = tracer.start("llm.chat")) {
                    call.tag("llm.model", model).tag("agent.round", round);
                    Models.Served served;
                    try {
                        BackoffClock.drain();
                        served = models.chat(model, systemPrompt, history, tools.specs(),
                                why -> note(guards, listener, "Model fallback: " + why));
                    } catch (RuntimeException e) {
                        call.error(e);
                        throw e;
                    }
                    response = served.response();
                    costUsd = costUsd.add(served.costUsd());
                    if (served.fellBack()) {
                        // The rest of the run stays on the model that answered.
                        fellBack = true;
                        model = served.model();
                        run.setModel(model);
                        call.tag("llm.fallback", model);
                    }
                    call.tag("llm.total_tokens", response.totalTokens()).tag("llm.cached_tokens", response.cachedTokens());
                    // Time spent obeying the provider's rate limit doesn't count against the run.
                    long waited = BackoffClock.drain();
                    if (waited > 0) {
                        deadline += waited;
                        call.tag("llm.backoff_ms", waited);
                    }
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
                    trajectory.add(path == null ? call.name() : call.name() + " " + path);

                    emit(listener, AgentEvent.tool(call.name(), path));
                    log.debug("run {} -> tool {} {}", run.getId(), call.name(), call.args().keySet());

                    if ("search_code".equals(call.name()) && Boolean.parseBoolean(String.valueOf(call.args().get("thorough")))) {
                        // The thorough search is a small agent of its own:
                        // rewrite, search, grade, retry. Its model calls count
                        // toward this run.
                        String query = String.valueOf(call.args().getOrDefault("query", "")).strip();
                        if (query.isEmpty()) {
                            results.add(ToolResult.failed("search_code", "query is required"));
                            continue;
                        }
                        var found = agentic.search(projectId, query, 6, why -> note(guards, listener, "Model fallback: " + why));
                        totalTokens += found.totalTokens();
                        costUsd = costUsd.add(found.costUsd());
                        String line = "'" + query + "': " + String.join("; ", found.trace());
                        searches.add(line);
                        emit(listener, AgentEvent.searched(found.trace(), found.hits().size()));
                        ToolResult r = AgentTools.formatHits(found.hits());
                        scan("the search results", r.output(), guards, listener);
                        results.add(r);
                        continue;
                    }

                    if ("update_plan".equals(call.name())) {
                        ToolResult planned = updatePlan(call, plan);
                        results.add(planned);
                        if (planned.ok()) {
                            emit(listener, AgentEvent.plan(List.copyOf(plan)));
                        } else {
                            emit(listener, AgentEvent.toolFailed(call.name(), planned.output()));
                        }
                        continue;
                    }

                    if ("finish".equals(call.name())) {
                        // The plan check comes first and costs nothing: a run
                        // that planned five steps and did three isn't done.
                        // Bounded, so a model that can't settle its plan
                        // still reaches the build gate.
                        List<String> open = unfinished(plan);
                        if (!open.isEmpty() && planChecks < MAX_PLAN_CHECKS) {
                            planChecks++;
                            emit(listener, AgentEvent.status("Plan has " + open.size() + " unfinished step"
                                    + (open.size() == 1 ? "" : "s")));
                            results.add(ToolResult.failed("finish", "Your plan still has unfinished steps: "
                                    + String.join("; ", open) + ". Do them now - or, if one is already done or "
                                    + "no longer needed, call update_plan marking it done or skipped - then call finish."));
                            continue;
                        }
                        // THE GATE. finish only succeeds if the build passes.
                        BuildResult check = verify(projectId, listener);

                        // The second gate: a reviewer agent reads the request and
                        // the diff. Major problems go back to the builder once;
                        // the second look is final either way.
                        if (check.passed() && reviewOn && !originals.isEmpty() && reviews < MAX_REVIEWS) {
                            reviews++;
                            emit(listener, AgentEvent.status("Reviewing the changes"));
                            List<CodeReviewer.Change> changes = new ArrayList<>();
                            originals.forEach((p, before) -> changes.add(
                                    new CodeReviewer.Change(p, before, tools.current(projectId, p).orElse(null))));
                            lastReview = reviewer.review(prompt, changes,
                                    why -> note(guards, listener, "Model fallback: " + why));
                            totalTokens += lastReview.totalTokens();
                            costUsd = costUsd.add(lastReview.costUsd());
                            emit(listener, AgentEvent.review(lastReview.ok() ? lastReview.verdict() : "skipped",
                                    lastReview.summary(), lastReview.issues()));
                            if (!lastReview.ok()) {
                                note(guards, listener, "Review skipped: " + lastReview.summary());
                            } else if (lastReview.wantsChanges() && reviews < MAX_REVIEWS) {
                                results.add(ToolResult.failed("finish", CodeReviewer.feedback(lastReview)));
                                continue;
                            }
                        }

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
                            results.add(ToolResult.failed("finish", repairInstruction(check)
                                    + (plan.isEmpty() ? "" : " Add the fixes to your plan with update_plan.")));
                        }
                        continue;
                    }

                    if (("write_file".equals(call.name()) || "edit_file".equals(call.name()))
                            && path != null && !originals.containsKey(path)) {
                        originals.put(path, tools.current(projectId, path).orElse(null));
                    }
                    ToolResult result = tools.execute(projectId, userId, call);
                    results.add(result);
                    if (READS.contains(call.name()) && result.ok()) {
                        scan(path != null ? path : "the " + call.name() + " results", result.output(), guards, listener);
                    }

                    if (!result.ok()) {
                        if (result.output().startsWith("ERROR: Refused: ")) {
                            note(guards, listener, "Refused to write a credential into " + path);
                        }
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

                // Graceful wrap-up: one warning at 80% of any budget, riding on
                // this round's last tool result (the model reads those), so it
                // can finish what matters instead of being cut off mid-change.
                if (!done && !wrapUpSent && !results.isEmpty()
                        && nearlySpent(promptTokens, toolCallCount, costUsd, startedAt, deadline)) {
                    wrapUpSent = true;
                    ToolResult last = results.remove(results.size() - 1);
                    results.add(new ToolResult(last.toolName(), last.ok(), last.output() + WRAP_UP));
                    note(guards, listener, "Most of this run's budget is used - asked the agent to wrap up");
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
        run.setCostUsd(costUsd);
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
            // A version per run that changed something, labelled with what
            // was asked. Never fatal: history failing must not fail the run.
            try {
                String label = (status.equals("SUCCEEDED") ? "" : "(" + status.toLowerCase() + ") ")
                        + prompt.strip().lines().findFirst().orElse("");
                checkpoints.record(projectId, "RUN", label, run.getId(), userId);
            } catch (RuntimeException e) {
                log.warn("checkpoint after run {} failed: {}", run.getId(), e.toString());
            }
            // Keyed by project: on Kafka, one project's events land on one
            // partition and stay in order. Published on the request thread,
            // not via an outbox - acceptable because every consumer is
            // idempotent and search re-checks the index itself; a lost event
            // costs latency on the next search, never a wrong answer.
            events.publish(Topics.CODE_GENERATED, String.valueOf(projectId), new CodeGenerated(projectId,
                    run.getId(), userId, status, List.copyOf(written), java.time.Instant.now()));
        }

        log.info("run {} {} ({}) - {} tool calls, {} repair round(s), build {}, {} tokens, {} ms",
                run.getId(), status, stopReason, toolCallCount, repairRounds,
                buildPassed == null ? "not run" : (buildPassed ? "passed" : "FAILED"),
                totalTokens, durationMs);

        AgentReport.Review review = lastReview == null ? null : new AgentReport.Review(
                lastReview.ok() ? lastReview.verdict() : "skipped", reviews, lastReview.summary(), lastReview.issues());
        AgentReport report = new AgentReport(model, tier.name(), fellBack, List.copyOf(plan), review, List.copyOf(guards),
                null, List.of(), List.of(), List.of(), List.copyOf(searches), List.copyOf(trajectory));
        try {
            run.setAgentReport(json.writeValueAsString(report));
            run = runs.save(run);
        } catch (RuntimeException e) {
            log.warn("saving the agent report of run {} failed: {}", run.getId(), e.toString());
        }

        GenerateResponse result = new GenerateResponse(run.getId(), status, stopReason, summary,
                List.copyOf(written), toolCallCount, repairRounds, buildPassed, totalTokens, durationMs,
                run.getErrorMessage(), java.util.Collections.unmodifiableMap(toolUsage), run.getCostUsd(),
                java.util.Collections.unmodifiableMap(ragUsed), report);

        emit(listener, AgentEvent.done(result));
        return result;
    }

    /** The saved agent reports of these runs, by run id - for chat history, where a reply shows its plan and review. */
    public Map<Long, AgentReport> reports(java.util.Collection<Long> runIds) {
        Map<Long, AgentReport> out = new java.util.HashMap<>();
        if (runIds == null || runIds.isEmpty()) {
            return out;
        }
        for (GenerationRun r : runs.findAllById(runIds)) {
            if (r.getAgentReport() != null) {
                try {
                    out.put(r.getId(), json.readValue(r.getAgentReport(), AgentReport.class));
                } catch (RuntimeException e) {
                    log.debug("unreadable agent report on run {}: {}", r.getId(), e.toString());
                }
            }
        }
        return out;
    }

    /**
     * What to do with pictures. Said in the request rather than only the
     * system prompt, because it applies to this request: "the user attached
     * these; they are the spec".
     */
    static String imageBrief(int count) {
        return "ATTACHED: " + count + " image" + (count == 1 ? "" : "s") + " - a screenshot, mockup or sketch of what "
                + "the user wants. Treat it as the specification: reproduce its layout, sections, text, colours, "
                + "spacing and components as closely as plain HTML, CSS and JavaScript allow. Use the visible text "
                + "verbatim. Where the image is ambiguous, choose what a careful front-end developer would, and say "
                + "so in your summary.";
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
                || "TOKEN_BUDGET".equals(stopReason) || "COST_BUDGET".equals(stopReason)) {
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

    static final int MAX_PLAN_CHECKS = 2;
    /** First look, and the look after one round of fixes. */
    static final int MAX_REVIEWS = 2;
    static final int MAX_PLAN_STEPS = 10;
    private static final Set<String> STEP_STATUSES = Set.of("pending", "in_progress", "done", "skipped");

    /**
     * update_plan replaces the whole plan each time - simpler for the model
     * than patching steps by index, and the latest plan is always complete.
     */
    @SuppressWarnings("unchecked")
    static ToolResult updatePlan(ToolCall call, List<AgentReport.PlanStep> plan) {
        Object raw = call.args().get("steps");
        if (!(raw instanceof List<?> items) || items.isEmpty()) {
            return ToolResult.failed("update_plan", "steps must be a non-empty list of {title, status}");
        }
        if (items.size() > MAX_PLAN_STEPS) {
            return ToolResult.failed("update_plan", "at most " + MAX_PLAN_STEPS + " steps - group smaller ones together");
        }
        List<AgentReport.PlanStep> next = new ArrayList<>();
        for (Object item : items) {
            String title;
            String status = "pending";
            if (item instanceof Map<?, ?> m) {
                title = m.get("title") == null ? null : m.get("title").toString().strip();
                if (m.get("status") != null) {
                    status = m.get("status").toString().strip().toLowerCase(java.util.Locale.ROOT);
                }
            } else {
                title = item == null ? null : item.toString().strip();
            }
            if (title == null || title.isEmpty()) {
                return ToolResult.failed("update_plan", "every step needs a title");
            }
            if (!STEP_STATUSES.contains(status)) {
                return ToolResult.failed("update_plan", "status must be one of pending, in_progress, done, skipped - not '"
                        + status + "'");
            }
            next.add(new AgentReport.PlanStep(title.length() > 160 ? title.substring(0, 157) + "..." : title, status));
        }
        plan.clear();
        plan.addAll(next);
        long done = plan.stream().filter(p -> "done".equals(p.status()) || "skipped".equals(p.status())).count();
        String upNext = plan.stream().filter(p -> !"done".equals(p.status()) && !"skipped".equals(p.status()))
                .map(AgentReport.PlanStep::title).findFirst().orElse(null);
        return ToolResult.ok("update_plan", "Plan saved: " + done + " of " + plan.size() + " steps done."
                + (upNext == null ? " Every step is done - call finish." : " Next: " + upNext));
    }

    static List<String> unfinished(List<AgentReport.PlanStep> plan) {
        return plan.stream().filter(p -> "pending".equals(p.status()) || "in_progress".equals(p.status()))
                .map(p -> "'" + p.title() + "'").toList();
    }

    /** Tools whose output is text the agent didn't write - scanned for injected instructions. */
    private static final Set<String> READS = Set.of("read_file", "search_code", "search_docs", "fetch_docs");

    static final String WRAP_UP = "\n\nNOTE: most of this run's budget (tokens, steps, time or cost) is used. "
            + "Finish the essential part of the request now and call finish; mention anything left undone in the summary.";

    private boolean nearlySpent(int promptTokens, int toolCalls, BigDecimal cost, long startedAt, long deadline) {
        if (promptTokens >= wrapUpAt * maxInputTokens || toolCalls >= wrapUpAt * maxToolCalls) {
            return true;
        }
        if (maxCostUsd.signum() > 0 && cost.doubleValue() >= wrapUpAt * maxCostUsd.doubleValue()) {
            return true;
        }
        return System.currentTimeMillis() - startedAt >= wrapUpAt * (deadline - startedAt);
    }

    /** Guardrail: report instructions found in text the agent is about to read. Once per source. */
    private void scan(String source, String text, List<String> guards, Consumer<AgentEvent> listener) {
        List<String> signals = Guardrails.injectionSignals(text);
        if (!signals.isEmpty()) {
            note(guards, listener, "Instructions found in " + source + " (\"" + signals.get(0)
                    + "\") - treated as data, not followed");
        }
    }

    private void note(List<String> guards, Consumer<AgentEvent> listener, String message) {
        if (!guards.contains(message)) {
            guards.add(message);
            emit(listener, AgentEvent.guard(message));
        }
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
