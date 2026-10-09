package com.forgeflow.shared.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Which requests are limited, by what, and how hard. Most specific rule first;
 * the first match wins, so a request is only ever counted against one bucket.
 *
 * Keyed by user where we know who it is, by IP where we don't (login, signup,
 * the unauthenticated MCP endpoint).
 */
public class RateLimitRules {

    public record Match(Limit limit, String key) {
    }

    private static final Pattern AI = Pattern.compile(
            "^/api/v1/projects/\\d+/(generate(/stream)?|chat/sessions/\\d+/(messages(/stream)?|retry(/stream)?))$");
    private static final Pattern INVITE = Pattern.compile("^/api/v1/projects/\\d+/members$");

    private final Limit auth;
    private final Limit ai;
    private final Limit mcp;
    private final Limit invites;
    private final Limit api;

    public RateLimitRules(int authPerMinute, int aiPerMinute, int mcpPerMinute, int invitesPerHour, int apiPerMinute) {
        this.auth = new Limit("auth", authPerMinute, Duration.ofMinutes(1));
        this.ai = new Limit("ai", aiPerMinute, Duration.ofMinutes(1));
        this.mcp = new Limit("mcp", mcpPerMinute, Duration.ofMinutes(1));
        this.invites = new Limit("invites", invitesPerHour, Duration.ofHours(1));
        this.api = new Limit("api", apiPerMinute, Duration.ofMinutes(1));
    }

    /** @param userId the authenticated caller, or null */
    public Match match(HttpServletRequest request, Long userId) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        String ip = request.getRemoteAddr();
        boolean post = "POST".equals(method);

        // Password guessing and signup spam: per IP, before anyone is known.
        if (post && (path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/signup"))) {
            return new Match(auth, "ip:" + ip);
        }
        // No login on /mcp, so the caller's address is all there is.
        if (post && path.equals("/mcp")) {
            return new Match(mcp, "ip:" + ip);
        }
        if (userId == null) {
            return null;          // anything else anonymous is a 401 anyway, or static/preview content
        }
        // The expensive ones: every call here spends model tokens.
        if (post && AI.matcher(path).matches()) {
            return new Match(ai, "user:" + userId);
        }
        // Invites send nothing today, but they will (email), and they let one
        // account probe which emails are registered.
        if (post && INVITE.matcher(path).matches()) {
            return new Match(invites, "user:" + userId);
        }
        if (path.startsWith("/api/")) {
            return new Match(api, "user:" + userId);
        }
        return null;
    }
}
