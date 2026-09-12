package com.forgeflow.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Note what is NOT here: ownerId. Jackson has nowhere to bind it, so a forged
 * owner does not get rejected - it evaporates. Making the lie impossible beats
 * validating against it.
 */
public record CreateProjectRequest(
        @NotBlank @Size(max = 120) String name,
        String description) {
}
