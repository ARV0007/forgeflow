package com.forgeflow.intelligence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row per agent run. This single table is three things at once:
 * the observability story, the quota story, and the output table the
 * eval harness writes into on Day 9.
 */
@Entity
@Table(name = "generation_runs")
@Getter
@Setter
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

    /** FINISH_TOOL | MAX_TOOL_CALLS | TIMEOUT | TOKEN_BUDGET | NO_TOOL_CALL | ERROR */
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
}
