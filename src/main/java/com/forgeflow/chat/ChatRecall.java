package com.forgeflow.chat;

import com.forgeflow.intelligence.RecalledContext;
import com.forgeflow.intelligence.retrieval.CodeIndex;
import com.forgeflow.shared.llm.Embedder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Long chat memory: RAG over the conversation itself.
 *
 * The model always sees the last N messages (the window). In a long session
 * "make the footer like the one we did at the start" points at something that
 * scrolled out of it. So before a run, the messages OLDER than the window are
 * searched for the new request - the same hybrid search as code - and the few
 * that match go along as "earlier in this chat", each with its other half
 * (a question with its reply, a reply with its question), in order.
 *
 * Vectors are made lazily: a message is embedded the first time it is older
 * than the window and a search looks at it, then stored. Each message is
 * embedded at most once; short chats are never embedded at all. If embedding
 * fails, recall runs on keywords alone.
 */
@Component
public class ChatRecall {

    private static final Logger log = LoggerFactory.getLogger(ChatRecall.class);
    /** How far back a search reaches; a session longer than this recalls from its most recent part. */
    static final int MAX_CANDIDATES = 400;
    private static final int EMBED_BATCH = 50;
    private static final int EMBED_CHARS = 2_000;

    private final JdbcTemplate jdbc;
    private final Embedder embedder;
    private final CodeIndex index;
    private final int howMany;
    private final int maxChars;

    public ChatRecall(JdbcTemplate jdbc, Embedder embedder, CodeIndex index,
                      @Value("${forgeflow.chat.recall:3}") int howMany,
                      @Value("${forgeflow.chat.recall-max-chars:3000}") int maxChars) {
        this.jdbc = jdbc;
        this.embedder = embedder;
        this.index = index;
        this.howMany = howMany;
        this.maxChars = maxChars;
    }

    /**
     * @param windowStart the oldest message the model already sees; only
     *                    messages before it are searched. Null = there is no
     *                    window yet, so nothing is older.
     */
    public Optional<RecalledContext> recall(Long sessionId, Long windowStart, String request) {
        if (howMany <= 0 || windowStart == null || request == null || request.isBlank()) {
            return Optional.empty();
        }
        Integer older = jdbc.queryForObject(
                "SELECT count(*) FROM chat_messages WHERE session_id = ? AND id < ? AND role IN ('user', 'assistant')",
                Integer.class, sessionId, windowStart);
        if (older == null || older == 0) {
            return Optional.empty();
        }
        List<Long> byMeaning = List.of();
        try {
            embedMissing(sessionId, windowStart);
            float[] q = embedder.embed(List.of(request), Embedder.Kind.QUERY).get(0);
            byMeaning = jdbc.queryForList("""
                    SELECT id FROM (SELECT id, embedding FROM chat_messages
                                    WHERE session_id = ? AND id < ? AND role IN ('user', 'assistant')
                                      AND embedding IS NOT NULL AND embedding_model = ?
                                    ORDER BY id DESC LIMIT ?) recent
                    ORDER BY embedding <=> CAST(? AS vector), id DESC LIMIT 20""",
                    Long.class, sessionId, windowStart, embedder.modelName(), MAX_CANDIDATES, CodeIndex.literal(q));
        } catch (RuntimeException e) {
            log.warn("chat recall for session {}: embeddings unavailable, keywords only: {}", sessionId, e.getMessage());
        }
        String keywords = CodeIndex.keywordQuery(request);
        List<Long> byWords = keywords == null ? List.of() : jdbc.queryForList("""
                SELECT id FROM (SELECT id, content FROM chat_messages
                                WHERE session_id = ? AND id < ? AND role IN ('user', 'assistant')
                                ORDER BY id DESC LIMIT ?) recent, to_tsquery('english', ?) q
                WHERE to_tsvector('english', content) @@ q
                ORDER BY ts_rank_cd(to_tsvector('english', content), q) DESC, id DESC LIMIT 20""",
                Long.class, sessionId, windowStart, MAX_CANDIDATES, keywords);

        Map<Long, Double> scores = CodeIndex.fuse(byMeaning, byWords, index.fusion());
        List<Long> best = scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(howMany).map(Map.Entry::getKey).toList();
        if (best.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(render(sessionId, windowStart, best));
    }

    /** Each hit with its other half, oldest first, within the character budget. */
    private RecalledContext render(Long sessionId, Long windowStart, List<Long> best) {
        TreeMap<Long, String[]> picked = new TreeMap<>();     // id -> {role, content}
        for (Long id : best) {
            addMessage(picked, id);
            String role = picked.get(id)[0];
            Long partner = "user".equals(role)
                    ? first("SELECT min(id) FROM chat_messages WHERE session_id = ? AND id > ? AND id < ? AND role = 'assistant'",
                            sessionId, id, windowStart)
                    : first("SELECT max(id) FROM chat_messages WHERE session_id = ? AND id < ? AND role = 'user'",
                            sessionId, id);
            if (partner != null) {
                addMessage(picked, partner);
            }
        }
        StringBuilder sb = new StringBuilder("EARLIER IN THIS CHAT (older messages than the recent ones above, "
                + "retrieved because they look relevant to this request):\n");
        int n = 0;
        for (Map.Entry<Long, String[]> m : picked.entrySet()) {
            String content = m.getValue()[1];
            if (content.length() > 800) {
                content = content.substring(0, 800) + "...";
            }
            String line = "\n[" + ("user".equals(m.getValue()[0]) ? "The user asked" : "You replied") + "] " + content + "\n";
            if (n > 0 && sb.length() + line.length() > maxChars) {
                break;
            }
            sb.append(line);
            n++;
        }
        return new RecalledContext(sb.toString(), n);
    }

    private void addMessage(Map<Long, String[]> into, Long id) {
        if (into.containsKey(id)) {
            return;
        }
        jdbc.query("SELECT role, content FROM chat_messages WHERE id = ?",
                rs -> { into.put(id, new String[]{rs.getString("role"), rs.getString("content")}); }, id);
    }

    private Long first(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    /** Embed, once, the older messages that have no vector from the current model. */
    private void embedMissing(Long sessionId, Long windowStart) {
        record Pending(long id, String content) {
        }
        List<Pending> pending = jdbc.query("""
                SELECT id, content FROM (SELECT id, content, embedding, embedding_model FROM chat_messages
                                         WHERE session_id = ? AND id < ? AND role IN ('user', 'assistant')
                                         ORDER BY id DESC LIMIT ?) recent
                WHERE embedding IS NULL OR embedding_model IS DISTINCT FROM ?""",
                (rs, i) -> new Pending(rs.getLong("id"), rs.getString("content")),
                sessionId, windowStart, MAX_CANDIDATES, embedder.modelName());
        for (int from = 0; from < pending.size(); from += EMBED_BATCH) {
            List<Pending> batch = new ArrayList<>(pending.subList(from, Math.min(pending.size(), from + EMBED_BATCH)));
            List<float[]> vectors = embedder.embed(batch.stream()
                    .map(p -> p.content().length() > EMBED_CHARS ? p.content().substring(0, EMBED_CHARS) : p.content())
                    .toList(), Embedder.Kind.DOCUMENT);
            for (int i = 0; i < batch.size(); i++) {
                jdbc.update("UPDATE chat_messages SET embedding = CAST(? AS vector), embedding_model = ? WHERE id = ?",
                        CodeIndex.literal(vectors.get(i)), embedder.modelName(), batch.get(i).id());
            }
        }
    }
}
