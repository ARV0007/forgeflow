package com.forgeflow.chat;

import com.forgeflow.chat.dto.ChatMessageResponse;
import com.forgeflow.chat.dto.ChatSessionResponse;
import com.forgeflow.chat.dto.ChatTurnResponse;
import com.forgeflow.chat.dto.CreateSessionRequest;
import com.forgeflow.chat.dto.RenameSessionRequest;
import com.forgeflow.chat.dto.SendMessageRequest;
import com.forgeflow.chat.dto.VisualReviewRequest;
import com.forgeflow.chat.dto.VisualReviewResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import com.forgeflow.shared.tracing.Tracer;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Spec: AI Code Generation - List Chat Sessions, Create New Chat Session, Load
 * Full Chat History, Chat Stream, Retry if failed.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/chat/sessions")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chat;

    // Same reasoning as AgentController: a reply spends nearly all its time
    // waiting on the model, so a virtual thread per reply costs almost nothing.
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatController(ChatService chat) {
        this.chat = chat;
    }

    private static Long caller(Authentication auth) {
        return (Long) auth.getPrincipal();
    }

    @GetMapping
    public List<ChatSessionResponse> list(@PathVariable Long projectId, Authentication auth) {
        return chat.listSessions(projectId, caller(auth));
    }

    @PostMapping
    public ResponseEntity<ChatSessionResponse> create(@PathVariable Long projectId,
                                                      @Valid @RequestBody(required = false) CreateSessionRequest req,
                                                      Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(chat.createSession(projectId, caller(auth), req == null ? null : req.title()));
    }

    @PatchMapping("/{sessionId}")
    public ChatSessionResponse rename(@PathVariable Long projectId, @PathVariable Long sessionId,
                                      @Valid @RequestBody RenameSessionRequest req, Authentication auth) {
        return chat.rename(projectId, sessionId, caller(auth), req.title());
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> delete(@PathVariable Long projectId, @PathVariable Long sessionId,
                                       Authentication auth) {
        chat.delete(projectId, sessionId, caller(auth));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{sessionId}/messages")
    public List<ChatMessageResponse> history(@PathVariable Long projectId, @PathVariable Long sessionId,
                                             Authentication auth) {
        return chat.history(projectId, sessionId, caller(auth));
    }

    /** Send a message and wait for the whole reply. */
    @PostMapping("/{sessionId}/messages")
    public ChatTurnResponse send(@PathVariable Long projectId, @PathVariable Long sessionId,
                                 @Valid @RequestBody SendMessageRequest req, Authentication auth) {
        ChatService.Turn turn = chat.begin(projectId, sessionId, caller(auth), req.content(), req.images());
        return chat.complete(turn, event -> { });
    }

    /** Spec: "Chat Stream". Progress events as the agent works, then the saved reply. */
    @PostMapping(value = "/{sessionId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendStream(@PathVariable Long projectId, @PathVariable Long sessionId,
                                 @Valid @RequestBody SendMessageRequest req, Authentication auth) {
        // begin() runs HERE, on the request thread: a 403, 404 or 409 must be a
        // real HTTP status, not an error event inside a 200 stream.
        return stream(chat.begin(projectId, sessionId, caller(auth), req.content(), req.images()));
    }

    /** An image sent with a message. Browsers can't add a bearer header to <img>, so the page fetches it. */
    @GetMapping("/{sessionId}/messages/{messageId}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> attachment(@PathVariable Long projectId, @PathVariable Long sessionId,
                                             @PathVariable Long messageId, @PathVariable Long attachmentId,
                                             Authentication auth) {
        AttachmentStore.Stored img = chat.attachment(projectId, sessionId, messageId, attachmentId, caller(auth));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(img.mimeType()))
                .header("Cache-Control", "private, max-age=86400")
                .header("X-Content-Type-Options", "nosniff")
                .body(img.data());
    }

    /**
     * "AI checks its own app": the workbench screenshots the live preview after
     * a reply and sends it here; a vision model judges it against the request.
     */
    @PostMapping("/{sessionId}/messages/{messageId}/visual-review")
    public VisualReviewResponse review(@PathVariable Long projectId, @PathVariable Long sessionId,
                                       @PathVariable Long messageId, @Valid @RequestBody VisualReviewRequest req,
                                       Authentication auth) {
        return chat.review(projectId, sessionId, messageId, caller(auth), req.screenshot());
    }

    /** Spec: "Retry if failed". */
    @PostMapping("/{sessionId}/retry")
    public ChatTurnResponse retry(@PathVariable Long projectId, @PathVariable Long sessionId,
                                  Authentication auth) {
        return chat.complete(chat.beginRetry(projectId, sessionId, caller(auth)), event -> { });
    }

    @PostMapping(value = "/{sessionId}/retry/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter retryStream(@PathVariable Long projectId, @PathVariable Long sessionId,
                                  Authentication auth) {
        return stream(chat.beginRetry(projectId, sessionId, caller(auth)));
    }

    private SseEmitter stream(ChatService.Turn turn) {
        SseEmitter emitter = new SseEmitter(300_000L);
        executor.submit(Tracer.wrap(() -> {
            try {
                ChatTurnResponse result = chat.complete(turn, event -> {
                    try {
                        emitter.send(SseEmitter.event().name(event.type()).data(event));
                    } catch (Exception e) {
                        // The browser went away. Keep going: the reply is still
                        // saved, and reloading the session shows it.
                        log.debug("chat stream client gone for session {}", turn.sessionId());
                    }
                });
                emitter.send(SseEmitter.event().name("message").data(result));
                emitter.complete();
            } catch (Exception e) {
                log.debug("chat stream for session {} ended early: {}", turn.sessionId(), e.toString());
                emitter.completeWithError(e);
            }
        }));
        return emitter;
    }
}
