-- "AI checks its own app": after a reply changes the files, the workbench
-- screenshots the live preview and a vision model reviews it against what
-- the user asked for. One row per review, attached to the reply it looked at,
-- so reloading the conversation shows the verdict again. The screenshot
-- itself is not kept: it is a throwaway input, and the files are the record.
CREATE TABLE visual_reviews (
    id           BIGSERIAL    PRIMARY KEY,
    message_id   BIGINT       NOT NULL REFERENCES chat_messages(id) ON DELETE CASCADE,
    user_id      BIGINT       NOT NULL,
    score        SMALLINT     NOT NULL CHECK (score BETWEEN 1 AND 10),
    verdict      VARCHAR(20)  NOT NULL CHECK (verdict IN ('LOOKS_RIGHT', 'NEEDS_FIXES')),
    summary      TEXT         NOT NULL,
    issues       TEXT         NOT NULL,          -- JSON array of {severity, text}
    tokens_used  INTEGER      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_visual_reviews_message ON visual_reviews(message_id, id DESC);
