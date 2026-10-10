package com.forgeflow.chat.dto;

import jakarta.validation.constraints.NotBlank;

/** An image in a request body: its type and its bytes as base64 (no "data:" prefix). */
public record ImageAttachment(@NotBlank String mimeType, @NotBlank String data) {
}
