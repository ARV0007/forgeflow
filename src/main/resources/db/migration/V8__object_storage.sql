-- File contents may now live in object storage (the spec's MinIO) instead of
-- this row. Exactly one of the two holds the bytes:
--   content     forgeflow.storage.files=postgres (default)
--   object_key  forgeflow.storage.files=s3 - a content-addressed key,
--               projects/<id>/blobs/<sha256>, so identical content is stored
--               once and a key never changes meaning (old versions stay
--               readable - what version history builds on).
-- V4 deliberately left this column out; V8 adds it because the full-topology
-- mode now exists to use it.
ALTER TABLE project_files ALTER COLUMN content DROP NOT NULL;
ALTER TABLE project_files ADD COLUMN object_key VARCHAR(600);
ALTER TABLE project_files ADD CONSTRAINT chk_project_files_content_somewhere
    CHECK (content IS NOT NULL OR object_key IS NOT NULL);
