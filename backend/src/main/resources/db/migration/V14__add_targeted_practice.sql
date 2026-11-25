CREATE TABLE targeted_practice (
    id UUID PRIMARY KEY,
    priority INTEGER NOT NULL CHECK (priority >= 1),
    target_category VARCHAR(32) NOT NULL
        CHECK (target_category IN ('GRAMMAR', 'VOCABULARY', 'SENTENCE_STRUCTURE', 'COHERENCE', 'PUNCTUATION')),
    target_severity VARCHAR(16) NOT NULL CHECK (target_severity IN ('MINOR', 'MAJOR')),
    focus TEXT NOT NULL,
    prompt TEXT NOT NULL,
    evidence JSONB NOT NULL,
    grounding_materials JSONB NOT NULL,
    occurrence_count INTEGER NOT NULL CHECK (occurrence_count >= 0),
    attempt_count INTEGER NOT NULL CHECK (attempt_count >= 1),
    before_practice_attempt_id UUID REFERENCES practice_attempt (id) ON DELETE CASCADE,
    before_created_at TIMESTAMPTZ NOT NULL,
    before_count INTEGER NOT NULL CHECK (before_count >= 0),
    practice_attempt_id UUID UNIQUE REFERENCES practice_attempt (id) ON DELETE CASCADE,
    after_count INTEGER CHECK (after_count IS NULL OR after_count >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CHECK ((practice_attempt_id IS NULL) = (after_count IS NULL)),
    CHECK ((practice_attempt_id IS NULL) = (completed_at IS NULL))
);

CREATE INDEX idx_targeted_practice_created_at ON targeted_practice (created_at DESC);

COMMENT ON TABLE targeted_practice IS
    'One Targeted Practice task generated from a Study Plan; links to the Practice Attempt submitted through normal grading.';
COMMENT ON COLUMN targeted_practice.evidence IS
    'Snapshot of the Study Plan evidence at generation time, so later reviews cannot rewrite the task.';
COMMENT ON COLUMN targeted_practice.practice_attempt_id IS
    'The Practice Attempt produced by the existing grading flow; null while the task is pending.';
