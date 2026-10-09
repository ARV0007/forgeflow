package com.forgeflow.workspace.dto;

import com.forgeflow.workspace.ProjectRole;
import jakarta.validation.constraints.NotNull;

public record UpdateMemberRequest(@NotNull ProjectRole role) {
}
