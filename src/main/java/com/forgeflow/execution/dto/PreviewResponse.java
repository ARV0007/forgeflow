package com.forgeflow.execution.dto;

import java.time.Instant;

/** Spec: "Get Preview". A live preview link and how long it has left. */
public record PreviewResponse(String url, String status, Instant startedAt, Instant expiresAt) {
}
