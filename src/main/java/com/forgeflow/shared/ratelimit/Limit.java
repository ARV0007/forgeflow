package com.forgeflow.shared.ratelimit;

import java.time.Duration;

/**
 * A token bucket: holds up to {@code capacity} tokens, refills at
 * capacity-per-period, each request takes one.
 *
 * "10 per minute" therefore allows a burst of 10 and then one every six
 * seconds - smoother than a fixed window, which would allow 10 at 12:00:59
 * and 10 more at 12:01:00.
 */
public record Limit(String name, int capacity, Duration period) {

    public Limit {
        if (capacity <= 0 || period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("A limit needs a positive capacity and period");
        }
    }

    /** Tokens added per millisecond. */
    public double refillPerMs() {
        return (double) capacity / period.toMillis();
    }
}
