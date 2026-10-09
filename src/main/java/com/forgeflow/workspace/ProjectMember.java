package com.forgeflow.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Someone the owner has let into a project. The owner is never a row here -
 * ownership lives in projects.owner_id, so it cannot be granted or revoked by
 * accident through the members API.
 */
@Entity
@Table(name = "project_members")
public class ProjectMember {

    @EmbeddedId
    private Key id;

    /** EDITOR or VIEWER; the database CHECK constraint enforces the same. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProjectRole role;

    @Column(name = "invited_by")
    private Long invitedBy;

    @Column(name = "invited_at", nullable = false, updatable = false)
    private Instant invitedAt;

    @PrePersist
    void onCreate() {
        if (invitedAt == null) {
            invitedAt = Instant.now();
        }
    }

    public static ProjectMember of(Long projectId, Long userId, ProjectRole role, Long invitedBy) {
        ProjectMember m = new ProjectMember();
        m.id = new Key(projectId, userId);
        m.role = role;
        m.invitedBy = invitedBy;
        return m;
    }

    public Long getProjectId() { return id.projectId; }
    public Long getUserId() { return id.userId; }

    public Key getId() { return id; }
    public void setId(Key id) { this.id = id; }

    public ProjectRole getRole() { return role; }
    public void setRole(ProjectRole role) { this.role = role; }

    public Long getInvitedBy() { return invitedBy; }
    public void setInvitedBy(Long invitedBy) { this.invitedBy = invitedBy; }

    public Instant getInvitedAt() { return invitedAt; }
    public void setInvitedAt(Instant invitedAt) { this.invitedAt = invitedAt; }

    /** (project_id, user_id) - a person is a member of a project at most once. */
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "project_id", nullable = false)
        private Long projectId;

        @Column(name = "user_id", nullable = false)
        private Long userId;

        protected Key() {
        }

        public Key(Long projectId, Long userId) {
            this.projectId = projectId;
            this.userId = userId;
        }

        public Long getProjectId() { return projectId; }
        public Long getUserId() { return userId; }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(projectId, k.projectId) && Objects.equals(userId, k.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(projectId, userId);
        }
    }
}
