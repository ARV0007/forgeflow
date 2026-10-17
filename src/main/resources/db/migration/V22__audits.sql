-- The audit agent: a read-only run that looks for bugs, accessibility,
-- security and UX problems and files them as findings the user can fix with
-- one click or dismiss. On demand, or nightly for projects that opt in.
CREATE TABLE project_audits (
    id           BIGSERIAL PRIMARY KEY,
    project_id   BIGINT        NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    status       VARCHAR(10)   NOT NULL CHECK (status IN ('RUNNING', 'DONE', 'FAILED')),
    trigger      VARCHAR(10)   NOT NULL CHECK (trigger IN ('MANUAL', 'NIGHTLY')),
    requested_by BIGINT        REFERENCES users(id) ON DELETE SET NULL,
    model        VARCHAR(80),
    summary      TEXT,
    error        TEXT,
    tokens       INT           NOT NULL DEFAULT 0,
    cost_usd     NUMERIC(12,6) NOT NULL DEFAULT 0,
    started_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ
);
CREATE INDEX idx_project_audits_project ON project_audits(project_id, id DESC);
-- One audit at a time per project - also what stops two API instances from
-- both running the same nightly audit.
CREATE UNIQUE INDEX uq_project_audits_running ON project_audits(project_id) WHERE status = 'RUNNING';

CREATE TABLE audit_findings (
    id          BIGSERIAL PRIMARY KEY,
    audit_id    BIGINT       NOT NULL REFERENCES project_audits(id) ON DELETE CASCADE,
    project_id  BIGINT       NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    severity    VARCHAR(6)   NOT NULL CHECK (severity IN ('high', 'medium', 'low')),
    category    VARCHAR(16)  NOT NULL CHECK (category IN ('bug', 'accessibility', 'security', 'performance', 'ux')),
    file        VARCHAR(512),
    problem     VARCHAR(500) NOT NULL,
    suggestion  VARCHAR(500),
    status      VARCHAR(10)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'FIXED', 'DISMISSED')),
    fix_run_id  BIGINT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_findings_audit ON audit_findings(audit_id);

-- Opt-in per project: audit it every night if its files changed.
CREATE TABLE audit_settings (
    project_id BIGINT  PRIMARY KEY REFERENCES projects(id) ON DELETE CASCADE,
    nightly    BOOLEAN NOT NULL DEFAULT false,
    updated_by BIGINT  REFERENCES users(id) ON DELETE SET NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
