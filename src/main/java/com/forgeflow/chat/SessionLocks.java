package com.forgeflow.chat;

import com.forgeflow.shared.redis.RespClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * At most one reply in flight per chat session.
 *
 * Without this, two sends in quick succession would each read the history,
 * each run the agent against the same files, and each append a reply - leaving
 * a conversation that reads user, user, assistant, assistant, which is both
 * nonsense to a person and rejected by Gemini (turns must alternate).
 *
 * With Redis (REDIS_URL set) the lock is shared by every API instance, so two
 * sends that land on different instances still serialise:
 *
 *   acquire   SET ff:lock:chat:{session} {random token} NX PX 360000
 *             NX = only if nobody holds it; PX = it expires on its own, so an
 *             instance that dies mid-reply can't hold the session forever
 *   release   delete it ONLY if it still holds our token (a Lua script, so
 *             the check and the delete are one step) - otherwise a reply that
 *             outlived its expiry would release a lock someone else now holds
 *
 * Without Redis, or if Redis can't be reached, it falls back to a set in
 * memory - correct for one instance.
 */
@Component
public class SessionLocks {

    private static final Logger log = LoggerFactory.getLogger(SessionLocks.class);

    /** Longer than the longest reply (the stream times out at 300 s). */
    static final Duration TTL = Duration.ofMinutes(6);

    static final String RELEASE = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    private final RespClient redis;
    private final String prefix;
    private final long ttlMillis;
    private final Set<Long> local = ConcurrentHashMap.newKeySet();
    /** session -> the token we hold it with (absent = held locally, or not held). */
    private final Map<Long, String> tokens = new ConcurrentHashMap<>();

    @Autowired
    public SessionLocks(ObjectProvider<RespClient> redis) {
        this(redis.getIfAvailable(), "ff:lock:chat:", TTL);
    }

    SessionLocks(RespClient redis, String prefix, Duration ttl) {
        this.redis = redis;
        this.prefix = prefix;
        this.ttlMillis = ttl.toMillis();
    }

    /** @return false if a reply is already being generated in this session. */
    public boolean tryAcquire(Long sessionId) {
        if (redis != null) {
            String token = UUID.randomUUID().toString();
            try {
                Object r = redis.call("SET", prefix + sessionId, token, "NX", "PX", Long.toString(ttlMillis));
                if (!"OK".equals(r)) {
                    return false;
                }
                tokens.put(sessionId, token);
                return true;
            } catch (IOException | RuntimeException e) {
                log.warn("session lock in Redis failed ({}); using this instance's memory", e.toString());
            }
        }
        return local.add(sessionId);
    }

    public void release(Long sessionId) {
        String token = tokens.remove(sessionId);
        if (token != null) {
            try {
                redis.call("EVAL", RELEASE, "1", prefix + sessionId, token);
            } catch (IOException | RuntimeException e) {
                log.warn("releasing session lock {} failed ({}); it expires on its own", sessionId, e.toString());
            }
            return;
        }
        local.remove(sessionId);
    }
}
