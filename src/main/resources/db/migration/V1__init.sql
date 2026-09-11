CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE users (
                       id            BIGSERIAL PRIMARY KEY,
                       email         VARCHAR(255) NOT NULL UNIQUE,
                       password_hash VARCHAR(255) NOT NULL,
                       name          VARCHAR(120),
                       created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                       updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE projects (
                          id          BIGSERIAL PRIMARY KEY,
                          owner_id    BIGINT NOT NULL REFERENCES users(id),
                          name        VARCHAR(120) NOT NULL,
                          description TEXT,
                          created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                          updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                          deleted_at  TIMESTAMPTZ
);

CREATE INDEX idx_projects_owner ON projects(owner_id) WHERE deleted_at IS NULL;

CREATE TABLE project_files (
                               id          BIGSERIAL PRIMARY KEY,
                               project_id  BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
                               path        VARCHAR(512) NOT NULL,
                               content     TEXT NOT NULL,
                               size_bytes  INTEGER NOT NULL DEFAULT 0,
                               version     INTEGER NOT NULL DEFAULT 1,
                               created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                               updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                               CONSTRAINT uq_project_path UNIQUE (project_id, path)
);

CREATE INDEX idx_files_project ON project_files(project_id);

CREATE TABLE chat_sessions (
                               id         BIGSERIAL PRIMARY KEY,
                               project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
                               user_id    BIGINT NOT NULL,
                               title      VARCHAR(200),
                               created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                               updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_sessions_project ON chat_sessions(project_id);

CREATE TABLE chat_messages (
                               id         BIGSERIAL PRIMARY KEY,
                               session_id BIGINT NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
                               role       VARCHAR(16) NOT NULL,
                               content    TEXT NOT NULL,
                               created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_messages_session ON chat_messages(session_id, created_at);

CREATE TABLE generation_runs (
                                 id                BIGSERIAL PRIMARY KEY,
                                 project_id        BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
                                 session_id        BIGINT REFERENCES chat_sessions(id) ON DELETE SET NULL,
                                 user_id           BIGINT NOT NULL,
                                 status            VARCHAR(24) NOT NULL,
                                 stop_reason       VARCHAR(48),
                                 model             VARCHAR(80),
                                 prompt_tokens     INTEGER NOT NULL DEFAULT 0,
                                 completion_tokens INTEGER NOT NULL DEFAULT 0,
                                 cached_tokens     INTEGER NOT NULL DEFAULT 0,
                                 cost_usd          NUMERIC(10,6) NOT NULL DEFAULT 0,
                                 tool_call_count   INTEGER NOT NULL DEFAULT 0,
                                 repair_rounds     INTEGER NOT NULL DEFAULT 0,
                                 files_written     INTEGER NOT NULL DEFAULT 0,
                                 build_passed      BOOLEAN,
                                 duration_ms       BIGINT,
                                 error_message     TEXT,
                                 created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_runs_project ON generation_runs(project_id, created_at DESC);
CREATE INDEX idx_runs_user_day ON generation_runs(user_id, created_at);

CREATE TABLE tool_calls (
                            id             BIGSERIAL PRIMARY KEY,
                            run_id         BIGINT NOT NULL REFERENCES generation_runs(id) ON DELETE CASCADE,
                            sequence_order INTEGER NOT NULL,
                            tool_name      VARCHAR(64) NOT NULL,
                            arguments      TEXT,
                            result_summary TEXT,
                            succeeded      BOOLEAN NOT NULL DEFAULT TRUE,
                            error_message  TEXT,
                            duration_ms    BIGINT,
                            created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_toolcalls_run ON tool_calls(run_id, sequence_order);

CREATE TABLE file_chunks (
                             id          BIGSERIAL PRIMARY KEY,
                             project_id  BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
                             file_path   VARCHAR(512) NOT NULL,
                             chunk_index INTEGER NOT NULL,
                             content     TEXT NOT NULL,
                             summary     VARCHAR(500),
                             embedding   vector(768),
                             tsv         tsvector GENERATED ALWAYS AS (to_tsvector('english', content)) STORED,
                             created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_chunks_project ON file_chunks(project_id);
CREATE INDEX idx_chunks_tsv     ON file_chunks USING GIN (tsv);
CREATE INDEX idx_chunks_vec     ON file_chunks USING hnsw (embedding vector_cosine_ops);

CREATE TABLE previews (
                          id           BIGSERIAL PRIMARY KEY,
                          project_id   BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
                          container_id VARCHAR(128),
                          preview_url  VARCHAR(512),
                          status       VARCHAR(24) NOT NULL,
                          started_at   TIMESTAMPTZ,
                          expires_at   TIMESTAMPTZ,
                          created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_previews_project ON previews(project_id);
CREATE INDEX idx_previews_expiry  ON previews(expires_at) WHERE status = 'RUNNING';
