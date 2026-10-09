package com.forgeflow.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * PUT /api/v1/projects/{id}. name is required (it is a replace); the spec's new
 * fields are optional so existing clients keep working - null leaves them as
 * they are.
 */
public record UpdateProjectRequest(
        @NotBlank @Size(max = 120) String name,
        String description,
        Boolean isPublic,
        @Size(max = 512) @Pattern(regexp = "^https://\\S+$", message = "must be an https:// URL")
        String thumbnailUrl) {
}
