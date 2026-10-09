package com.forgeflow.workspace.dto;

import com.forgeflow.workspace.ProjectRole;

import java.time.Instant;

/** One row of a project's member list. The owner appears too, with role OWNER. */
public record MemberResponse(
        Long userId,
        String email,
        String name,
        String avatarUrl,
        ProjectRole role,
        Long invitedBy,
        Instant invitedAt) {
}
