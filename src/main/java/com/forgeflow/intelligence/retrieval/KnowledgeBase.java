package com.forgeflow.intelligence.retrieval;

import com.forgeflow.shared.llm.Embedder;
import com.forgeflow.shared.tracing.Tracer;
import com.forgeflow.workspace.Stack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The knowledge base: ForgeFlow's own reference notes on React, CSS, the DOM
 * and the rest, retrieved into the agent's work.
 *
 * Project code (CodeIndex) answers "what does THIS app do?". Notes answer
 * "how should this be done?" - useEffect's dependency rules, a card grid that
 * wraps without media queries, avoiding the dark-mode flash. A model knows a
 * lot of that from training, but not reliably, not current, and not in the
 * house style. Retrieval puts the right paragraph in front of it at the
 * moment it's needed.
 *
 * Same machinery as code search, different corpus:
 *   - notes live in resources/knowledge/*.md, one ## section per chunk
 *   - each note says which stacks it applies to, so a plain HTML project is
 *     never handed React advice
 *   - loaded once per process, re-embedded only when a note's hash or the
 *     embedding model changes; a failed embedding is retried on the next use
 *   - hybrid search, fused with the same weighted RRF as code
 */
@Service
public class KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBase.class);
    private static final Pattern STACKS = Pattern.compile("<!--\\s*stacks:\\s*([a-z, ]+)-->");
    private static final int CANDIDATES = 30;
    /** A long section is split at paragraph breaks so one chunk stays one idea. */
    static final int MAX_CHUNK_CHARS = 1_600;

    public record Chunk(String doc, String title, String section, int index, String stacks, String content) {
    }

    /** {@code source} is "note › section": how the chat and the tool name where advice came from. */
    public record Hit(String doc, String title, String section, double score, String content, String source) {
        static Hit of(String doc, String title, String section, double score, String content) {
            return new Hit(doc, title, section, score, content, doc + " › " + section);
        }
    }

    public record Notes(String text, List<Hit> hits) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Embedder embedder;
    private final CodeIndex index;
    private final Tracer tracer;
    private final String location;
    private final int attach;
    private final int attachMaxChars;
    private volatile boolean loaded;

    public KnowledgeBase(JdbcTemplate jdbc, TransactionTemplate tx, Embedder embedder, CodeIndex index, Tracer tracer,
                         @Value("${forgeflow.knowledge.location:classpath:knowledge/*.md}") String location,
                         @Value("${forgeflow.knowledge.attach:3}") int attach,
                         @Value("${forgeflow.knowledge.attach-max-chars:3500}") int attachMaxChars) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.embedder = embedder;
        this.index = index;
        this.tracer = tracer;
        this.location = location;
        this.attach = attach;
        this.attachMaxChars = attachMaxChars;
    }

    // ---------------------------------------------------------------- loading

    /** Bring the table in line with the notes on the classpath. Cheap after the first call. */
    public synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        try (Tracer.Span span = tracer.start("knowledge.load")) {
            Map<String, String> notes = readNotes();
            Map<String, String> stored = new HashMap<>();
            Map<String, Boolean> complete = new HashMap<>();
            jdbc.query("""
                    SELECT doc, max(doc_hash) AS h, bool_and(embedding IS NOT NULL AND embedding_model = ?) AS ok
                    FROM knowledge_chunks GROUP BY doc""", rs -> {
                stored.put(rs.getString("doc"), rs.getString("h"));
                complete.put(rs.getString("doc"), rs.getBoolean("ok"));
            }, embedder.modelName());
            for (String gone : stored.keySet()) {
                if (!notes.containsKey(gone)) {
                    jdbc.update("DELETE FROM knowledge_chunks WHERE doc = ?", gone);
                }
            }
            boolean allEmbedded = true;
            int reloaded = 0;
            for (Map.Entry<String, String> note : notes.entrySet()) {
                String hash = sha256(note.getValue());
                if (hash.equals(stored.get(note.getKey())) && Boolean.TRUE.equals(complete.get(note.getKey()))) {
                    continue;
                }
                allEmbedded &= load(note.getKey(), note.getValue(), hash);
                reloaded++;
            }
            span.tag("knowledge.notes", notes.size()).tag("knowledge.reloaded", reloaded);
            if (reloaded > 0) {
                log.info("knowledge base: {} note(s), {} (re)loaded", notes.size(), reloaded);
            }
            // A note stored without vectors (embedding failed) is still
            // keyword-searchable; try the vectors again next time.
            loaded = allEmbedded;
        }
    }

    private Map<String, String> readNotes() {
        Map<String, String> notes = new LinkedHashMap<>();
        try {
            for (Resource r : new PathMatchingResourcePatternResolver().getResources(location)) {
                String name = r.getFilename();
                if (name == null || !name.endsWith(".md")) {
                    continue;
                }
                try (InputStream in = r.getInputStream()) {
                    notes.put(name.substring(0, name.length() - 3), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("can't read the knowledge notes at " + location, e);
        }
        return notes;
    }

    /** @return true when every chunk got a vector */
    private boolean load(String doc, String text, String hash) {
        List<Chunk> chunks = chunk(doc, text);
        List<float[]> vectors = null;
        try {
            vectors = embedder.embed(chunks.stream().map(c -> c.title() + "\n" + c.section() + "\n" + c.content()).toList(),
                    Embedder.Kind.DOCUMENT);
        } catch (RuntimeException e) {
            log.warn("embedding note {} failed, storing keyword-only: {}", doc, e.getMessage());
        }
        List<float[]> vs = vectors;
        tx.executeWithoutResult(s -> {
            jdbc.update("DELETE FROM knowledge_chunks WHERE doc = ?", doc);
            for (int i = 0; i < chunks.size(); i++) {
                Chunk c = chunks.get(i);
                jdbc.update("""
                        INSERT INTO knowledge_chunks (doc, title, section, chunk_index, stacks, content, doc_hash,
                                                      embedding_model, embedding)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS vector))
                        ON CONFLICT (doc, chunk_index) DO NOTHING""",
                        c.doc(), c.title(), c.section(), c.index(), c.stacks(), c.content(), hash,
                        vs == null ? null : embedder.modelName(), vs == null ? null : CodeIndex.literal(vs.get(i)));
            }
        });
        return vs != null;
    }

    /**
     * One chunk per "## " section, the note's title and section kept as
     * fields (and embedded with the text). A section longer than
     * MAX_CHUNK_CHARS is split at blank lines.
     */
    static List<Chunk> chunk(String doc, String text) {
        Matcher sm = STACKS.matcher(text);
        String stacks = sm.find() ? sm.group(1).replace(" ", "") : "static,react";
        String title = doc;
        String section = null;
        StringBuilder body = new StringBuilder();
        List<String[]> sections = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("<!--")) {
                continue;
            }
            if (line.startsWith("# ")) {
                title = line.substring(2).strip();
            } else if (line.startsWith("## ")) {
                if (section != null) {
                    sections.add(new String[]{section, body.toString().strip()});
                }
                section = line.substring(3).strip();
                body.setLength(0);
            } else if (section != null) {
                body.append(line).append('\n');
            }
        }
        if (section != null) {
            sections.add(new String[]{section, body.toString().strip()});
        }
        List<Chunk> out = new ArrayList<>();
        for (String[] s : sections) {
            for (String piece : split(s[1])) {
                out.add(new Chunk(doc, title, s[0], out.size(), stacks, piece));
            }
        }
        return out;
    }

    private static List<String> split(String body) {
        if (body.length() <= MAX_CHUNK_CHARS) {
            return List.of(body);
        }
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String para : body.split("\n\\s*\n")) {
            if (cur.length() > 0 && cur.length() + para.length() > MAX_CHUNK_CHARS) {
                out.add(cur.toString().strip());
                cur.setLength(0);
            }
            cur.append(para).append("\n\n");
        }
        if (cur.length() > 0) {
            out.add(cur.toString().strip());
        }
        return out;
    }

    // ---------------------------------------------------------------- search

    /** @param stack null = every note; otherwise only notes that apply to it */
    public List<Hit> search(String query, int k, Stack stack) {
        return search(query, k, stack, CodeIndex.Mode.HYBRID);
    }

    /** @param mode one half alone (VECTOR, KEYWORD) is for measuring what each contributes */
    public List<Hit> search(String query, int k, Stack stack, CodeIndex.Mode mode) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        ensureLoaded();
        String stackFilter = stack == null ? "%" : "%" + stack.name().toLowerCase(Locale.ROOT) + "%";
        List<Long> byMeaning = List.of();
        if (mode != CodeIndex.Mode.KEYWORD) try {
            float[] v = embedder.embed(List.of(query), Embedder.Kind.QUERY).get(0);
            byMeaning = jdbc.queryForList("""
                    SELECT id FROM knowledge_chunks
                    WHERE embedding IS NOT NULL AND embedding_model = ? AND stacks LIKE ?
                    ORDER BY embedding <=> CAST(? AS vector), id LIMIT ?""",
                    Long.class, embedder.modelName(), stackFilter, CodeIndex.literal(v), CANDIDATES);
        } catch (RuntimeException e) {
            log.warn("knowledge query embedding failed, keywords only: {}", e.getMessage());
        }
        String keywords = mode == CodeIndex.Mode.VECTOR ? null : CodeIndex.keywordQuery(query);
        List<Long> byWords = keywords == null ? List.of() : jdbc.queryForList("""
                SELECT id FROM knowledge_chunks, to_tsquery('english', ?) q
                WHERE tsv @@ q AND stacks LIKE ?
                ORDER BY ts_rank_cd(tsv, q) DESC, id LIMIT ?""", Long.class, keywords, stackFilter, CANDIDATES);
        Map<Long, Double> scores = CodeIndex.fuse(byMeaning, byWords, index.fusion());
        if (scores.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT id, doc, title, section, content FROM knowledge_chunks WHERE id = ANY(?)",
                        (rs, i) -> Hit.of(rs.getString("doc"), rs.getString("title"), rs.getString("section"),
                                scores.get(rs.getLong("id")), rs.getString("content")),
                        (Object) scores.keySet().toArray(Long[]::new)).stream()
                .sorted(Comparator.comparingDouble((Hit h) -> -h.score()).thenComparing(Hit::doc))
                .limit(Math.max(1, Math.min(k, 10)))
                .toList();
    }

    /**
     * What a run starts with: the few sections most relevant to the request,
     * for this project's stack, within a character budget. Empty when
     * attaching is switched off (forgeflow.knowledge.attach: 0).
     */
    public Optional<Notes> notesFor(String prompt, Stack stack) {
        return notesFor(prompt, stack, attach);
    }

    public Optional<Notes> notesFor(String prompt, Stack stack, int howMany) {
        if (howMany <= 0) {
            return Optional.empty();
        }
        List<Hit> hits = search(prompt, howMany, stack);
        if (hits.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder("REFERENCE NOTES (ForgeFlow's own notes on doing this well - "
                + "follow them where they apply to this request; ignore the rest):\n");
        List<Hit> used = new ArrayList<>();
        for (Hit h : hits) {
            String block = "\n--- " + h.title() + " › " + h.section() + " ---\n" + h.content() + "\n";
            if (!used.isEmpty() && sb.length() + block.length() > attachMaxChars) {
                break;
            }
            sb.append(block);
            used.add(h);
        }
        return Optional.of(new Notes(sb.toString(), used));
    }

    /** Every note: name, title, which stacks, how many sections. For the /knowledge listing. */
    public List<Map<String, Object>> list() {
        ensureLoaded();
        return jdbc.queryForList("""
                SELECT doc, max(title) AS title, max(stacks) AS stacks, count(*) AS sections
                FROM knowledge_chunks GROUP BY doc ORDER BY doc""");
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
