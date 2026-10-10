package com.forgeflow.chat;

import com.forgeflow.billing.Entitlements;
import com.forgeflow.billing.Quota;
import com.forgeflow.chat.dto.AttachmentInfo;
import com.forgeflow.chat.dto.ChatMessageResponse;
import com.forgeflow.chat.dto.ImageAttachment;
import com.forgeflow.chat.dto.ChatSessionResponse;
import com.forgeflow.chat.dto.ChatTurnResponse;
import com.forgeflow.chat.dto.ToolCallSummary;
import com.forgeflow.intelligence.AgentEvent;
import com.forgeflow.intelligence.AgentService;
import com.forgeflow.intelligence.dto.GenerateResponse;
import com.forgeflow.shared.ResourceNotFoundException;
import com.forgeflow.shared.llm.ImagePart;
import com.forgeflow.shared.llm.LlmMessage;
import com.forgeflow.workspace.ProjectService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Spec: list / create chat sessions, load full history, chat stream, retry if
 * failed - and "past 10 chat messages (session history)" fed to the model.
 *
 * A turn is split in two on purpose:
 *
 *   begin     runs on the REQUEST thread: access check, the per-session lock,
 *             and the user's message saved. Failing here is a clean 403/404/409
 *             before any stream has opened.
 *   complete  runs the agent - seconds to minutes - then saves the reply and
 *             releases the lock. It is deliberately NOT one transaction: holding
 *             a database connection open across a model call would let a handful
 *             of slow replies exhaust the pool for everybody.
 */
@Service
public class ChatService {

    private static final TypeReference<List<ToolCallSummary>> TOOL_CALLS = new TypeReference<>() { };

    private final ProjectService projects;
    private final ChatSessionRepository sessions;
    private final ChatMessageRepository messages;
    private final AgentService agent;
    private final SessionLocks locks;
    private final Entitlements entitlements;
    private final AttachmentStore attachments;
    private final ObjectMapper json = new ObjectMapper();
    private final int memoryMessages;

    public ChatService(ProjectService projects,
                       ChatSessionRepository sessions,
                       ChatMessageRepository messages,
                       AgentService agent,
                       SessionLocks locks,
                       Entitlements entitlements,
                       AttachmentStore attachments,
                       @Value("${forgeflow.chat.memory-messages:10}") int memoryMessages) {
        this.projects = projects;
        this.sessions = sessions;
        this.messages = messages;
        this.agent = agent;
        this.locks = locks;
        this.entitlements = entitlements;
        this.attachments = attachments;
        this.memoryMessages = memoryMessages;
    }

    // ------------------------------------------------------------ sessions

    @Transactional(readOnly = true)
    public List<ChatSessionResponse> listSessions(Long projectId, Long userId) {
        projects.getById(projectId, userId);
        return sessions.findByProjectIdAndDeletedAtIsNullOrderByUpdatedAtDesc(projectId).stream()
                .map(ChatSessionResponse::from).toList();
    }

    @Transactional
    public ChatSessionResponse createSession(Long projectId, Long userId, String title) {
        projects.requireWrite(projectId, userId);
        ChatSession s = new ChatSession();
        s.setProjectId(projectId);
        s.setUserId(userId);
        s.setTitle(title == null || title.isBlank() ? null : title.trim());
        return ChatSessionResponse.from(sessions.save(s));
    }

    @Transactional
    public ChatSessionResponse rename(Long projectId, Long sessionId, Long userId, String title) {
        projects.requireWrite(projectId, userId);
        ChatSession s = load(projectId, sessionId);
        s.setTitle(title.trim());
        return ChatSessionResponse.from(sessions.save(s));
    }

    @Transactional
    public void delete(Long projectId, Long sessionId, Long userId) {
        projects.requireWrite(projectId, userId);
        ChatSession s = load(projectId, sessionId);
        s.setDeletedAt(Instant.now());
        sessions.save(s);
    }

    /** Spec: "Load Full Chat History". The whole conversation, oldest first. */
    @Transactional(readOnly = true)
    public List<ChatMessageResponse> history(Long projectId, Long sessionId, Long userId) {
        projects.getById(projectId, userId);
        load(projectId, sessionId);
        List<ChatMessage> all = messages.findBySessionIdOrderByIdAsc(sessionId);
        Map<Long, List<AttachmentInfo>> images = attachments.infoFor(all.stream().map(ChatMessage::getId).toList());
        return all.stream().map(m -> toResponse(m, images.getOrDefault(m.getId(), List.of()))).toList();
    }

    // --------------------------------------------------------------- turns

    /** Everything complete() needs, captured while still on the request thread. */
    public record Turn(Long projectId, Long sessionId, Long userId, String prompt, List<ImagePart> images,
                       ChatMessage userMessage, List<LlmMessage> memory) {
    }

    public Turn begin(Long projectId, Long sessionId, Long userId, String content) {
        return begin(projectId, sessionId, userId, content, null);
    }

