-- Images sent with a chat message ("screenshot to app"). Kept with the
-- message so a reload shows them and "retry" sends them again. Bytes in
-- Postgres: at most 3 images of 4 MB per message, read rarely - a bucket
-- would be the next step at scale, behind the same ObjectStore seam.
CREATE TABLE chat_attachments (
    id          BIGSERIAL    PRIMARY KEY,
    message_id  BIGINT       NOT NULL REFERENCES chat_messages(id) ON DELETE CASCADE,
    mime_type   VARCHAR(40)  NOT NULL,
    data        BYTEA        NOT NULL,
    size_bytes  INTEGER      NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_chat_attachments_message ON chat_attachments(message_id);
