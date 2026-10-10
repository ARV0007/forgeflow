-- Publish: a project's public site, at /s/{slug}/. A site serves one
-- CHECKPOINT - a frozen version - not the live files, so editing never
-- changes what visitors see until you publish again, and publishing an older
-- checkpoint is an instant rollback. The slug survives unpublishing, so the
-- address comes back when you publish again.
CREATE TABLE sites (
    project_id     BIGINT       PRIMARY KEY REFERENCES projects(id) ON DELETE CASCADE,
    slug           VARCHAR(80)  NOT NULL UNIQUE,
    checkpoint_id  BIGINT       NOT NULL REFERENCES checkpoints(id),
    live           BOOLEAN      NOT NULL DEFAULT TRUE,
    published_by   BIGINT       REFERENCES users(id),
    published_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Every publish, newest last: what was live when, and who put it there.
CREATE TABLE site_releases (
    id             BIGSERIAL    PRIMARY KEY,
    project_id     BIGINT       NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    checkpoint_id  BIGINT       NOT NULL REFERENCES checkpoints(id),
    published_by   BIGINT       REFERENCES users(id),
    published_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_site_releases_project ON site_releases(project_id, id DESC);