    /**
     * Send a new message, optionally with images. Throws 400/403/404/409 here
     * - never from inside a stream.
     */
    public Turn begin(Long projectId, Long sessionId, Long userId, String content, List<ImageAttachment> images) {
        // Bad images are a 400 before anything is saved or locked.
        List<byte[]> decoded = new ArrayList<>();
        List<ImagePart> parts = new ArrayList<>();
        for (ImageAttachment img : images == null ? List.<ImageAttachment>of() : images) {
            try {
                decoded.add(ImagePart.validate(img.mimeType(), img.data()));
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
            }
            parts.add(new ImagePart(img.mimeType(), img.data()));
        }
        projects.requireWrite(projectId, userId);
        ChatSession session = load(projectId, sessionId);
        // Out of tokens is a 402 now, before the message is saved - not a
        // saved question followed by a failed reply.
        entitlements.requireRoomFor(userId, Quota.AI_TOKENS_PER_DAY);
        acquire(sessionId);
        try {
            ChatMessage userMessage = new ChatMessage();
            userMessage.setSessionId(sessionId);
            userMessage.setRole(ChatMessage.USER);
            userMessage.setContent(content.trim());
            userMessage.setAuthorId(userId);
            userMessage = messages.save(userMessage);
            for (int i = 0; i < parts.size(); i++) {
                attachments.save(userMessage.getId(), parts.get(i).mimeType(), decoded.get(i));
            }

            if (session.getTitle() == null) {
                session.setTitle(titleFrom(content));
            }
            session.touch();
            sessions.save(session);

            return new Turn(projectId, sessionId, userId, userMessage.getContent(), List.copyOf(parts), userMessage,
                    memoryBefore(sessionId, userMessage.getId()));
        } catch (RuntimeException e) {
            locks.release(sessionId);
            throw e;
        }
    }

    /**
     * Spec: "Retry if failed". Re-runs the last question, but only when the
     * last reply did not succeed - a retry is for recovering, not for rolling
     * the dice again on something that worked.
     */
    public Turn beginRetry(Long projectId, Long sessionId, Long userId) {
        projects.requireWrite(projectId, userId);
        load(projectId, sessionId);
        entitlements.requireRoomFor(userId, Quota.AI_TOKENS_PER_DAY);
        acquire(sessionId);
        try {
            ChatMessage last = messages.findFirstBySessionIdOrderByIdDesc(sessionId)
                    .orElseThrow(() -> new IllegalStateException("Nothing to retry - the session is empty"));
            if (!last.isAssistant() || "SUCCEEDED".equals(last.getStatus())) {
                throw new IllegalStateException("Nothing to retry - the last reply succeeded");
            }
            ChatMessage question = messages
                    .findFirstBySessionIdAndRoleAndIdLessThanOrderByIdDesc(sessionId, ChatMessage.USER, last.getId())
                    .orElseThrow(() -> new IllegalStateException("Nothing to retry - no question found"));

            // Memory stops BEFORE the failed exchange: the model should see the
            // question fresh, not alongside its own failure notice.
            // The question's images go again: a retry asks exactly what was asked.
            return new Turn(projectId, sessionId, userId, question.getContent(), attachments.imagesOf(question.getId()),
                    null, memoryBefore(sessionId, question.getId()));
        } catch (RuntimeException e) {
            locks.release(sessionId);
            throw e;
        }
    }

    /** Run the agent and record its reply. Always releases the session lock. */
    public ChatTurnResponse complete(Turn turn, Consumer<AgentEvent> listener) {
        try {
            GenerateResponse run = agent.generate(turn.projectId(), turn.userId(), turn.prompt(), turn.images(),
                    turn.memory(), turn.sessionId(), listener);

            ChatMessage reply = new ChatMessage();
            reply.setSessionId(turn.sessionId());
            reply.setRole(ChatMessage.ASSISTANT);
            reply.setContent(replyText(run));
            reply.setToolCalls(toolCallsJson(run.filesWritten()));
            reply.setTokensUsed(run.totalTokens());
            reply.setStatus(run.status());
            reply.setRunId(run.runId());
            reply = messages.save(reply);

            sessions.findById(turn.sessionId()).ifPresent(s -> {
                s.touch();
                sessions.save(s);
            });

            List<AttachmentInfo> sent = turn.userMessage() == null ? List.of()
                    : attachments.infoFor(List.of(turn.userMessage().getId())).getOrDefault(turn.userMessage().getId(), List.of());
            return new ChatTurnResponse(
                    turn.userMessage() == null ? null : toResponse(turn.userMessage(), sent),
                    toResponse(reply, List.of()), run);
        } finally {
            locks.release(turn.sessionId());
        }
    }

    // ------------------------------------------------------------- memory

