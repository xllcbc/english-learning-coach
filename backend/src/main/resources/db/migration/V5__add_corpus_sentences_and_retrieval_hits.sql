CREATE TABLE corpus_sentence (
    id UUID PRIMARY KEY,
    word_form VARCHAR(64) NOT NULL,
    sentence TEXT NOT NULL,
    source VARCHAR(128) NOT NULL,
    license VARCHAR(64) NOT NULL,
    UNIQUE (word_form, sentence)
);

CREATE INDEX idx_corpus_sentence_word_form ON corpus_sentence (word_form);

ALTER TABLE vocabulary_suggestion
    ADD COLUMN corpus_sentence_id UUID REFERENCES corpus_sentence (id) ON DELETE SET NULL;

CREATE TABLE retrieval_hit (
    id UUID PRIMARY KEY,
    practice_attempt_id UUID NOT NULL REFERENCES practice_attempt (id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    kind VARCHAR(32) NOT NULL CHECK (kind IN ('CORPUS_SENTENCE', 'GROUNDING_MATERIAL')),
    word_form VARCHAR(64),
    corpus_sentence_id UUID REFERENCES corpus_sentence (id) ON DELETE SET NULL,
    ranking INTEGER CHECK (ranking IS NULL OR ranking >= 1),
    similarity_score DOUBLE PRECISION,
    ordering VARCHAR(16) NOT NULL CHECK (ordering IN ('FIXED_ORDER', 'SIMILARITY')),
    skip_reason VARCHAR(32),
    UNIQUE (practice_attempt_id, position)
);
