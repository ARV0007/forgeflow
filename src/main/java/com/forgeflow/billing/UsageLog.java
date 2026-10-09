package com.forgeflow.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Spec: USAGE_LOG. Append-only: one row per billable thing that happened.
 * Never updated, never deleted - it is the record a limit is checked against
 * and the record a disputed bill would be settled from.
 */
@Entity
@Table(name = "usage_logs")
public class UsageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "project_id", updatable = false)
    private Long projectId;

    @Column(nullable = false, updatable = false)
    private String kind;

    @Column(nullable = false, updatable = false)
    private Long quantity;

    /** What it was for, e.g. "run:42". */
    @Column(updatable = false)
    private String ref;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UsageLog() {
    }

    public UsageLog(Long userId, Long projectId, String kind, long quantity, String ref) {
        this.userId = userId;
        this.projectId = projectId;
        this.kind = kind;
        this.quantity = quantity;
        this.ref = ref;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getProjectId() { return projectId; }
    public String getKind() { return kind; }
    public Long getQuantity() { return quantity; }
    public String getRef() { return ref; }
    public Instant getCreatedAt() { return createdAt; }
}
