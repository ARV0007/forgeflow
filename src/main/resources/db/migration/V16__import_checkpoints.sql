-- An imported repo or zip is a version of its own in History, like an AI run
-- or a restore: "Imported from github.com/owner/repo".
ALTER TABLE checkpoints DROP CONSTRAINT checkpoints_kind_check;
ALTER TABLE checkpoints ADD CONSTRAINT checkpoints_kind_check
    CHECK (kind IN ('BASELINE', 'RUN', 'RESTORE', 'IMPORT'));
