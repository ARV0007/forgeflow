package com.forgeflow.execution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "previews")
@Getter
@Setter
public class Preview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "container_id")
    private String containerId;

    @Column(name = "preview_url")
    private String previewUrl;

    /** STARTING | RUNNING | STOPPED | FAILED */
    @Column(nullable = false)
    private String status;

    @Column(name = "started_at")
    private Instant startedAt;

    /** Previews are not free - each one is a running container. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
