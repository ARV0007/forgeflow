package com.forgeflow.shared.tracing;

/** Where finished spans go. Nowhere, unless ZIPKIN_URL is set. */
public interface SpanReporter {

    void report(SpanRecord span);

    SpanReporter NOOP = span -> { };
}
