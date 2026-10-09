package com.forgeflow.chat.dto;

import jakarta.validation.constraints.Size;

/** Title optional: an untitled session is named after its first message. */
public record CreateSessionRequest(@Size(max = 200) String title) {
}
