-- Project memory: short facts about a project that should outlive one chat -
-- "the brand colour is #2f6f4f", "prices are in rupees", "keep it one page".
-- Saved by the agent (remember tool) or by a person; read into every run.
CREATE TABLE project_memories (
    id          BIGSERIAL PRIMARY KEY,
    project_id  BIGINT       NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    content     VARCHAR(300) NOT NULL,
    source      VARCHAR(10)  NOT NULL CHECK (source IN ('agent', 'user')),
    created_by  BIGINT       REFERENCES users(id) ON DELETE SET NULL,
    run_id      BIGINT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_project_memories_project ON project_memories(project_id, id);
-- One copy of a fact per project, whatever its case or spacing.
CREATE UNIQUE INDEX uq_project_memories_fact ON project_memories(project_id, lower(btrim(content)));
