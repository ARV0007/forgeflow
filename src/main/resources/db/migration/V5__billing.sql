-- V5: billing - the spec's PLAN, SUBSCRIPTION and USAGE_LOG.
--
-- A plan is a set of limits. A subscription says which plan a user is on.
-- The usage log is the record that limits are checked against. Nobody without
-- a live subscription row is on FREE - that is the default, not a row - so a
-- new user costs nothing to create and never needs a billing call to sign up.

CREATE TABLE plans (
    id                 BIGSERIAL PRIMARY KEY,
    code               VARCHAR(32)  NOT NULL UNIQUE,
    name               VARCHAR(64)  NOT NULL,
    price_cents        INTEGER      NOT NULL DEFAULT 0,
    currency           VARCHAR(3)   NOT NULL DEFAULT 'usd',
    -- Which Stripe Price to charge. Kept in config, not here, because test and
    -- live Stripe accounts have different ids for the same plan.
    max_projects       INTEGER      NOT NULL,
    max_tokens_per_day INTEGER      NOT NULL,
    max_previews       INTEGER      NOT NULL,
    unlimited_ai       BOOLEAN      NOT NULL DEFAULT FALSE,
    features           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    -- false = exists, can be held, cannot be bought (the MCP service account's plan)
    purchasable        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO plans (code, name, price_cents, max_projects, max_tokens_per_day, max_previews, unlimited_ai, features, purchasable)
VALUES
    ('FREE', 'Free', 0, 3, 200000, 1, FALSE,
     '{"highlights": ["3 projects", "200k AI tokens a day", "1 live preview", "Zip download"]}', TRUE),
    ('PRO', 'Pro', 2000, 50, 2000000, 5, FALSE,
     '{"highlights": ["50 projects", "2M AI tokens a day", "5 live previews", "Priority generation"]}', TRUE),
    ('INTERNAL', 'Internal', 0, 1000, 1000000, 10, FALSE,
     '{"highlights": ["Service accounts only"]}', FALSE);

CREATE TABLE subscriptions (
    id                       BIGSERIAL PRIMARY KEY,
    user_id                  BIGINT       NOT NULL REFERENCES users (id),
    plan_id                  BIGINT       NOT NULL REFERENCES plans (id),
    status                   VARCHAR(24)  NOT NULL,
    provider                 VARCHAR(16)  NOT NULL,
    provider_subscription_id VARCHAR(128) UNIQUE,
    current_period_end       TIMESTAMPTZ,
    cancel_at_period_end     BOOLEAN      NOT NULL DEFAULT FALSE,
    -- When the newest provider event applied to this row was CREATED. Stripe
    -- does not deliver in order; an older event arriving late is ignored.
    provider_event_at        TIMESTAMPTZ,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_subscriptions_status CHECK (status IN ('ACTIVE', 'PAST_DUE', 'INCOMPLETE', 'CANCELED')),
    CONSTRAINT chk_subscriptions_provider CHECK (provider IN ('stripe', 'fake', 'internal'))
);

-- At most one subscription that counts, per user. Without this, two webhook
-- deliveries racing each other could leave a user with two live plans.
CREATE UNIQUE INDEX uq_subscriptions_live_per_user
    ON subscriptions (user_id) WHERE status IN ('ACTIVE', 'PAST_DUE');

CREATE TABLE usage_logs (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users (id),
    project_id BIGINT       REFERENCES projects (id) ON DELETE SET NULL,
    kind       VARCHAR(24)  NOT NULL,
    quantity   BIGINT       NOT NULL,
    ref        VARCHAR(64),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_usage_logs_kind CHECK (kind IN ('AI_TOKENS', 'PROJECT_CREATED', 'PREVIEW_STARTED'))
);

-- "How many tokens has this user spent today" - the query run before every generation.
CREATE INDEX idx_usage_logs_user_kind_time ON usage_logs (user_id, kind, created_at);

-- Stripe delivers each webhook event AT LEAST once. Remembering the ids we
-- have processed is what turns at-least-once into effectively-once.
CREATE TABLE stripe_events (
    event_id    VARCHAR(255) PRIMARY KEY,
    type        VARCHAR(100) NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Live previews count against the person who started them.
ALTER TABLE previews
    ADD COLUMN started_by BIGINT REFERENCES users (id) ON DELETE SET NULL;
