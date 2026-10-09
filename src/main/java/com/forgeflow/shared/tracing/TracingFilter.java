package com.forgeflow.shared.tracing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Opens a server span for every request, first thing - before security, so
 * even a 401 or a 429 has a trace id.
 *
 * Continues the caller's trace when it sends a valid traceparent, and always
 * answers with one (plus X-Trace-Id, which is easier to read out over the
 * phone when someone reports a bug).
 */
public class TracingFilter extends OncePerRequestFilter {

    private final Tracer tracer;

    public TracingFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        TraceContext incoming = TraceContext.fromTraceparent(request.getHeader("traceparent"));
        try (Tracer.Span span = tracer.startServer(request.getMethod() + " " + route(request.getRequestURI()), incoming)) {
            response.setHeader("traceparent", span.context().traceparent());
            response.setHeader("X-Trace-Id", span.context().traceId());
            span.tag("http.method", request.getMethod()).tag("http.path", request.getRequestURI());
            try {
                chain.doFilter(request, response);
            } catch (IOException | ServletException | RuntimeException e) {
                span.error(e);
                throw e;
            } finally {
                span.tag("http.status_code", response.getStatus());
            }
        }
    }

    /**
     * Span names must have low cardinality - "GET /api/v1/projects/{id}", not
     * one name per project id - or the tracing UI's service map becomes a list
     * of thousands of one-off operations. Preview tokens are collapsed too.
     */
    static String route(String uri) {
        if (uri.startsWith("/p/")) {
            return "/p/{token}/**";
        }
        return uri.replaceAll("/\\d+(?=/|$)", "/{id}");
    }
}
