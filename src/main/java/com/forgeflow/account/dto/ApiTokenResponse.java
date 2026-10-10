package com.forgeflow.account.dto;

import java.time.Instant;

/** @param token the secret itself - only in the response to creating it, null everywhere else */
public record ApiTokenResponse(Long id, String name, String prefix, Instant createdAt, Instant lastUsedAt,
                               String token) {
}
