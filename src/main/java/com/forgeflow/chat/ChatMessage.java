package com.forgeflow.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** Spec: CHAT_MESSAGE. One turn of a conversation. */
@Entity
@Table(name = "chat_messages")
public class ChatMessage {

    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /** user | assistant | system | tool - enforced by a CHECK constraint. */
    @Column(nullable = false)
    private String role;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    /** For user messages: who said it. A session can be shared by several editors. */
    @Column(name = "author_id")
    private Long authorId;

    /**
     * For assistant messages: what the agent did, as a JSON array of
     * {"name": "write_file", "path": "index.html"}. Names and paths only - the
     * file contents live in project_files, and copying them into every chat
     * message would store each file once per edit.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_calls", columnDefinition = "jsonb")
    private String toolCalls;

    @Column(name = "tool_call_id")
    private String toolCallId;

    @Column(name = "tokens_used", nullable = false)
    private int tokensUsed;

    @Column(name = "cost_usd")
    private java.math.BigDecimal costUsd;

    /** For assistant messages: SUCCEEDED | FAILED | CAPPED, copied from the run. */
    private String status;

    @Column(name = "run_id")
    private Long runId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public boolean isUser() {
        return USER.equals(role);
    }

    public boolean isAssistant() {
        return ASSISTANT.equals(role);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Long getAuthorId() { return authorId; }
    public void setAuthorId(Long authorId) { this.authorId = authorId; }

    public String getToolCalls() { return toolCalls; }
    public void setToolCalls(String toolCalls) { this.toolCalls = toolCalls; }

    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }

    public int getTokensUsed() { return tokensUsed; }
    public void setTokensUsed(int tokensUsed) { this.tokensUsed = tokensUsed; }

    public java.math.BigDecimal getCostUsd() { return costUsd; }
    public void setCostUsd(java.math.BigDecimal costUsd) { this.costUsd = costUsd; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
