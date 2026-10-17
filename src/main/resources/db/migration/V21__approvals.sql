-- Human in the loop: actions the agent may only propose. Today, deleting a
-- file; the user approves or rejects it in the workbench. Nothing happens to
-- the project until a person says yes.
CREATE TABLE agent_approvals (
    id           BIGSERIAL PRIMARY KEY,
    project_id   BIGINT       NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    run_id       BIGINT,
    kind         VARCHAR(20)  NOT NULL CHECK (kind IN ('DELETE_FILE')),
    path         VARCHAR(512) NOT NULL,
    reason       VARCHAR(300),
    status       VARCHAR(10)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    requested_by BIGINT       REFERENCES users(id) ON DELETE SET NULL,
    decided_by   BIGINT       REFERENCES users(id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decided_at   TIMESTAMPTZ
);
CREATE INDEX idx_agent_approvals_project ON agent_approvals(project_id, id DESC);
-- One open request per file.
CREATE UNIQUE INDEX uq_agent_approvals_pending ON agent_approvals(project_id, path) WHERE status = 'PENDING';
