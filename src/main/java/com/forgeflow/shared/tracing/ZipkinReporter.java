package com.forgeflow.shared.tracing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sends spans to Zipkin's HTTP API (POST /api/v2/spans, a JSON array).
 *
 * Batched and in the background: a request must never wait on - or fail
 * because of - its own telemetry. The queue is bounded, and when Zipkin is
 * slow or down, spans are dropped and counted rather than piling up in memory.
 */
public class ZipkinReporter implements SpanReporter, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ZipkinReporter.class);
    private static final int MAX_BATCH = 200;

    private final URI endpoint;
    private final String serviceName;
    private final BlockingQueue<SpanRecord> queue = new ArrayBlockingQueue<>(10_000);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final ScheduledExecutorService flusher;
    private final AtomicLong dropped = new AtomicLong();

    public ZipkinReporter(String baseUrl, String serviceName, long flushIntervalMs) {
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/api/v2/spans");
        this.serviceName = serviceName;
        this.flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "zipkin-reporter");
            t.setDaemon(true);
            return t;
        });
        flusher.scheduleWithFixedDelay(this::flush, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void report(SpanRecord span) {
        if (!queue.offer(span)) {
            dropped.incrementAndGet();
        }
    }

    /** Send what's queued. Public so tests (and shutdown) can force it. */
    public void flush() {
        List<SpanRecord> batch = new ArrayList<>();
        queue.drainTo(batch, MAX_BATCH);
        if (batch.isEmpty()) {
            return;
        }
        List<Map<String, Object>> body = new ArrayList<>(batch.size());
        for (SpanRecord s : batch) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("traceId", s.traceId());
            m.put("id", s.id());
            if (s.parentId() != null) {
                m.put("parentId", s.parentId());
            }
            m.put("name", s.name());
            if (s.kind() != null) {
                m.put("kind", s.kind());
            }
            m.put("timestamp", s.timestamp());
            m.put("duration", Math.max(1, s.duration()));
            m.put("localEndpoint", Map.of("serviceName", serviceName));
            if (!s.tags().isEmpty()) {
                m.put("tags", s.tags());
            }
            body.add(m);
        }
        try {
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build(), HttpResponse.BodyHandlers.discarding());
            if (r.statusCode() / 100 != 2) {
                log.debug("zipkin rejected {} spans: HTTP {}", batch.size(), r.statusCode());
            }
        } catch (Exception e) {
            dropped.addAndGet(batch.size());
            log.debug("zipkin unreachable, dropped {} spans ({} total): {}", batch.size(), dropped.get(), e.toString());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public long dropped() {
        return dropped.get();
    }

    @Override
    public void close() {
        flusher.shutdown();
        flush();
    }
}
