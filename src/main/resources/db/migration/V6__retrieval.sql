-- V6: retrieval - finishing the file_chunks table V1 sketched.
--
-- The spec draws Qdrant for RAG. The vectors live in Postgres instead
-- (pgvector, already installed by V1): one database means a chunk and the
-- file it came from can never disagree about whether the other exists, and
-- the keyword half of hybrid search (the tsv column) sits in the same row.

ALTER TABLE file_chunks
    ADD COLUMN start_line      INTEGER,
    ADD COLUMN end_line        INTEGER,
    -- SHA-256 of the WHOLE FILE the chunk came from. Re-indexing compares
    -- this per file and skips everything unchanged - no embedding calls for
    -- files nobody touched.
    ADD COLUMN file_hash       VARCHAR(64),
    -- Which embedder produced the vector. Vectors from different models live
    -- in different spaces; comparing across them is meaningless, so a model
    -- change means a re-embed.
    ADD COLUMN embedding_model VARCHAR(64);

-- Two indexers racing on one file: the second insert fails instead of
-- leaving duplicate chunks behind.
CREATE UNIQUE INDEX uq_chunks_project_path_index ON file_chunks (project_id, file_path, chunk_index);
