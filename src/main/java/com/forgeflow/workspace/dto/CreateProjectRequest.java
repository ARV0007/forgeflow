package com.forgeflow.workspace.dto;

import com.forgeflow.workspace.Stack;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Note what is NOT here: ownerId. Jackson has nowhere to bind it, so a forged
 * owner does not get rejected - it evaporates. Making the lie impossible beats
 * validating against it.
 *
 * @param stack STATIC (default) or REACT - fixed for the project's life
 */
public record CreateProjectRequest(
        @NotBlank @Size(max = 120) String name,
        String description,
        Stack stack) {

    public CreateProjectRequest(String name, String description) {
        this(name, description, null);
    }
}
