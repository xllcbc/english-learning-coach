CREATE TABLE teacher_conversation (
    id UUID PRIMARY KEY,
    practice_attempt_id UUID NOT NULL UNIQUE REFERENCES practice_attempt (id) ON DELETE CASCADE,
    model_mode VARCHAR(16) NOT NULL CHECK (model_mode IN ('MOCK', 'REAL')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE teacher_conversation_turn (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES teacher_conversation (id) ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK (position >= 1),
    learner_message TEXT NOT NULL,
    answer TEXT NOT NULL,
    stop_reason VARCHAR(32) NOT NULL
        CHECK (stop_reason IN ('COMPLETED', 'STEP_BUDGET_EXHAUSTED', 'TOOL_BUDGET_EXHAUSTED')),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (conversation_id, position)
);

CREATE INDEX idx_teacher_conversation_turn_conversation
    ON teacher_conversation_turn (conversation_id, position);

CREATE TABLE agent_step (
    id UUID PRIMARY KEY,
    turn_id UUID NOT NULL REFERENCES teacher_conversation_turn (id) ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK (position >= 1),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('TOOL_CALL', 'ANSWER')),
    tool_name VARCHAR(64),
    arguments JSONB NOT NULL,
    result_status VARCHAR(16) NOT NULL
        CHECK (result_status IN ('SUCCESS', 'NOT_FOUND', 'BLOCKED', 'FAILED', 'UNKNOWN_TOOL')),
    output TEXT NOT NULL,
    duration_millis BIGINT NOT NULL CHECK (duration_millis >= 0),
    UNIQUE (turn_id, position)
);

CREATE TABLE teacher_conversation_citation (
    id UUID PRIMARY KEY,
    turn_id UUID NOT NULL REFERENCES teacher_conversation_turn (id) ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK (position >= 1),
    reference INTEGER NOT NULL CHECK (reference >= 1),
    kind VARCHAR(24) NOT NULL
        CHECK (kind IN ('GROUNDING_MATERIAL', 'DICTIONARY_ENTRY', 'USER_DOCUMENT')),
    title TEXT NOT NULL,
    content TEXT NOT NULL,
    source TEXT NOT NULL,
    license TEXT,
    UNIQUE (turn_id, position),
    UNIQUE (turn_id, reference)
);

-- A model-driven tool call inside a conversation is a Tool Invocation as well as an Agent Step
-- (ADR-0010). The nullable turn link keeps grading's invocations distinct from conversation ones.
ALTER TABLE tool_invocation
    ADD COLUMN teacher_conversation_turn_id UUID
        REFERENCES teacher_conversation_turn (id) ON DELETE CASCADE;

CREATE INDEX idx_tool_invocation_teacher_conversation_turn
    ON tool_invocation (teacher_conversation_turn_id);

COMMENT ON TABLE teacher_conversation IS
    'One Teacher Conversation anchored to exactly one Practice Attempt; replay never calls the model.';
COMMENT ON TABLE agent_step IS
    'Model-driven decision and action inside a Teacher Conversation Turn (ADR-0010).';
COMMENT ON TABLE teacher_conversation_citation IS
    'Answer citation snapshot, separate from Agent Step and Retrieval Hit.';
