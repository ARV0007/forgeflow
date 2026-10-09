package com.forgeflow.intelligence.retrieval;

import com.forgeflow.intelligence.CodeGenerated;
import com.forgeflow.shared.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the index warm: when a run changes files, re-index in the background,
 * so the next search doesn't pay for the embedding calls.
 *
 * The first consumer of CodeGenerated. With Kafka this class would be a
 * consumer in a separate indexing service; the event it reads would not change.
 *
 * Correctness never depends on this listener: CodeIndex.search re-checks
 * freshness itself. This only moves the cost off the next request.
 */
@Component
public class CodeIndexer {

    private static final Logger log = LoggerFactory.getLogger(CodeIndexer.class);

    private final CodeIndex index;
    private final String mode;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public CodeIndexer(CodeIndex index, @Value("${forgeflow.retrieval.index-on-generate:async}") String mode) {
        this.index = index;
        this.mode = mode;
    }

    @EventListener
    public void on(CodeGenerated event) {
        if (event.paths().isEmpty() || "off".equals(mode)) {
            return;
        }
        Runnable job = () -> {
            try {
                index.ensureIndexed(event.projectId());
            } catch (RuntimeException e) {
                log.warn("background indexing of project {} failed: {}", event.projectId(), e.toString());
            }
        };
        if ("sync".equals(mode)) {
            job.run();
        } else {
            executor.submit(Tracer.wrap(job));       // logs from the job keep the run's trace id
        }
    }
}
