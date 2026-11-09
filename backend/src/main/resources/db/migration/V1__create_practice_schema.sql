CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE practice_attempt (
    id UUID PRIMARY KEY,
    writing_text TEXT NOT NULL,
    word_count INTEGER NOT NULL CHECK (word_count BETWEEN 200 AND 500),
    correction TEXT NOT NULL,
    natural_revision TEXT NOT NULL,
    estimated_level VARCHAR(2) NOT NULL CHECK (estimated_level IN ('A2', 'B1', 'B2', 'C1')),
    grammar_score SMALLINT NOT NULL CHECK (grammar_score BETWEEN 1 AND 5),
    vocabulary_score SMALLINT NOT NULL CHECK (vocabulary_score BETWEEN 1 AND 5),
    coherence_score SMALLINT NOT NULL CHECK (coherence_score BETWEEN 1 AND 5),
    strengths JSONB NOT NULL,
    structured_response JSONB NOT NULL,
    model_mode VARCHAR(16) NOT NULL CHECK (model_mode IN ('MOCK', 'REAL')),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_practice_attempt_created_at ON practice_attempt (created_at DESC);

CREATE TABLE learning_issue (
    id UUID PRIMARY KEY,
    practice_attempt_id UUID NOT NULL REFERENCES practice_attempt (id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    original_sentence TEXT NOT NULL,
    suggestion TEXT NOT NULL,
    category VARCHAR(32) NOT NULL CHECK (category IN ('GRAMMAR', 'VOCABULARY', 'SENTENCE_STRUCTURE', 'COHERENCE', 'PUNCTUATION')),
    severity VARCHAR(16) NOT NULL CHECK (severity IN ('MINOR', 'MAJOR')),
    explanation TEXT NOT NULL,
    UNIQUE (practice_attempt_id, position)
);

CREATE TABLE vocabulary_suggestion (
    id UUID PRIMARY KEY,
    practice_attempt_id UUID NOT NULL REFERENCES practice_attempt (id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    word TEXT NOT NULL,
    explanation TEXT NOT NULL,
    example TEXT NOT NULL,
    UNIQUE (practice_attempt_id, position)
);
