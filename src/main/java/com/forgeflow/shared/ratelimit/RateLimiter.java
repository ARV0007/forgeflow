package com.forgeflow.shared.ratelimit;

/** Takes one token from the bucket named {@code key}, if there is one. */
public interface RateLimiter {

    Decision tryAcquire(String key, Limit limit);
}
