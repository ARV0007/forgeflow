package com.forgeflow.shared.events;

import java.util.function.Consumer;

/**
 * Publish domain events; subscribe to them by topic and consumer group.
 *
 * Two implementations, chosen by {@code forgeflow.events.transport}:
 *
 *   in-process  handlers run in this JVM, at publish time (Render, tests)
 *   kafka       a real broker: events survive restarts, and a handler can
 *               live in a different process - the indexing worker
 *
 * The publisher's code is the same either way. That is the point: the
 * architecture diagram's Kafka arrow is a deployment decision, not a rewrite.
 *
 * Contract for handlers, either way: at-least-once delivery, so a handler
 * must be idempotent - getting the same event twice must be harmless.
 */
public interface EventBus {

    void publish(String topic, String key, Object event);

    /**
     * @param group a consumer group: every group gets every event once; two
     *              subscribers in the same group share the work (on Kafka)
     */
    <T> void subscribe(String topic, String group, Class<T> type, Consumer<T> handler);

    /** "in-process" or "kafka" - for health/info endpoints and logs. */
    String transport();
}
