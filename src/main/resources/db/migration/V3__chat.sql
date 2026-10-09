-- V3: chat - the spec's CHAT_SESSION and CHAT_MESSAGE.
--
-- Both tables existed since V1 and nothing ever wrote to them; every generate
-- started with no memory of the last one. This brings them up to the spec and
-- adds three columns the spec does not draw, each for a reason:
--
--   author_id  a session can be shared by several editors, so a user message
--              records WHO said it, not just that a user did.
--   status     an assistant reply that failed must stay in the history (it
--              keeps the user/model turns alternating, which Gemini requires,
--              and it is what "retry if failed" retries). Stored here so the
--              chat module never has to join into intelligence's tables.
--   run_id     links a reply to the generation run that produced it, for
--              tokens, duration and repair rounds.

ALTER TABLE chat_sessions
    ADD COLUMN deleted_at TIMESTAMPTZ;

-- V1 left user_id without a foreign key.
ALTER TABLE chat_sessions
    ADD CONSTRAINT fk_chat_sessions_user FOREIGN KEY (user_id) REFERENCES users (id);

ALTER TABLE chat_messages
    ADD COLUMN author_id    BIGINT       REFERENCES users (id),
    ADD COLUMN tool_calls   JSONB,
    ADD COLUMN tool_call_id VARCHAR(128),
    ADD COLUMN tokens_used  INTEGER      NOT NULL DEFAULT 0,
    ADD COLUMN status       VARCHAR(24),
    ADD COLUMN run_id       BIGINT       REFERENCES generation_runs (id) ON DELETE SET NULL;

ALTER TABLE chat_messages
    ADD CONSTRAINT chk_chat_messages_role CHECK (role IN ('user', 'assistant', 'system', 'tool'));

-- The sessions sidebar: one project's live sessions, most recent first.
CREATE INDEX idx_chat_sessions_project
    ON chat_sessions (project_id, updated_at DESC) WHERE deleted_at IS NULL;

-- Loading a conversation, and taking its last N messages as model memory.
CREATE INDEX idx_chat_messages_session ON chat_messages (session_id, id);
