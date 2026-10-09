package com.forgeflow.shared.tracing;

import java.util.Map;

/** A finished span, in the shape Zipkin's v2 API wants. Times are in microseconds. */
public record SpanRecord(String traceId, String id, String parentId, String name, String kind,
                         long timestamp, long duration, Map<String, String> tags) {
}
