package com.forgeflow.shared.tracing;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Where we are in a trace: which trace (one per user action, shared by every
 * service it touches) and which span (one step within it).
 *
 * Serialised as a W3C traceparent header - the standard every tracing system
 * (Zipkin, Jaeger, Datadog, cloud load balancers) reads:
 *
 *   traceparent: 00-<32 hex trace id>-<16 hex span id>-<2 hex flags>
 */
public record TraceContext(String traceId, String spanId, String parentSpanId) {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern TRACEPARENT =
            Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$");
    private static final String ZERO_TRACE = "0".repeat(32);
    private static final String ZERO_SPAN = "0".repeat(16);

    /** A brand-new trace. */
    public static TraceContext root() {
        return new TraceContext(hex(16), hex(8), null);
    }

    /** A step inside this one. */
    public TraceContext child() {
        return new TraceContext(traceId, hex(8), spanId);
    }

    /**
     * Continue the caller's trace, or null if the header is absent or invalid.
     * All-zero ids are invalid by the spec - and a client that sends garbage
     * gets a fresh trace, not an error.
     */
    public static TraceContext fromTraceparent(String header) {
        if (header == null) {
            return null;
        }
        var m = TRACEPARENT.matcher(header.trim().toLowerCase());
        if (!m.matches() || ZERO_TRACE.equals(m.group(1)) || ZERO_SPAN.equals(m.group(2))) {
            return null;
        }
        // Their span is our parent; we are a new span in their trace.
        return new TraceContext(m.group(1), hex(8), m.group(2));
    }

    public String traceparent() {
        return "00-" + traceId + "-" + spanId + "-01";
    }

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
