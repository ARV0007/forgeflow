package com.forgeflow.shared.events;

import java.time.Instant;
import java.util.List;

/**
 * Domain event: a generation run finished and changed these files. Topic
 * {@link Topics#CODE_GENERATED} - the spec's "code.generated".
 *
 * It lives in shared because it is a contract between modules: intelligence
 * publishes it, and the indexer (intelligence) and the preview notifier
 * (execution) consume it without knowing about each other. Plain values, no
 * entities, so it serialises to the same JSON in-process or on Kafka.
 */
public record CodeGenerated(Long projectId, Long runId, Long userId, String status,
                            List<String> paths, Instant occurredAt) {
}
