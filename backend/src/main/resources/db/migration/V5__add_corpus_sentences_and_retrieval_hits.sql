CREATE TABLE corpus_sentence (
    id UUID PRIMARY KEY,
    word_form VARCHAR(64) NOT NULL CHECK (word_form = lower(word_form)),
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
    kind VARCHAR(32) NOT NULL CHECK (kind IN ('CORPUS_SENTENCE')),
    word_form VARCHAR(64),
    corpus_sentence_id UUID REFERENCES corpus_sentence (id) ON DELETE SET NULL,
    ranking INTEGER CHECK (ranking IS NULL OR ranking >= 1),
    similarity_score DOUBLE PRECISION,
    ordering VARCHAR(16) NOT NULL CHECK (ordering IN ('FIXED_ORDER')),
    skip_reason VARCHAR(32),
    UNIQUE (practice_attempt_id, position)
);

COMMENT ON COLUMN retrieval_hit.ordering IS
    'FIXED_ORDER: ranking is deterministic and similarity_score is therefore null';
COMMENT ON COLUMN retrieval_hit.similarity_score IS
    'Null for FIXED_ORDER; filled only when semantic ranking is added';
COMMENT ON COLUMN retrieval_hit.skip_reason IS
    'NO_CORPUS_SENTENCE when no candidate matched; RETRIEVAL_FAILED when the lookup threw';
COMMENT ON COLUMN retrieval_hit.corpus_sentence_id IS
    'Adopted Corpus Sentence; null when the model example was kept';
