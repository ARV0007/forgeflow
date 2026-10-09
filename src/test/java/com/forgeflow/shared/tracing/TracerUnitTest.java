package com.forgeflow.shared.tracing;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TracerUnitTest {

    @Test
    void routesHaveLowCardinality() {
        assertThat(TracingFilter.route("/api/v1/projects/42/chat/sessions/7/messages"))
                .isEqualTo("/api/v1/projects/{id}/chat/sessions/{id}/messages");
        assertThat(TracingFilter.route("/api/v1/projects")).isEqualTo("/api/v1/projects");
        assertThat(TracingFilter.route("/p/9f8e7d/index.html")).isEqualTo("/p/{token}/**");
        assertThat(TracingFilter.route("/api/v1/billing/plans")).isEqualTo("/api/v1/billing/plans");
    }

    @Test
    void childSpansNestAndTheMdcFollows() {
        List<SpanRecord> out = new CopyOnWriteArrayList<>();
        Tracer tracer = new Tracer(out::add);

        try (Tracer.Span outer = tracer.start("outer")) {
            assertThat(MDC.get("traceId")).isEqualTo(outer.context().traceId());
            tracer.inSpan("inner", () -> {
                assertThat(MDC.get("spanId")).isNotEqualTo(outer.context().spanId());
                return null;
            });
            assertThat(MDC.get("spanId")).isEqualTo(outer.context().spanId());     // popped back
        }
        assertThat(MDC.get("traceId")).isNull();
        assertThat(Tracer.currentTraceId()).isNull();

        assertThat(out).extracting(SpanRecord::name).containsExactly("inner", "outer");
        assertThat(out.get(0).parentId()).isEqualTo(out.get(1).id());
        assertThat(out.get(0).traceId()).isEqualTo(out.get(1).traceId());
    }

    @Test
    void wrapCarriesTheTraceToAnotherThread() throws Exception {
        Tracer tracer = new Tracer(SpanReporter.NOOP);
        AtomicReference<String> seen = new AtomicReference<>();
        String traceId;
        try (Tracer.Span span = tracer.start("request")) {
            traceId = span.context().traceId();
            Thread t = Thread.ofVirtual().start(Tracer.wrap(() -> seen.set(MDC.get("traceId"))));
            t.join();
        }
        assertThat(seen.get()).isEqualTo(traceId);
    }

    @Test
    void anExceptionIsRecordedOnTheSpanAndStillThrown() {
        List<SpanRecord> out = new CopyOnWriteArrayList<>();
        Tracer tracer = new Tracer(out::add);
        try {
            tracer.inSpan("boom", () -> {
                throw new IllegalStateException("nope");
            });
        } catch (IllegalStateException expected) {
            // fine
        }
        assertThat(out.get(0).tags()).containsEntry("error", "IllegalStateException: nope");
    }

    @Test
    void zipkinReceivesV2Json() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        HttpServer zipkin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        zipkin.createContext("/", ex -> {
            path.set(ex.getRequestURI().getPath());
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(202, -1);
            ex.close();
        });
        zipkin.start();
        try (ZipkinReporter reporter = new ZipkinReporter("http://127.0.0.1:" + zipkin.getAddress().getPort(),
                "forgeflow", 60_000)) {
            Tracer tracer = new Tracer(reporter);
            try (Tracer.Span server = tracer.startServer("GET /x", null)) {
                server.tag("http.status_code", 200);
                tracer.inSpan("llm.chat", () -> 1);
            }
            reporter.flush();

            assertThat(path.get()).isEqualTo("/api/v2/spans");
            JsonNode spans = new ObjectMapper().readTree(body.get());
            assertThat(spans).hasSize(2);
            JsonNode child = spans.get(0);
            JsonNode root = spans.get(1);
            assertThat(root.path("kind").asText()).isEqualTo("SERVER");
            assertThat(root.has("parentId")).isFalse();
            assertThat(root.path("localEndpoint").path("serviceName").asText()).isEqualTo("forgeflow");
            assertThat(root.path("tags").path("http.status_code").asText()).isEqualTo("200");
            assertThat(child.path("parentId").asText()).isEqualTo(root.path("id").asText());
            assertThat(child.path("duration").asLong()).isPositive();
        } finally {
            zipkin.stop(0);
        }
    }

    @Test
    void anUnreachableZipkinDropsSpansQuietly() {
        try (ZipkinReporter reporter = new ZipkinReporter("http://127.0.0.1:1", "forgeflow", 60_000)) {
            new Tracer(reporter).inSpan("x", () -> 1);
            reporter.flush();
            assertThat(reporter.dropped()).isEqualTo(1);
        }
    }
}
