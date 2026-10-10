package com.forgeflow.intelligence.retrieval;

import java.util.List;

/**
 * The vector half of search: "which chunks of this project are nearest to
 * this query vector?". Chunk rows (text, lines, keyword index) always live in
 * Postgres; this decides where nearest-neighbour search runs.
 *
 *   pgvector  the embedding column on file_chunks, HNSW index (default)
 *   qdrant    a dedicated vector database - the spec's box. Postgres stays
 *             the record; Qdrant is an index of it that can be rebuilt
 *
 * Point ids are chunk ids, so either answer joins straight back to the rows.
 */
public interface VectorIndex {

    String name();

    /** After a file's chunks were rewritten: make the index hold exactly these vectors for it. */
    void replaceFile(Long projectId, String path, String model, List<Long> chunkIds, List<float[]> vectors);

    /** A file was deleted from the project. */
    void removeFile(Long projectId, String path);

    /** Chunk ids nearest to {@code query}, best first, among vectors from {@code model}. */
    List<Long> nearest(Long projectId, String model, float[] query, int n);
}
