package com.forgeflow.shared.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Spec: rate limiting. Runs inside the security chain, right after the JWT
 * filter, so it knows who the caller is.
 *
 * Deliberately NOT a @Component. Spring Boot registers every Filter bean as a
 * plain servlet filter too, and that copy would run BEFORE Spring Security -
 * when nobody is authenticated yet - limiting every user as anonymous.
 *
 * Over the limit is a 429 with Retry-After, which well-behaved clients (and
 * Stripe, and MCP hosts) honour automatically.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;
    private final RateLimitRules rules;

    public RateLimitFilter(RateLimiter limiter, RateLimitRules rules) {
        this.limiter = limiter;
        this.rules = rules;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RateLimitRules.Match match = rules.match(request, currentUser());
        if (match == null) {
            chain.doFilter(request, response);
            return;
        }

        Decision d = limiter.tryAcquire(match.key(), match.limit());
        response.setHeader("X-RateLimit-Limit", Integer.toString(match.limit().capacity()));
        response.setHeader("X-RateLimit-Remaining", Long.toString(d.remaining()));
        if (d.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        long seconds = Math.max(1, (d.retryAfterMs() + 999) / 1000);
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(seconds));
        response.setContentType("application/problem+json");
        // Written by hand: this runs before the DispatcherServlet, so the
        // @RestControllerAdvice that formats every other error can't reach it.
        response.getWriter().write("""
                {"type":"about:blank","title":"Too Many Requests","status":429,\
                "detail":"Slow down - try again in %d second%s.","limit":"%s","retryAfterSeconds":%d}"""
                .formatted(seconds, seconds == 1 ? "" : "s", match.limit().name(), seconds));
    }

    private static Long currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof Long id ? id : null;
    }
}
