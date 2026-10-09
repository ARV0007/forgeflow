package com.forgeflow.execution;

import com.forgeflow.execution.dto.PreviewResponse;
import com.forgeflow.shared.ResourceNotFoundException;
import com.forgeflow.workspace.ProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spec: Preview - Get Preview, Logs Stream. Plus build, start and stop.
 *
 * Who may do what: build and start/stop cost compute, so they need WRITE
 * (an EDITOR). Looking at the preview and its logs is a read - a VIEWER can.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class ExecutionController {

    private final ExecutionService execution;
    private final ProjectService projects;
    private final PreviewLogs logs;

    public ExecutionController(ExecutionService execution, ProjectService projects, PreviewLogs logs) {
        this.execution = execution;
        this.projects = projects;
        this.logs = logs;
    }

    private static Long caller(Authentication auth) {
        return (Long) auth.getPrincipal();
    }

    @PostMapping("/build")
    public BuildResult build(@PathVariable Long projectId, Authentication auth) {
        projects.requireWrite(projectId, caller(auth));
        return execution.build(projectId);
    }

    @PostMapping("/preview")
    public PreviewResponse startPreview(@PathVariable Long projectId, Authentication auth) {
        projects.requireWrite(projectId, caller(auth));
        return execution.startPreview(projectId, caller(auth));
    }

    /** Spec: "Get Preview". 404 when nothing is running, so a client can tell "none" from "error". */
    @GetMapping("/preview")
    public PreviewResponse getPreview(@PathVariable Long projectId, Authentication auth) {
        projects.getById(projectId, caller(auth));
        return execution.current(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("No preview is running for this project"));
    }

    @DeleteMapping("/preview")
    public ResponseEntity<Void> stopPreview(@PathVariable Long projectId, Authentication auth) {
        projects.requireWrite(projectId, caller(auth));
        execution.stopPreview(projectId);
        return ResponseEntity.noContent().build();
    }

    /** The buffered log, as plain JSON. {@code after} skips lines already seen. */
    @GetMapping("/preview/logs")
    public List<PreviewLogs.Line> logs(@PathVariable Long projectId,
                                       @RequestParam(defaultValue = "0") long after,
                                       Authentication auth) {
        projects.getById(projectId, caller(auth));
        return logs.since(projectId, after);
    }

    /**
     * Spec: "Logs Stream". Server-Sent Events: everything buffered first, then
     * each new line as it happens. Every event carries its sequence number as
     * the SSE id, so a client that reconnects with Last-Event-ID picks up where
     * it left off instead of replaying the whole buffer.
     */
    @GetMapping(value = "/preview/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter logStream(@PathVariable Long projectId,
                                @RequestParam(defaultValue = "0") long after,
                                @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                                Authentication auth) {
        projects.getById(projectId, caller(auth));
        long from = Math.max(after, parseOrZero(lastEventId));

        SseEmitter emitter = new SseEmitter(ExecutionService.PREVIEW_TTL.toMillis());
        AtomicLong lastSent = new AtomicLong(from);

        // The lock closes a race: a line appended between "read the backlog"
        // and "start listening" would otherwise be lost, or sent twice. The
        // listener waits for the backlog to go out, then skips anything the
        // backlog already covered.
        synchronized (emitter) {
            Runnable unsubscribe = logs.subscribe(projectId, line -> {
                synchronized (emitter) {
                    if (line.seq() > lastSent.get()) {
                        send(emitter, line);
                        lastSent.set(line.seq());
                    }
                }
            });
            emitter.onCompletion(unsubscribe);
            emitter.onTimeout(unsubscribe);
            emitter.onError(e -> unsubscribe.run());

            for (PreviewLogs.Line line : logs.since(projectId, from)) {
                send(emitter, line);
                lastSent.set(line.seq());
            }
        }
        return emitter;
    }

    /** Throws when the client has gone; PreviewLogs takes that as "unsubscribe me". */
    private static void send(SseEmitter emitter, PreviewLogs.Line line) {
        try {
            emitter.send(SseEmitter.event().id(Long.toString(line.seq())).name("log").data(line));
        } catch (IOException | IllegalStateException e) {
            throw new UncheckedIOException(new IOException("log stream client gone", e));
        }
    }

    private static long parseOrZero(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** "Nothing to preview" is the caller's situation, not a server fault. */
    @ExceptionHandler(SandboxException.class)
    ProblemDetail sandboxProblem(SandboxException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