    /**
     * The model's view of the conversation so far: the newest N messages before
     * this point, as alternating USER / MODEL text.
     *
     * Two rules keep it valid for Gemini, which rejects a conversation that does
     * not alternate or that opens with the model:
     *  - a window that starts mid-exchange drops its leading assistant reply;
     *  - consecutive replies (a failure, then its retry) collapse to the newest.
     */
    List<LlmMessage> memoryBefore(Long sessionId, Long beforeMessageId) {
        List<ChatMessage> window = new ArrayList<>(messages.findBySessionIdAndIdLessThanOrderByIdDesc(
                sessionId, beforeMessageId, PageRequest.of(0, memoryMessages)));
        Collections.reverse(window);

        List<ChatMessage> alternating = new ArrayList<>();
        for (ChatMessage m : window) {
            if (alternating.isEmpty() && !m.isUser()) {
                continue;
            }
            if (!alternating.isEmpty() && alternating.get(alternating.size() - 1).getRole().equals(m.getRole())) {
                alternating.set(alternating.size() - 1, m);      // the later one supersedes
            } else {
                alternating.add(m);
            }
        }

        // Past images aren't re-sent (they were acted on; re-sending every one
        // would multiply the cost of each turn) - the model is told they existed.
        Map<Long, List<AttachmentInfo>> images = attachments.infoFor(alternating.stream().map(ChatMessage::getId).toList());
        List<LlmMessage> out = new ArrayList<>();
        for (ChatMessage m : alternating) {
            if (m.isUser()) {
                int n = images.getOrDefault(m.getId(), List.of()).size();
                out.add(LlmMessage.user(n == 0 ? m.getContent()
                        : m.getContent() + "\n[" + n + " image" + (n == 1 ? "" : "s") + " attached to this message]"));
            } else {
                out.add(LlmMessage.assistant(memoryText(m)));
            }
        }
        return out;
    }

    /** A past reply as the model will remember it: what it said and what it touched. */
    private String memoryText(ChatMessage reply) {
        List<ToolCallSummary> calls = parseToolCalls(reply.getToolCalls());
        if (calls.isEmpty()) {
            return reply.getContent();
        }
        StringBuilder sb = new StringBuilder(reply.getContent()).append("\n[Files written: ");
        for (int i = 0; i < calls.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(calls.get(i).path());
        }
        return sb.append(']').toString();
    }

    // ------------------------------------------------------------ helpers

    private ChatSession load(Long projectId, Long sessionId) {
        return sessions.findByIdAndProjectIdAndDeletedAtIsNull(sessionId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Chat session not found"));
    }

    private void acquire(Long sessionId) {
        if (!locks.tryAcquire(sessionId)) {
            throw new IllegalStateException("A reply is already being generated in this session");
        }
    }

    /** A reply a person can read - including when the run did not work. */
    static String replyText(GenerateResponse run) {
        if ("SUCCEEDED".equals(run.status())) {
            return run.summary() == null || run.summary().isBlank() ? "Done." : run.summary();
        }
        String files = run.filesWritten().isEmpty() ? "" : " Files written so far: " + String.join(", ", run.filesWritten()) + ".";
        return switch (run.stopReason() == null ? "ERROR" : run.stopReason()) {
            case "MAX_REPAIRS" -> "I couldn't get the build to pass after " + run.repairRounds()
                    + " repair attempts." + files;
            case "TIMEOUT" -> "I ran out of time before finishing." + files;
            case "MAX_TOOL_CALLS" -> "I hit the step limit before finishing." + files;
            case "TOKEN_BUDGET" -> "I ran out of context budget before finishing." + files;
            default -> "Something went wrong and the run stopped"
                    + (run.errorMessage() == null ? "." : ": " + run.errorMessage()) + files;
        };
    }

    private static String titleFrom(String content) {
        String oneLine = content.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= 60 ? oneLine : oneLine.substring(0, 57) + "...";
    }

    private String toolCallsJson(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return null;
        }
        return json.writeValueAsString(paths.stream().map(p -> new ToolCallSummary("write_file", p)).toList());
    }

    private List<ToolCallSummary> parseToolCalls(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return json.readValue(raw, TOOL_CALLS);
    }

    private ChatMessageResponse toResponse(ChatMessage m, List<AttachmentInfo> images) {
        return new ChatMessageResponse(m.getId(), m.getSessionId(), m.getRole(), m.getContent(), m.getAuthorId(),
                parseToolCalls(m.getToolCalls()), m.getTokensUsed(), m.getStatus(), m.getRunId(), m.getCreatedAt(),
                images);
    }

    /** One stored image's bytes - a read: anyone who can see the project. */
    @Transactional(readOnly = true)
    public AttachmentStore.Stored attachment(Long projectId, Long sessionId, Long messageId, Long attachmentId,
                                             Long userId) {
        projects.getById(projectId, userId);
        load(projectId, sessionId);
        ChatMessage m = messages.findById(messageId)
                .filter(x -> x.getSessionId().equals(sessionId))
                .orElseThrow(() -> new ResourceNotFoundException("Message not found"));
        return attachments.load(m.getId(), attachmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Attachment not found"));
    }
}
