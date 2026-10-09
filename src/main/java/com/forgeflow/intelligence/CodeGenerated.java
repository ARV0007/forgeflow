package com.forgeflow.intelligence;

import java.time.Instant;
import java.util.List;

/**
 * Domain event: a generation run finished and changed these files.
 *
 * Published in-process today (Spring's ApplicationEventPublisher). This is
 * the seam the spec's Kafka topic "code.generated" would sit on: the
 * publisher doesn't know who listens, so moving delivery to Kafka changes
 * how it's sent, not who sends it or what it says. Fields are plain values
 * - no entities - so it serialises as-is.
 */
public record CodeGenerated(Long projectId, Long runId, Long userId, String status,
                            List<String> paths, Instant occurredAt) {
}
