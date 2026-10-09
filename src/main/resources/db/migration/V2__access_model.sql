-- V2: the access model from the Lovable-clone spec.
--
-- Spec ER diagram: USER gains identity-provider and billing columns; PROJECT
-- gains is_public and thumbnail_url; PROJECT_MEMBER gives one project many
-- users, each an EDITOR or a VIEWER.
--
-- The spec also draws a PROJECT_OWNERSHIP join table. It is deliberately NOT
-- created: a project has exactly one owner, and projects.owner_id already
-- says so with a NOT NULL foreign key. A join table would allow zero owners or
-- three, and every query would need to rule those states out.

-- ---------------------------------------------------------------- users
ALTER TABLE users
    ADD COLUMN avatar_url         VARCHAR(512),
    ADD COLUMN provider           VARCHAR(32)  NOT NULL DEFAULT 'local',
    ADD COLUMN provider_id        VARCHAR(255),
    ADD COLUMN email_verified     BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN stripe_customer_id VARCHAR(255),
    ADD COLUMN deleted_at         TIMESTAMPTZ;

-- An account created through Google has no password of its own.
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

-- One account per external identity, and one per Stripe customer. Partial
-- indexes, because most rows have neither.
CREATE UNIQUE INDEX uq_users_provider_identity
    ON users (provider, provider_id) WHERE provider_id IS NOT NULL;
CREATE UNIQUE INDEX uq_users_stripe_customer
    ON users (stripe_customer_id) WHERE stripe_customer_id IS NOT NULL;

-- Invites look people up by email regardless of how they typed it.
CREATE INDEX idx_users_email_lower ON users (lower(email));

-- ------------------------------------------------------------- projects
ALTER TABLE projects
    ADD COLUMN is_public     BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN thumbnail_url VARCHAR(512);

-- ------------------------------------------------------ project_members
-- The owner is NOT a row here. Ownership lives in projects.owner_id; this
-- table only holds the people the owner has let in.
CREATE TABLE project_members (
    project_id BIGINT      NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES users (id)    ON DELETE CASCADE,
    role       VARCHAR(16) NOT NULL CHECK (role IN ('EDITOR', 'VIEWER')),
    invited_by BIGINT      REFERENCES users (id),
    invited_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, user_id)
);

-- "Which projects can this user see?" runs on every project list.
CREATE INDEX idx_project_members_user ON project_members (user_id);
