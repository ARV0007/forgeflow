-- V4: who wrote each file - the spec's PROJECT_FILE.created_by / updated_by.
--
-- A project with several editors needs to answer "who changed index.html?".
-- Until now every write was anonymous. Both columns are nullable because the
-- files written before this migration have no known author, and ON DELETE SET
-- NULL because deleting an account must not delete the work it touched in
-- someone else's project.
--
-- The spec's minio_object_key column is deliberately NOT added. File content
-- stays in Postgres TEXT: generated projects are a few small text files, a
-- 200 KB cap per file is enforced by the agent's write tool, and an object
-- store would add a second system that has to stay consistent with this table
-- for no gain at this size. ProjectFileService is the only code that touches
-- content, so moving it to object storage later is a change in one class.

ALTER TABLE project_files
    ADD COLUMN created_by BIGINT REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN updated_by BIGINT REFERENCES users (id) ON DELETE SET NULL;
