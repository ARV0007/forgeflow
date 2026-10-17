-- The agentic side of each run (plan, review, guardrail notices, questions,
-- approvals, memory, trajectory), as the AgentReport the API returned. Kept
-- whole: it's read back as a unit (history, evals), never queried by field.
ALTER TABLE generation_runs ADD COLUMN agent_report JSONB;
