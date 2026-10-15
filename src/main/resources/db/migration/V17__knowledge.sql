-- The knowledge base: ForgeFlow's own reference notes (resources/knowledge/*.md),
-- chunked by section and embedded like code, shared by every project.
--
-- A separate table, not file_chunks with a null project: these rows belong to
-- no project, are never deleted with one, and are filtered by stack instead.
-- Small (tens of rows), so no vector index: a scan is instant.
CREATE TABLE knowledge_chunks (
    id              BIGSERIAL PRIMARY KEY,
    doc             VARCHAR(128) NOT NULL,          -- file name without .md
    title           VARCHAR(200) NOT NULL,          -- the note's # heading
    section         VARCHAR(200) NOT NULL,          -- the ## heading this chunk is
    chunk_index     INTEGER      NOT NULL,
    stacks          VARCHAR(64)  NOT NULL,          -- "static,react": which projects it applies to
    content         TEXT         NOT NULL,
    doc_hash        VARCHAR(64)  NOT NULL,          -- SHA-256 of the whole note: re-embed only on change
    embedding_model VARCHAR(64),
    embedding       vector(768),
    tsv             tsvector GENERATED ALWAYS AS
                        (to_tsvector('english', title || ' ' || section || ' ' || content)) STORED,
    UNIQUE (doc, chunk_index)
);

CREATE INDEX idx_knowledge_tsv ON knowledge_chunks USING GIN (tsv);
