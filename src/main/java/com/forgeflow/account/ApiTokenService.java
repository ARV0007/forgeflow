package com.forgeflow.account;

import com.forgeflow.account.dto.ApiTokenResponse;
import com.forgeflow.shared.ResourceNotFoundException;
import com.forgeflow.shared.security.AccessTokens;
import com.forgeflow.shared.storage.SigV4;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Personal access tokens - so an AI agent (over MCP), a script or CI can act
 * as you without your password.
 *
 *   ff_pat_ + 32 characters from a 62-letter alphabet (~190 bits)
 *
 * Only the SHA-256 is stored, and the token is shown once. A fast hash is the
 * right choice here (unlike passwords, which get BCrypt): a token is random,
 * not chosen by a human, so there is no dictionary to try. The prefix makes a
 * leaked token recognisable to secret scanners and to you.
 */
@Service
public class ApiTokenService implements AccessTokens {

    static final int MAX_PER_USER = 10;
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;

    public ApiTokenService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public ApiTokenResponse create(Long userId, String name) {
        Integer live = jdbc.queryForObject("SELECT count(*) FROM api_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, userId);
        if (live >= MAX_PER_USER) {
            throw new IllegalStateException("You have " + MAX_PER_USER + " tokens already - revoke one first");
        }
        StringBuilder sb = new StringBuilder(PREFIX);
        for (int i = 0; i < 32; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        String token = sb.toString();
        String prefix = token.substring(0, PREFIX.length() + 4);
        Instant now = Instant.now();
        Long id = jdbc.queryForObject("""
                INSERT INTO api_tokens (user_id, name, token_hash, prefix, created_at) VALUES (?, ?, ?, ?, ?) RETURNING id""",
                Long.class, userId, name.strip(), SigV4.sha256Hex(token), prefix, Timestamp.from(now));
        return new ApiTokenResponse(id, name.strip(), prefix, now, null, token);
    }

    @Transactional(readOnly = true)
    public List<ApiTokenResponse> list(Long userId) {
        return jdbc.query("""
                SELECT id, name, prefix, created_at, last_used_at FROM api_tokens
                WHERE user_id = ? AND revoked_at IS NULL ORDER BY id DESC""",
                (rs, i) -> new ApiTokenResponse(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant(),
                        rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant(), null),
                userId);
    }

    @Transactional
    public void revoke(Long userId, Long tokenId) {
        int n = jdbc.update("UPDATE api_tokens SET revoked_at = now() WHERE id = ? AND user_id = ? AND revoked_at IS NULL",
                tokenId, userId);
        if (n == 0) {
            throw new ResourceNotFoundException("No such token");
        }
    }

    /**
     * Looked up by hash - an index lookup, and comparing hashes of random
     * tokens leaks nothing useful through timing. Last-used is written at
     * most once a minute per token, not on every request.
     */
    @Override
    public Optional<Long> userFor(String token) {
        if (token == null || !token.startsWith(PREFIX) || token.length() > 64) {
            return Optional.empty();
        }
        String hash = SigV4.sha256Hex(token);
        Optional<long[]> row = jdbc.query("SELECT id, user_id FROM api_tokens WHERE token_hash = ? AND revoked_at IS NULL",
                rs -> rs.next() ? Optional.of(new long[]{rs.getLong(1), rs.getLong(2)}) : Optional.empty(), hash);
        row.ifPresent(r -> jdbc.update("""
                UPDATE api_tokens SET last_used_at = now()
                WHERE id = ? AND (last_used_at IS NULL OR last_used_at < now() - interval '1 minute')""", r[0]));
        return row.map(r -> r[1]);
    }

    static void requireSession(Object details) {
        if (VIA.equals(details)) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "Manage tokens from a signed-in session - a token can't create or revoke tokens");
        }
    }

    private static final String VIA = com.forgeflow.shared.security.JwtAuthFilter.VIA_TOKEN;
}
