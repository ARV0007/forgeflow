package com.forgeflow.intelligence.retrieval;

import com.forgeflow.shared.llm.Embedder;
import com.forgeflow.shared.tracing.Tracer;
import com.forgeflow.workspace.ProjectFileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spec: RAG. A searchable index of each project's own code.
 *
 *   index   chunk each file (CodeChunker), embed each chunk, store in
 *           file_chunks next to its full-text tsvector
 *   search  hybrid: vector similarity AND keyword match, fused by rank
 *
 * Why hybrid? Embeddings are good at meaning ("where is the dark mode
 * toggle?") and bad at exact identifiers - a query for "renderTodos" may well
 * rank a chunk about rendering above the one that defines renderTodos.
 * Keyword search is the reverse. Reciprocal Rank Fusion merges the two lists
 * by position, so neither score scale has to be comparable with the other.
 */
@Service
public class CodeIndex {

    private static final Logger log = LoggerFactory.getLogger(CodeIndex.class);
    private static final Pattern QUERY_WORD = Pattern.compile("[A-Za-z0-9_]{2,}");
    /** RRF's k. 60 is the value from the original paper and the usual default; it damps the very top ranks. */
    private static final int RRF_K = 60;
    private static final int CANDIDATES = 30;

    public record SearchHit(String path, int startLine, int endLine, double score, String content) {
    }

    public record IndexReport(int filesIndexed, int chunksWritten, int filesRemoved, int filesUnchanged) {
    }

    private final ProjectFileService files;
    private final Embedder embedder;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final int wholeProjectThreshold;
    private final int topK;
    private final Map<Long, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Tracer tracer;

    public CodeIndex(ProjectFileService files, Embedder embedder, JdbcTemplate jdbc, TransactionTemplate tx,
                     Tracer tracer,
                     @Value("${forgeflow.retrieval.whole-project-threshold:15}") int wholeProjectThreshold,
                     @Value("${forgeflow.retrieval.top-k:8}") int topK) {
        this.tracer = tracer;
        this.files = files;
        this.embedder = embedder;
        this.jdbc = jdbc;
        this.tx = tx;
        this.wholeProjectThreshold = wholeProjectThreshold;
        this.topK = topK;
    }

    // ------------------------------------------------------------- indexing

    /**
     * Bring the index up to date with the project's files, re-embedding only
     * what changed. Cheap when nothing did: one query and a hash per file.
     *
     * Embedding happens OUTSIDE any transaction (it's a network call, and a
     * held connection per slow call would drain the pool); each file's chunks
     * are then swapped in one short transaction.
     */
    public IndexReport ensureIndexed(Long projectId) {
        ReentrantLock lock = locks.computeIfAbsent(projectId, k -> new ReentrantLock());
        lock.lock();
        try (Tracer.Span span = tracer.start("rag.index")) {
            IndexReport r = reindex(projectId);
            span.tag("project.id", projectId).tag("rag.files_indexed", r.filesIndexed())
                .tag("rag.chunks_written", r.chunksWritten());
            return r;
        } finally {
            lock.unlock();
        }
    }

    private IndexReport reindex(Long projectId) {
        Map<String, String> snapshot = files.snapshot(projectId);

        record Indexed(String hash, String model, int missingVectors) {
        }
        Map<String, Indexed> existing = new HashMap<>();
        jdbc.query("""
                SELECT file_path, max(file_hash) AS h, max(embedding_model) AS m,
                       count(*) FILTER (WHERE embedding IS NULL) AS missing
                FROM file_chunks WHERE project_id = ? GROUP BY file_path
                """, rs -> {
            existing.put(rs.getString("file_path"),
                    new Indexed(rs.getString("h"), rs.getString("m"), rs.getInt("missing")));
        }, projectId);

        int removed = 0;
        for (String gone : existing.keySet()) {
            if (!snapshot.containsKey(gone)) {
                jdbc.update("DELETE FROM file_chunks WHERE project_id = ? AND file_path = ?", projectId, gone);
                removed++;
            }
        }

        int unchanged = 0;
        int indexed = 0;
        int chunksWritten = 0;
        for (Map.Entry<String, String> file : snapshot.entrySet()) {
            String hash = sha256(file.getValue());
            Indexed have = existing.get(file.getKey());
            if (have != null && hash.equals(have.hash()) && embedder.modelName().equals(have.model())
                    && have.missingVectors() == 0) {
                unchanged++;
                continue;
            }
            chunksWritten += indexFile(projectId, file.getKey(), file.getValue(), hash);
            indexed++;
        }
        if (indexed + removed > 0) {
            log.info("indexed project {}: {} file(s) re-indexed ({} chunks), {} removed, {} unchanged",
                    projectId, indexed, chunksWritten, removed, unchanged);
        }
        return new IndexReport(indexed, chunksWritten, removed, unchanged);
    }

    private int indexFile(Long projectId, String path, String content, String hash) {
        List<CodeChunker.Chunk> chunks = CodeChunker.chunk(content);

        // The path goes into what is embedded (not what is stored): "styles.css"
        // is itself a strong hint about what a chunk is for.
        List<String> texts = chunks.stream().map(c -> "File: " + path + "\n" + c.text()).toList();
        List<float[]> vectors = null;
        String model = null;
        try {
            if (!texts.isEmpty()) {
                vectors = embedder.embed(texts, Embedder.Kind.DOCUMENT);
                model = embedder.modelName();
            }
        } catch (RuntimeException e) {
            // Keyword search still works on this file. The missing vectors are
            // noticed (missingVectors > 0) and retried on the next pass.
            log.warn("embedding {} in project {} failed, storing keyword-only: {}", path, projectId, e.getMessage());
        }
        List<float[]> vs = vectors;
        String m = model;
        try {
            tx.executeWithoutResult(status -> {
                jdbc.update("DELETE FROM file_chunks WHERE project_id = ? AND file_path = ?", projectId, path);
                for (int i = 0; i < chunks.size(); i++) {
                    CodeChunker.Chunk c = chunks.get(i);
                    jdbc.update("""
                            INSERT INTO file_chunks
                              (project_id, file_path, chunk_index, content, start_line, end_line,
                               file_hash, embedding_model, embedding)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS vector))
                            """, projectId, path, c.index(), c.text(), c.startLine(), c.endLine(),
                            hash, m, vs == null ? null : literal(vs.get(i)));
                }
            });
        } catch (DuplicateKeyException e) {
            // Another indexer (another instance) wrote this file at the same
            // moment. Its chunks are as good as ours.
            log.debug("project {} file {} indexed concurrently elsewhere", projectId, path);
        }
        return chunks.size();
    }

    // -------------------------------------------------------------- search

    /** Fresh results: the index is brought up to date first. */
    public List<SearchHit> search(Long projectId, String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        ensureIndexed(projectId);
        int k = Math.max(1, Math.min(limit, 20));

        String keywords = keywordQuery(query);
        String vector = null;
        try {
            vector = literal(embedder.embed(List.of(query), Embedder.Kind.QUERY).get(0));
        } catch (RuntimeException e) {
            log.warn("query embedding failed, searching by keyword only: {}", e.getMessage());
        }
        if (vector == null && keywords == null) {
            return List.of();
        }

        // Each half produces a ranked candidate list; RRF scores a chunk by
        // 1/(60 + rank) in each list it appears in, and sums. A chunk ranked
        // well by BOTH beats one ranked first by only one. A half with no
        // input (embedding failed, or no usable words) is simply empty.
        List<Object> args = new ArrayList<>();
        String vectorCte;
        if (vector != null) {
            vectorCte = """
                    SELECT id, row_number() OVER (ORDER BY embedding <=> CAST(? AS vector)) AS r
                    FROM file_chunks
                    WHERE project_id = ? AND embedding IS NOT NULL AND embedding_model = ?
                    ORDER BY embedding <=> CAST(? AS vector)
                    LIMIT ?""";
            args.addAll(List.of(vector, projectId, embedder.modelName(), vector, CANDIDATES));
        } else {
            vectorCte = "SELECT NULL::bigint AS id, NULL::bigint AS r WHERE false";
        }
        String keywordCte;
        if (keywords != null) {
            keywordCte = """
                    SELECT id, row_number() OVER (ORDER BY ts_rank_cd(tsv, q) DESC) AS r
                    FROM file_chunks, to_tsquery('english', ?) q
                    WHERE project_id = ? AND tsv @@ q
                    ORDER BY ts_rank_cd(tsv, q) DESC
                    LIMIT ?""";
            args.addAll(List.of(keywords, projectId, CANDIDATES));
        } else {
            keywordCte = "SELECT NULL::bigint AS id, NULL::bigint AS r WHERE false";
        }
        args.addAll(List.of(RRF_K, RRF_K, projectId, k));

        String sql = "WITH v AS (" + vectorCte + "), kw AS (" + keywordCte + ")\n" + """
                SELECT c.file_path, c.start_line, c.end_line, c.content,
                       coalesce(1.0 / (? + v.r), 0) + coalesce(1.0 / (? + kw.r), 0) AS score
                FROM file_chunks c
                LEFT JOIN v ON v.id = c.id
                LEFT JOIN kw ON kw.id = c.id
                WHERE c.project_id = ? AND (v.id IS NOT NULL OR kw.id IS NOT NULL)
                ORDER BY score DESC, c.file_path, c.chunk_index
                LIMIT ?""";
        return jdbc.query(sql, (rs, i) -> new SearchHit(
                        rs.getString("file_path"), rs.getInt("start_line"), rs.getInt("end_line"),
                        rs.getDouble("score"), rs.getString("content")),
                args.toArray());
    }

    /**
     * What a run starts with. Small projects: nothing - the agent can list and
     * read every file, and should. Past the threshold, sending every file
     * stops being affordable, so the most relevant chunks are attached to the
     * request instead. That is the retrieval half of RAG.
     */
    public Optional<String> contextFor(Long projectId, String prompt) {
        if (files.list(projectId).size() <= wholeProjectThreshold) {
            return Optional.empty();
        }
        List<SearchHit> hits = search(projectId, prompt, topK);
        if (hits.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder("RELEVANT CODE (retrieved for this request - excerpts, not whole files; "
                + "read_file before editing):\n");
        for (SearchHit h : hits) {
            sb.append("\n--- ").append(h.path()).append(" lines ").append(h.startLine())
              .append('-').append(h.endLine()).append(" ---\n").append(h.content()).append('\n');
        }
        return Optional.of(sb.toString());
    }

    // ------------------------------------------------------------- helpers

    /**
     * Any of the query's words, not all of them (OR, not AND). Built from a
     * strict whitelist of characters, so nothing the user typed can be read as
     * tsquery syntax.
     */
    static String keywordQuery(String query) {
        Set<String> words = new LinkedHashSet<>();
        Matcher m = QUERY_WORD.matcher(query);
        while (m.find() && words.size() < 16) {
            words.add(m.group().toLowerCase(Locale.ROOT));
        }
        return words.isEmpty() ? null : String.join(" | ", words);
    }

    /** pgvector's text form: [0.1,0.2,...] */
    static String literal(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** For tests and the indexer's log. */
    int chunkCount(Long projectId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM file_chunks WHERE project_id = ?", Integer.class, projectId);
        return n == null ? 0 : n;
    }
}
