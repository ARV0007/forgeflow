package com.forgeflow.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A file saved by a person rather than the agent - a hand edit, or an import. */
public record SaveFileRequest(
        @NotBlank @Size(max = 512) String path,
        @NotNull String content) {
}
