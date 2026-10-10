package com.forgeflow.intelligence.retrieval;

import com.forgeflow.shared.events.CodeGenerated;
import com.forgeflow.shared.events.EventBus;
import com.forgeflow.shared.events.Topics;
import com.forgeflow.shared.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the index warm: when a run changes files, re-index in the background,
 * so the next search doesn't pay for the embedding calls. Spec: "chunk and
 * embed and ingest", fed by code.generated.
 *
 * Where it runs is configuration:
 *
 *   all-in-one (Render, tests)   in-process bus; this subscribes in the app
 *   full mode, api instance      forgeflow.indexer.enabled=false - the API
 *                                publishes to Kafka and never embeds
 *   full mode, worker instance   subscribes to Kafka as group "indexer";
 *                                run more workers and Kafka shares the
 *                                partitions between them
 *
 * Idempotent (ensureIndexed compares file hashes), so at-least-once delivery
 * is fine. And correctness never depends on it: CodeIndex.search re-checks
 * freshness itself. This only moves the cost off the next request.
 */
@Component
public class CodeIndexer {

    private static final Logger log = LoggerFactory.getLogger(CodeIndexer.class);
    public static final String GROUP = "indexer";

    private final CodeIndex index;
    private final String mode;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public CodeIndexer(CodeIndex index, EventBus events,
                       @Value("${forgeflow.retrieval.index-on-generate:async}") String mode,
                       @Value("${forgeflow.indexer.enabled:true}") boolean enabled) {
        this.index = index;
        this.mode = mode;
        if (enabled && !"off".equals(mode)) {
            events.subscribe(Topics.CODE_GENERATED, GROUP, CodeGenerated.class, this::on);
            log.info("indexer subscribed to {} via {} (mode {})", Topics.CODE_GENERATED, events.transport(), mode);
        }
    }

    void on(CodeGenerated event) {
        if (event.paths() == null || event.paths().isEmpty()) {
            return;
        }
        Runnable job = () -> {
            CodeIndex.IndexReport r = index.ensureIndexed(event.projectId());
            log.debug("indexed project {} after run {}: {}", event.projectId(), event.runId(), r);
        };
        if ("sync".equals(mode)) {
            job.run();          // a Kafka consumer's thread, or a test: failures propagate (and get retried)
        } else {
            executor.submit(Tracer.wrap(() -> {
                try {
                    job.run();
                } catch (RuntimeException e) {
                    log.warn("background indexing of project {} failed: {}", event.projectId(), e.toString());
                }
            }));
        }
    }
}
