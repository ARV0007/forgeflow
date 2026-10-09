package com.forgeflow.chat;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * At most one reply in flight per chat session.
 *
 * Without this, two sends in quick succession would each read the history,
 * each run the agent against the same files, and each append a reply - leaving
 * a conversation that reads user, user, assistant, assistant, which is both
 * nonsense to a person and rejected by Gemini (turns must alternate).
 *
 * In-memory, which is correct for one instance - Render runs one. A second
 * instance would need this in Redis, with an expiry so a crashed instance
 * cannot hold a session forever.
 */
@Component
public class SessionLocks {

    private final Set<Long> busy = ConcurrentHashMap.newKeySet();

    /** @return false if a reply is already being generated in this session. */
    public boolean tryAcquire(Long sessionId) {
        return busy.add(sessionId);
    }

    public void release(Long sessionId) {
        busy.remove(sessionId);
    }
}
