-- Speaking Practice is its own aggregate root, separate from Practice Attempt and
-- Teacher Conversation (ADR-0012). A session owns its turns; each turn may carry Agent Steps and
-- model-driven Tool Invocations. Reopening a session reads persisted rows and never calls a model.

CREATE TABLE speaking_session (
    id UUID PRIMARY KEY,
    model_mode VARCHAR(16) NOT NULL CHECK (model_mode IN ('MOCK', 'REAL')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_speaking_session_created_at ON speaking_session (created_at DESC);

CREATE TABLE speaking_session_turn (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES speaking_session (id) ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK (position >= 1),
    input_mode VARCHAR(8) NOT NULL CHECK (input_mode IN ('VOICE', 'TEXT')),
    transcript TEXT,
    reply TEXT NOT NULL,
    fallback_reason VARCHAR(24)
        CHECK (fallback_reason IN ('FAILED', 'TIMEOUT', 'SYNTHESIS_FAILED')),
    stop_reason VARCHAR(32)
        CHECK (stop_reason IN ('COMPLETED', 'STEP_BUDGET_EXHAUSTED', 'TOOL_BUDGET_EXHAUSTED')),
    model_mode VARCHAR(16) NOT NULL CHECK (model_mode IN ('MOCK', 'REAL')),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (session_id, position)
);

CREATE INDEX idx_speaking_session_turn_session
    ON speaking_session_turn (session_id, position);

-- Agent Steps are shared provenance (ADR-0010). A step belongs to exactly one owner: a Teacher
-- Conversation Turn or a Speaking Session Turn.
ALTER TABLE agent_step
    ALTER COLUMN turn_id DROP NOT NULL;

ALTER TABLE agent_step
    ADD COLUMN speaking_session_turn_id UUID
        REFERENCES speaking_session_turn (id) ON DELETE CASCADE;

ALTER TABLE agent_step
    ADD CONSTRAINT agent_step_single_owner
        CHECK ((turn_id IS NOT NULL) <> (speaking_session_turn_id IS NOT NULL));

CREATE UNIQUE INDEX idx_agent_step_speaking_turn_position
    ON agent_step (speaking_session_turn_id, position)
    WHERE speaking_session_turn_id IS NOT NULL;

-- A model-driven tool call in Speaking Practice is a Tool Invocation too, but it has no Practice
-- Attempt. Keep the two owners exclusive so grading, conversation and speech traces never blur.
ALTER TABLE tool_invocation
    ALTER COLUMN practice_attempt_id DROP NOT NULL;

ALTER TABLE tool_invocation
    ADD COLUMN speaking_session_turn_id UUID
        REFERENCES speaking_session_turn (id) ON DELETE CASCADE;

ALTER TABLE tool_invocation
    ADD CONSTRAINT tool_invocation_single_owner
        CHECK ((practice_attempt_id IS NOT NULL) <> (speaking_session_turn_id IS NOT NULL));

CREATE INDEX idx_tool_invocation_speaking_turn
    ON tool_invocation (speaking_session_turn_id);

COMMENT ON TABLE speaking_session IS
    'A Speaking Practice aggregate root: replayable transcript turns, separate from Practice Attempt (ADR-0012).';
COMMENT ON TABLE speaking_session_turn IS
    'One spoken or typed turn in a Speaking Session; fallback_reason records a degraded speech step.';
