package com.forgeflow.intelligence;

import com.forgeflow.billing.Entitlements;
import com.forgeflow.billing.Quota;
import com.forgeflow.intelligence.dto.GenerateRequest;
import com.forgeflow.intelligence.dto.GenerateResponse;
import com.forgeflow.workspace.ProjectService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.forgeflow.shared.tracing.Tracer;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentService agent;
    private final ProjectService projects;
    private final Entitlements entitlements;
    private final AppJudge judge;

    // Virtual threads: a generation spends nearly all its time blocked on the
    // model, so a platform thread per run would be almost pure waste.
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public AgentController(AgentService agent, ProjectService projects, Entitlements entitlements, AppJudge judge) {
        this.agent = agent;
        this.projects = projects;
        this.entitlements = entitlements;
        this.judge = judge;
    }

    public record JudgeRequest(String request) {
    }

    /**
     * LLM-as-judge: score the project's current files against a request, on a
     * fixed 1-5 rubric. A read (nothing changes) that spends the caller's AI
     * tokens - what the evals use to measure quality, not only "it built".
     */
    @PostMapping("/judge")
    public AppJudge.Verdict judge(@PathVariable Long projectId, @RequestBody JudgeRequest body, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        projects.getById(projectId, userId);
        String request = body == null || body.request() == null ? "" : body.request().strip();
        if (request.isEmpty() || request.length() > 4000) {
            throw new com.forgeflow.shared.BadRequestException("request must be 1 to 4000 characters");
        }
        return judge.judge(userId, projectId, request);
    }

    @PostMapping("/generate")
    public GenerateResponse generate(@PathVariable Long projectId,
                                     @Valid @RequestBody GenerateRequest request,
                                     Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        projects.requireWrite(projectId, userId);   // 404 if no access, 403 if VIEWER
        RunOptions rag = RunOptions.parse(request.codeContext(), request.notes(), request.review());
        return agent.generate(projectId, userId, request.prompt(), rag, event -> { });
    }

    @PostMapping(value = "/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateStream(@PathVariable Long projectId,
                                     @Valid @RequestBody GenerateRequest request,
                                     Authentication auth) {

        Long userId = (Long) auth.getPrincipal();

        // Ownership runs on the REQUEST thread, before the emitter exists.
        // Do it inside the background task and a forbidden request would get
        // 200 plus an error event instead of a clean 404.
        projects.requireWrite(projectId, userId);
        RunOptions rag = RunOptions.parse(request.codeContext(), request.notes(), request.review());
        // Same reason: out of tokens must be a real 402, not an error event in a 200.
        entitlements.requireRoomFor(userId, Quota.AI_TOKENS_PER_DAY);

        SseEmitter emitter = new SseEmitter(180_000L);

        executor.submit(Tracer.wrap(() -> {
            try {
                agent.generate(projectId, userId, request.prompt(), rag, event -> {
                    try {
                        emitter.send(SseEmitter.event().name(event.type()).data(event));
                    } catch (Exception e) {
                        // Client hung up. The run continues - files already
                        // written stay written.
                        throw new IllegalStateException("client disconnected", e);
                    }
                });
                emitter.complete();
            } catch (Exception e) {
                log.debug("stream for project {} ended early: {}", projectId, e.toString());
                emitter.completeWithError(e);
            }
        }));

        return emitter;
    }
}
