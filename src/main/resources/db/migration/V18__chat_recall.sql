-- Long chat memory: a message that has fallen out of the recent window can
-- still be recalled when it's relevant. Its vector is made lazily - the first
-- time it's older than the window and a search looks at it - then kept, so
-- each message is embedded at most once. NULL = not embedded yet.
ALTER TABLE chat_messages
    ADD COLUMN embedding       vector(768),
    ADD COLUMN embedding_model VARCHAR(64);
