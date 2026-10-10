package com.forgeflow.gateway;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Token checks at the edge. A request to a protected path with a missing,
 * forged or expired JWT is answered 401 here and never reaches a service.
 * The services still verify too - defence in depth, and they need the user
 * id anyway - but junk traffic stops at the door.
 *
 * Which paths are public mirrors the services' SecurityConfig; anything not
 * listed is protected. Erring that way is safe: a path wrongly protected
 * here fails loudly in testing, one wrongly left open would not.
 */
public class EdgeAuth {

    private final SecretKey key;

    public EdgeAuth(String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean isPublic(String method, String path) {
        if (!path.startsWith("/api/")) {
            return true;      // static workbench, previews (/p/...), /mcp (own key), docs, actuator
        }
        return path.startsWith("/api/v1/auth/")
                || ("GET".equals(method) && path.equals("/api/v1/billing/plans"))
                || ("POST".equals(method) && path.startsWith("/api/v1/billing/webhook/"));
    }

    /** The token's subject (user id) when valid; empty when missing or invalid. */
    public Optional<String> verify(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        try {
            return Optional.of(Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(authorizationHeader.substring(7).trim()).getPayload().getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
