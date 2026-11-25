-- Learning Issues found in a Speaking Session turn belong to that session, never to a Practice
-- Attempt (ADR-0012). They replay with their turn and are deliberately absent from learning_issue,
-- whose rows remain anchored to written Practice Attempts.

CREATE TABLE speaking_session_learning_issue (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES speaking_session (id) ON DELETE CASCADE,
    turn_id UUID NOT NULL REFERENCES speaking_session_turn (id) ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK (position >= 1),
    original_sentence TEXT NOT NULL,
    suggestion TEXT NOT NULL,
    category VARCHAR(32) NOT NULL CHECK (category IN ('GRAMMAR', 'VOCABULARY', 'SENTENCE_STRUCTURE', 'COHERENCE', 'PUNCTUATION')),
    severity VARCHAR(16) NOT NULL CHECK (severity IN ('MINOR', 'MAJOR')),
    explanation TEXT NOT NULL,
    UNIQUE (turn_id, position)
);

CREATE INDEX idx_speaking_session_learning_issue_session
    ON speaking_session_learning_issue (session_id);

COMMENT ON TABLE speaking_session_learning_issue IS
    'A Learning Issue found in a Speaking Session turn; independent of Practice Attempt and written Learning Issues.';
