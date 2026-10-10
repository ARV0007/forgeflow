-- Version history. A checkpoint is the whole project at one moment: every
-- path and the fingerprint (SHA-256) of its content. Content itself is stored
-- once per project per fingerprint in file_blobs, so a hundred checkpoints of
-- a project where one file changes each time cost a hundred small rows plus
-- the changed contents - not a hundred copies of everything.
--
-- In s3 mode the blob row only points at the object the file write already
-- created (same content-addressed key), so history adds no bytes to the bucket.

CREATE TABLE file_blobs (
    project_id  BIGINT       NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    sha256      CHAR(64)     NOT NULL,
    content     TEXT,
    object_key  VARCHAR(600),
    size_bytes  INTEGER      NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, sha256),
    CONSTRAINT chk_file_blobs_somewhere CHECK (content IS NOT NULL OR object_key IS NOT NULL)
);

CREATE TABLE checkpoints (
    id          BIGSERIAL    PRIMARY KEY,
    project_id  BIGINT       NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    kind        VARCHAR(16)  NOT NULL CHECK (kind IN ('BASELINE', 'RUN', 'RESTORE')),
    label       VARCHAR(300) NOT NULL,
    run_id      BIGINT,                         -- the generation run that produced it, if any
    created_by  BIGINT       REFERENCES users(id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    file_count  INTEGER      NOT NULL,
    -- SHA-256 over the sorted "path sha" lines: equal trees, equal hash. Lets
    -- a checkpoint that changes nothing be skipped, and restores be compared.
    tree_hash   CHAR(64)     NOT NULL
);
CREATE INDEX idx_checkpoints_project ON checkpoints(project_id, id DESC);

CREATE TABLE checkpoint_files (
    checkpoint_id  BIGINT        NOT NULL REFERENCES checkpoints(id) ON DELETE CASCADE,
    path           VARCHAR(512)  NOT NULL,
    sha256         CHAR(64)      NOT NULL,
    size_bytes     INTEGER       NOT NULL,
    PRIMARY KEY (checkpoint_id, path)
);
