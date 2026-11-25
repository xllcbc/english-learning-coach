-- ADR-0011 budgets the speaking turn latency: from the learner finishing speaking to the reply
-- starting to play, p95 should be about 2.5 seconds. The server records the processing share of
-- that path on the turn so the p95 can be recomputed from persisted history. Turns that never
-- produced a playable reply (a transcription fallback) carry no latency.

ALTER TABLE speaking_session_turn
    ADD COLUMN latency_ms INTEGER CHECK (latency_ms IS NULL OR latency_ms >= 0);

COMMENT ON COLUMN speaking_session_turn.latency_ms IS
    'Milliseconds from receiving the learner''s utterance to the reply being ready to play (ADR-0011).';
