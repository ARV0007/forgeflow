package com.forgeflow.shared.tracing;

import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Spans, without a tracing library.
 *
 * The current span lives in a ThreadLocal and is mirrored into the logging
 * MDC, so every log line carries its trace id. Starting a child span pushes;
 * finishing it pops back to the parent.
 *
 * Work handed to another thread (the SSE executors, the background indexer)
 * loses ThreadLocals - wrap it with {@link #wrap(Runnable)} to carry the
 * trace across.
 */
public class Tracer {

    public static final String MDC_TRACE = "traceId";
    public static final String MDC_SPAN = "spanId";

    private static final ThreadLocal<TraceContext> CURRENT = new ThreadLocal<>();

    private final SpanReporter reporter;

    public Tracer(SpanReporter reporter) {
        this.reporter = reporter;
    }

    /** The trace id of whatever is running on this thread, or null outside any trace. */
    public static String currentTraceId() {
        TraceContext c = CURRENT.get();
        return c == null ? null : c.traceId();
    }

    public static TraceContext current() {
        return CURRENT.get();
    }

    /** An open span. Close it exactly once, in a finally block or try-with-resources. */
    public final class Span implements AutoCloseable {

        private final TraceContext context;
        private final TraceContext previous;
        private final String name;
        private final String kind;
        private final long startMicros;
        private final long startNanos;
        private final Map<String, String> tags = new LinkedHashMap<>();
        private boolean closed;

        private Span(TraceContext context, TraceContext previous, String name, String kind) {
            this.context = context;
            this.previous = previous;
            this.name = name;
            this.kind = kind;
            this.startMicros = System.currentTimeMillis() * 1000;
            this.startNanos = System.nanoTime();
            activate(context);
        }

        public Span tag(String key, Object value) {
            if (value != null) {
                tags.put(key, String.valueOf(value));
            }
            return this;
        }

        public Span error(Throwable t) {
            return tag("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        public TraceContext context() {
            return context;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            long micros = (System.nanoTime() - startNanos) / 1000;
            reporter.report(new SpanRecord(context.traceId(), context.spanId(), context.parentSpanId(),
                    name, kind, startMicros, micros, Map.copyOf(tags)));
            activate(previous);
        }
    }

    /** A server span for an incoming request: continues {@code incoming} if given, else starts a trace. */
    public Span startServer(String name, TraceContext incoming) {
        return new Span(incoming != null ? incoming : TraceContext.root(), CURRENT.get(), name, "SERVER");
    }

    /** A message being sent: a child of the current span, marked PRODUCER (Zipkin draws the hop). */
    public Span startProducer(String name) {
        TraceContext parent = CURRENT.get();
        return new Span(parent == null ? TraceContext.root() : parent.child(), parent, name, "PRODUCER");
    }

    /**
     * A message being handled: continues the producer's trace from the
     * message's traceparent header, so one trace runs from the HTTP request
     * that caused an event to the worker that consumed it.
     */
    public Span startConsumer(String name, TraceContext incoming) {
        return new Span(incoming != null ? incoming.child() : TraceContext.root(), CURRENT.get(), name, "CONSUMER");
    }

    /** A child of the current span - or a new trace if there is none (a scheduled job, say). */
    public Span start(String name) {
        TraceContext parent = CURRENT.get();
        return new Span(parent == null ? TraceContext.root() : parent.child(), parent, name, null);
    }

    /** Run {@code work} inside a child span named {@code name}, recording any exception on it. */
    public <T> T inSpan(String name, Supplier<T> work) {
        try (Span span = start(name)) {
            try {
                return work.get();
            } catch (RuntimeException e) {
                span.error(e);
                throw e;
            }
        }
    }

    /** Carry the current trace onto whatever thread runs {@code task}. */
    public static Runnable wrap(Runnable task) {
        TraceContext captured = CURRENT.get();
        return () -> {
            TraceContext before = CURRENT.get();
            activate(captured);
            try {
                task.run();
            } finally {
                activate(before);
            }
        };
    }

    private static void activate(TraceContext c) {
        if (c == null) {
            CURRENT.remove();
            MDC.remove(MDC_TRACE);
            MDC.remove(MDC_SPAN);
        } else {
            CURRENT.set(c);
            MDC.put(MDC_TRACE, c.traceId());
            MDC.put(MDC_SPAN, c.spanId());
        }
    }
}
