-- What each assistant reply cost at list price, so the conversation can show
-- it ("2,336 tokens · $0.0011") without joining back to generation_runs.
ALTER TABLE chat_messages ADD COLUMN cost_usd NUMERIC(12, 6);
