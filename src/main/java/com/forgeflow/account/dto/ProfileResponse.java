package com.forgeflow.account.dto;

import java.time.Instant;

/** GET /api/v1/me. The signed-in user, minus everything secret. */
public record ProfileResponse(
        Long id,
        String email,
        String name,
        String avatarUrl,
        String provider,
        boolean emailVerified,
        Instant createdAt) {
}
