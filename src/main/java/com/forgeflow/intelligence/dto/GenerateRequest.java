package com.forgeflow.intelligence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GenerateRequest(
        @NotBlank @Size(max = 4000) String prompt) {
}
