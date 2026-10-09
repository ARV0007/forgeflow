package com.forgeflow.workspace.dto;

import com.forgeflow.workspace.Project;
import com.forgeflow.workspace.ProjectRole;

import java.time.Instant;

/**
 * @param role the CALLER's role on this project. The same project reads
 *             differently to its owner and to a viewer, and the client needs to
 *             know which buttons to show without guessing.
 */
public record ProjectResponse(
        Long id,
        String name,
        String description,
        Long ownerId,
        ProjectRole role,
        boolean isPublic,
        String thumbnailUrl,
        Instant createdAt,
        Instant updatedAt) {

    public static ProjectResponse from(Project p, ProjectRole role) {
        return new ProjectResponse(p.getId(), p.getName(), p.getDescription(), p.getOwnerId(), role,
                p.isPublic(), p.getThumbnailUrl(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
