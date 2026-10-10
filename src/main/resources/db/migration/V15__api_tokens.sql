-- Personal access tokens: for scripts, CI and AI agents (MCP) to act as a
-- user without their password. Only a SHA-256 of each token is stored - the
-- token is shown once, at creation - so a database leak leaks no usable
-- tokens. (A fast hash is right here, unlike passwords: the token is 190 bits
-- of randomness, so there is nothing to brute-force.)
CREATE TABLE api_tokens (
    id            BIGSERIAL    PRIMARY KEY,
    user_id       BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name          VARCHAR(80)  NOT NULL,
    token_hash    CHAR(64)     NOT NULL UNIQUE,
    prefix        VARCHAR(16)  NOT NULL,          -- "ff_pat_AbCd", to recognise it in a list
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at  TIMESTAMPTZ,
    revoked_at    TIMESTAMPTZ
);
CREATE INDEX idx_api_tokens_user ON api_tokens(user_id);
