package com.forgeflow.intelligence;

import com.forgeflow.intelligence.dto.GenerateRequest;
import com.forgeflow.intelligence.dto.GenerateResponse;
import com.forgeflow.workspace.ProjectService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    // Virtual threads: a generation spends nearly all its time blocked on the
    // model, so a platform thread per run would be almost pure waste.
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public AgentController(AgentService agent, ProjectService projects) {
        this.agent = agent;
        this.projects = projects;
    }

    @PostMapping("/generate")
    public GenerateResponse generate(@PathVariable Long projectId,
                                     @Valid @RequestBody GenerateRequest request,
                                     Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        projects.getById(projectId, userId);   // ownership check, throws 404
        return agent.generate(projectId, userId, request.prompt());
    }

    @PostMapping(value = "/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateStream(@PathVariable Long projectId,
                                     @Valid @RequestBody GenerateRequest request,
                                     Authentication auth) {

        Long userId = (Long) auth.getPrincipal();

        // Ownership runs on the REQUEST thread, before the emitter exists.
        // Do it inside the background task and a forbidden request would get
        // 200 plus an error event instead of a clean 404.
        projects.getById(projectId, userId);

        SseEmitter emitter = new SseEmitter(180_000L);

        executor.submit(() -> {
            try {
                agent.generate(projectId, userId, request.prompt(), event -> {
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
        });

        return emitter;
    }
}
