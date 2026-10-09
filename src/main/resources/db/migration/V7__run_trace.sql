-- V7: link each generation run to the request trace that started it.
-- "Run 512 failed" in the database -> its trace id -> every log line and span
-- from that request, including the model calls and builds inside it.
ALTER TABLE generation_runs ADD COLUMN trace_id VARCHAR(32);
CREATE INDEX idx_generation_runs_trace ON generation_runs (trace_id);
