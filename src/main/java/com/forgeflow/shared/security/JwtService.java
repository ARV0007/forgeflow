package com.forgeflow.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * Mints and verifies JWTs. The secret must be at least 32 bytes for HS256.
 * Verification is local - no database hit - which is the whole point of a JWT.
 * Trade-off: a token cannot be revoked before it expires.
 *
 * Sessions slide. A token lives {@code expiry-minutes}; once it is past half
 * of that, the next request it authenticates gets a fresh one back (see
 * JwtAuthFilter). So someone actively working is never signed out mid-task,
 * while an idle tab still expires. The "auth_time" claim - when the person
 * actually typed their password - is carried across renewals, and renewal
 * stops {@code max-session-days} after it: a stolen token can't be kept alive
 * forever by using it.
 */
@Service
public class JwtService {

    static final String AUTH_TIME = "auth_time";

    private final SecretKey key;
    private final long expiryMillis;
    private final Duration maxSession;
    private final Clock clock;

    @Autowired
    public JwtService(@Value("${forgeflow.jwt.secret}") String secret,
                      @Value("${forgeflow.jwt.expiry-minutes}") long expiryMinutes,
                      @Value("${forgeflow.jwt.max-session-days:30}") long maxSessionDays) {
        this(secret, expiryMinutes, maxSessionDays, Clock.systemUTC());
    }

    /** With a clock, so tests can mint tokens "in the past". */
    JwtService(String secret, long expiryMinutes, long maxSessionDays, Clock clock) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiryMillis = expiryMinutes * 60_000L;
        this.maxSession = Duration.ofDays(maxSessionDays);
        this.clock = clock;
    }

    /** A token for someone who has just proved who they are. */
    public String generateToken(Long userId, String email) {
        return mint(String.valueOf(userId), email, clock.instant());
    }

    /** The principal is the user ID, not the email: emails change, IDs do not. */
    public Long extractUserId(String token) {
        return Long.valueOf(parse(token).getSubject());
    }

    /**
     * A replacement for a valid token that is past half its life, or empty if
     * it's still fresh - or if the session it belongs to is older than the
     * maximum, in which case it runs out and the person signs in again.
     */
    public Optional<String> renewIfAged(String token) {
        Claims c = parse(token);
        Instant now = clock.instant();
        Instant issued = c.getIssuedAt().toInstant();
        if (Duration.between(issued, now).toMillis() < expiryMillis / 2) {
            return Optional.empty();
        }
        Number authTime = c.get(AUTH_TIME, Number.class);
        Instant authenticated = authTime == null ? issued : Instant.ofEpochSecond(authTime.longValue());
        if (authenticated.plus(maxSession).isBefore(now)) {
            return Optional.empty();
        }
        return Optional.of(mint(c.getSubject(), c.get("email", String.class), authenticated));
    }

    private String mint(String subject, String email, Instant authenticatedAt) {
        Date now = Date.from(clock.instant());
        return Jwts.builder()
                .subject(subject)
                .claim("email", email)
                .claim(AUTH_TIME, authenticatedAt.getEpochSecond())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiryMillis))
                .signWith(key)
                .compact();
    }

    private Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
