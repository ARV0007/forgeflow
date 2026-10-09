package com.forgeflow.shared.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The token bucket in Redis, as one Lua script.
 *
 * Why a script: "read the bucket, decide, write it back" as three separate
 * commands is a race - two instances read 1 token at the same moment and both
 * spend it. Redis runs a script atomically, so read-decide-write is one step
 * for everyone.
 *
 * Why Redis's clock (TIME) and not ours: instances' clocks drift. If each
 * passed its own "now", a fast clock would refill buckets early.
 *
 * If Redis is down, this falls back to an in-memory bucket rather than either
 * blocking everyone (fail closed) or letting everything through (fail open
 * with no limit at all).
 */
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    static final String SCRIPT = """
            local capacity = tonumber(ARGV[1])
            local refill_per_ms = tonumber(ARGV[2])
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local b = redis.call('HMGET', KEYS[1], 'tokens', 'at')
            local tokens = tonumber(b[1])
            local at = tonumber(b[2])
            if tokens == nil then
              tokens = capacity
            else
              tokens = math.min(capacity, tokens + (now - at) * refill_per_ms)
            end
            local allowed = 0
            local retry = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            else
              retry = math.ceil((1 - tokens) / refill_per_ms)
            end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'at', tostring(now))
            redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / refill_per_ms) + 1000)
            return {allowed, math.floor(tokens), retry}
            """;

    private final RespClient redis;
    private final RateLimiter fallback;
    private final String keyPrefix;
    private volatile String sha;
    private final AtomicLong lastWarning = new AtomicLong();

    public RedisRateLimiter(RespClient redis, RateLimiter fallback, String keyPrefix) {
        this.redis = redis;
        this.fallback = fallback;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public Decision tryAcquire(String key, Limit limit) {
        String redisKey = keyPrefix + limit.name() + ":" + key;
        try {
            List<?> r = (List<?>) eval(redisKey, Integer.toString(limit.capacity()),
                    Double.toString(limit.refillPerMs()));
            return new Decision(((Long) r.get(0)) == 1L, (Long) r.get(1), (Long) r.get(2));
        } catch (IOException | RuntimeException e) {
            warnAtMostOncePerMinute(e);
            return fallback.tryAcquire(key, limit);
        }
    }

    /**
     * EVALSHA sends 40 bytes instead of the whole script. The first call - or
     * the first after Redis restarts and forgets its script cache - gets
     * NOSCRIPT, and falls back to EVAL, which also re-caches it.
     */
    private Object eval(String key, String... args) throws IOException {
        String cached = sha;
        if (cached != null) {
            try {
                return redis.call(concat("EVALSHA", cached, "1", key, args));
            } catch (RespClient.RedisError e) {
                if (!e.getMessage().startsWith("NOSCRIPT")) {
                    throw e;
                }
            }
        }
        sha = (String) redis.call("SCRIPT", "LOAD", SCRIPT);
        return redis.call(concat("EVALSHA", sha, "1", key, args));
    }

    private static String[] concat(String a, String b, String c, String d, String[] rest) {
        String[] out = new String[4 + rest.length];
        out[0] = a;
        out[1] = b;
        out[2] = c;
        out[3] = d;
        System.arraycopy(rest, 0, out, 4, rest.length);
        return out;
    }

    private void warnAtMostOncePerMinute(Exception e) {
        long now = System.currentTimeMillis();
        long last = lastWarning.get();
        if (now - last > 60_000 && lastWarning.compareAndSet(last, now)) {
            log.warn("Redis rate limiter unavailable ({}), limiting per instance until it's back", e.toString());
        }
    }
}
