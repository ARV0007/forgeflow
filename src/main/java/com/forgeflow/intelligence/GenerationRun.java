package com.forgeflow.intelligence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row per agent run. This single table is three things at once:
 * the observability story, the quota story, and the output table the
 * eval harness writes into.
 */
@Entity
@Table(name = "generation_runs")
public class GenerationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** RUNNING | SUCCEEDED | FAILED | CAPPED */
    @Column(nullable = false)
    private String status;

    /** FINISH_TOOL | MAX_TOOL_CALLS | TIMEOUT | TOKEN_BUDGET | NO_TOOL_CALL | MAX_REPAIRS | ERROR */
    @Column(name = "stop_reason")
    private String stopReason;

    private String model;

    @Column(name = "prompt_tokens", nullable = false)
    private Integer promptTokens = 0;

    @Column(name = "completion_tokens", nullable = false)
    private Integer completionTokens = 0;

    @Column(name = "cached_tokens", nullable = false)
    private Integer cachedTokens = 0;

    @Column(name = "cost_usd", nullable = false)
    private BigDecimal costUsd = BigDecimal.ZERO;

    @Column(name = "tool_call_count", nullable = false)
    private Integer toolCallCount = 0;

    @Column(name = "repair_rounds", nullable = false)
    private Integer repairRounds = 0;

    @Column(name = "files_written", nullable = false)
    private Integer filesWritten = 0;

    @Column(name = "build_passed")
    private Boolean buildPassed;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getStopReason() { return stopReason; }
    public void setStopReason(String stopReason) { this.stopReason = stopReason; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }

    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }

    public Integer getCachedTokens() { return cachedTokens; }
    public void setCachedTokens(Integer cachedTokens) { this.cachedTokens = cachedTokens; }

    public BigDecimal getCostUsd() { return costUsd; }
    public void setCostUsd(BigDecimal costUsd) { this.costUsd = costUsd; }

    public Integer getToolCallCount() { return toolCallCount; }
    public void setToolCallCount(Integer toolCallCount) { this.toolCallCount = toolCallCount; }

    public Integer getRepairRounds() { return repairRounds; }
    public void setRepairRounds(Integer repairRounds) { this.repairRounds = repairRounds; }

    public Integer getFilesWritten() { return filesWritten; }
    public void setFilesWritten(Integer filesWritten) { this.filesWritten = filesWritten; }

    public Boolean getBuildPassed() { return buildPassed; }
    public void setBuildPassed(Boolean buildPassed) { this.buildPassed = buildPassed; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
