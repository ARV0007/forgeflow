package com.forgeflow.shared.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Events delivered by a method call, on the publisher's thread. A handler
 * that wants to do slow work hands it off itself (the indexer does), so the
 * publisher is never held up by a slow consumer it doesn't know about - and a
 * failing handler is logged, never thrown back at the publisher.
 *
 * Nothing survives a restart. That is acceptable here because every consumer
 * is a cache-warmer or a notice: search re-checks the index itself.
 */
public class InProcessEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(InProcessEventBus.class);

    private record Subscription(String group, Consumer<Object> handler) {
    }

    private final Map<String, List<Subscription>> subscriptions = new ConcurrentHashMap<>();

    @Override
    public void publish(String topic, String key, Object event) {
        for (Subscription s : subscriptions.getOrDefault(topic, List.of())) {
            try {
                s.handler().accept(event);
            } catch (RuntimeException e) {
                log.warn("handler in group {} failed on {} (key {}): {}", s.group(), topic, key, e.toString());
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> void subscribe(String topic, String group, Class<T> type, Consumer<T> handler) {
        subscriptions.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>())
                .add(new Subscription(group, event -> handler.accept((T) event)));
    }

    @Override
    public String transport() {
        return "in-process";
    }
}
