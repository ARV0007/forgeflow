package com.forgeflow.workspace.dto;

import com.forgeflow.workspace.ProjectRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Invite an existing ForgeFlow user by email, as EDITOR or VIEWER. */
public record AddMemberRequest(
        @NotBlank @Email String email,
        @NotNull ProjectRole role) {
}
