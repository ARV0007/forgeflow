package com.forgeflow.shared.ratelimit;

/** The answer for one request: go ahead, or wait this long. */
public record Decision(boolean allowed, long remaining, long retryAfterMs) {
}
