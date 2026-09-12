package com.forgeflow.workspace.dto;

import com.forgeflow.workspace.Project;

import java.time.Instant;

public record ProjectResponse(
        Long id,
        String name,
        String description,
        Instant createdAt,
        Instant updatedAt) {

    public static ProjectResponse from(Project p) {
        return new ProjectResponse(p.getId(), p.getName(), p.getDescription(),
                p.getCreatedAt(), p.getUpdatedAt());
    }
}
