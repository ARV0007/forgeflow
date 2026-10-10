package com.forgeflow.intelligence.retrieval;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** Vectors in the file_chunks.embedding column; nearest neighbours by pgvector's cosine distance. */
public class PgVectorIndex implements VectorIndex {

    private final JdbcTemplate jdbc;

    public PgVectorIndex(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "pgvector";
    }

    @Override
    public void replaceFile(Long projectId, String path, String model, List<Long> chunkIds, List<float[]> vectors) {
        // Nothing to do: the vectors were written with the chunk rows.
    }

    @Override
    public void removeFile(Long projectId, String path) {
        // Nothing to do: deleting the rows deleted the vectors.
    }

    @Override
    public List<Long> nearest(Long projectId, String model, float[] query, int n) {
        return jdbc.queryForList("""
                SELECT id FROM file_chunks
                WHERE project_id = ? AND embedding IS NOT NULL AND embedding_model = ?
                ORDER BY embedding <=> CAST(? AS vector), id
                LIMIT ?""", Long.class, projectId, model, CodeIndex.literal(query), n);
    }
}
