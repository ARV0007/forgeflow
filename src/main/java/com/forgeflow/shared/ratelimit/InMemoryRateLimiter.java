package com.forgeflow.shared.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The same token bucket as the Redis script, in this JVM.
 *
 * Correct for one instance. With several, each would hand out its own full
 * allowance - which is why Redis exists here. Also the fallback when Redis is
 * unreachable: limiting per instance is far better than not limiting at all.
 */
public class InMemoryRateLimiter implements RateLimiter {

    private static final int MAX_KEYS = 100_000;

    private record Bucket(double tokens, long at) {
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public InMemoryRateLimiter() {
        this(System::currentTimeMillis);
    }

    /** With an injectable clock, so tests can move time instead of sleeping. */
    public InMemoryRateLimiter(LongSupplier clockMs) {
        this.clock = clockMs;
    }

    @Override
    public Decision tryAcquire(String key, Limit limit) {
        if (buckets.size() > MAX_KEYS) {
            // Crude, but bounded: a flood of distinct keys (spoofed IPs, say)
            // must not grow memory without end. Everyone starts with a full bucket.
            buckets.clear();
        }
        long now = clock.getAsLong();
        Decision[] out = new Decision[1];
        // compute() runs atomically per key: two threads cannot both spend the last token.
        buckets.compute(limit.name() + ":" + key, (k, b) -> {
            double tokens = b == null
                    ? limit.capacity()
                    : Math.min(limit.capacity(), b.tokens() + (now - b.at()) * limit.refillPerMs());
            if (tokens >= 1) {
                out[0] = new Decision(true, (long) Math.floor(tokens - 1), 0);
                return new Bucket(tokens - 1, now);
            }
            out[0] = new Decision(false, 0, (long) Math.ceil((1 - tokens) / limit.refillPerMs()));
            return new Bucket(tokens, now);
        });
        return out[0];
    }
}
