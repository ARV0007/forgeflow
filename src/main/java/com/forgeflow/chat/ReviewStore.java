package com.forgeflow.chat;

import com.forgeflow.chat.dto.VisualReviewResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Visual reviews, kept with the reply they looked at. Plain JDBC, like the attachments. */
@Component
class ReviewStore {

    private static final TypeReference<List<VisualReviewResponse.Issue>> ISSUES = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    private final RowMapper<VisualReviewResponse> row = (rs, i) -> new VisualReviewResponse(
            rs.getLong("id"), rs.getLong("message_id"), rs.getInt("score"), rs.getString("verdict"),
            rs.getString("summary"), json.readValue(rs.getString("issues"), ISSUES), rs.getInt("tokens_used"),
            rs.getTimestamp("created_at").toInstant());

    ReviewStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    VisualReviewResponse save(Long messageId, Long userId, int score, String verdict, String summary,
                              List<VisualReviewResponse.Issue> issues, int tokens) {
        return jdbc.queryForObject("""
                INSERT INTO visual_reviews (message_id, user_id, score, verdict, summary, issues, tokens_used)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING id, message_id, score, verdict, summary, issues, tokens_used, created_at""",
                row, messageId, userId, score, verdict, summary, json.writeValueAsString(issues), tokens);
    }

    /** The newest review of each message, one query for a whole history. */
    Map<Long, VisualReviewResponse> latestFor(Collection<Long> messageIds) {
        Map<Long, VisualReviewResponse> out = new HashMap<>();
        if (messageIds.isEmpty()) {
            return out;
        }
        jdbc.query("""
                SELECT DISTINCT ON (message_id) id, message_id, score, verdict, summary, issues, tokens_used, created_at
                FROM visual_reviews WHERE message_id = ANY(?) ORDER BY message_id, id DESC""",
                rs -> {
                    VisualReviewResponse r = row.mapRow(rs, 0);
                    out.put(r.messageId(), r);
                }, (Object) messageIds.toArray(Long[]::new));
        return out;
    }
}
