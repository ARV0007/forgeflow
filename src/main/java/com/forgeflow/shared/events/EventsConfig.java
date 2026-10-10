package com.forgeflow.shared.events;

import com.forgeflow.shared.tracing.Tracer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Picks the event transport. {@code in-process} (default) for the
 * all-in-one deployment and tests; {@code kafka} for the full topology in
 * docker-compose.full.yml and deploy/k8s, where the indexer is its own process.
 */
@Configuration
class EventsConfig {

    @Bean
    EventBus eventBus(@Value("${forgeflow.events.transport:in-process}") String transport,
                      @Value("${forgeflow.events.kafka.bootstrap-servers:localhost:9092}") String bootstrap,
                      @Value("${forgeflow.events.kafka.client-id:forgeflow}") String clientId,
                      @Value("${forgeflow.events.kafka.partitions:3}") int partitions,
                      @Value("${forgeflow.events.kafka.max-attempts:3}") int maxAttempts,
                      @Value("${forgeflow.events.kafka.backoff-ms:500}") long backoffMs,
                      Tracer tracer) {
        return switch (transport) {
            case "in-process" -> new InProcessEventBus();
            case "kafka" -> new KafkaEventBus(bootstrap, clientId, tracer, partitions, maxAttempts,
                    Duration.ofMillis(backoffMs));
            default -> throw new IllegalArgumentException(
                    "forgeflow.events.transport must be in-process or kafka, not " + transport);
        };
    }
}
