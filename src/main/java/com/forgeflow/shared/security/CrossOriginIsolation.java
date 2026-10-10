package com.forgeflow.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Cross-origin isolation for the "Run with Node" page, and only for it.
 *
 * A WebContainer runs Node.js compiled to WebAssembly in worker threads that
 * share memory - SharedArrayBuffer - and browsers only hand that out to a page
 * that promises not to load anything cross-origin without consent:
 * Cross-Origin-Opener-Policy: same-origin plus
 * Cross-Origin-Embedder-Policy: require-corp.
 *
 * Why not on every page: under require-corp the workbench's preview iframe
 * (a sandboxed, opaque-origin document) and anything it loads would need
 * opt-in headers too, and the Stripe redirect would lose its opener. The
 * WebContainer gets its own page instead, opened in a new tab.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CrossOriginIsolation extends OncePerRequestFilter {

    static final Set<String> PATHS = Set.of("/run.html", "/run.js");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (PATHS.contains(path) || path.startsWith("/vendor/")) {
            response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
            response.setHeader("Cross-Origin-Embedder-Policy", "require-corp");
            response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        }
        chain.doFilter(request, response);
    }
}
