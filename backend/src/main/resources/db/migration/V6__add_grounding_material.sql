CREATE TABLE grounding_material (
    id UUID PRIMARY KEY,
    category VARCHAR(32) NOT NULL CHECK (category IN ('GRAMMAR', 'VOCABULARY', 'SENTENCE_STRUCTURE', 'COHERENCE', 'PUNCTUATION')),
    content TEXT NOT NULL,
    source VARCHAR(128) NOT NULL,
    license VARCHAR(64) NOT NULL,
    UNIQUE (category, content)
);

CREATE INDEX idx_grounding_material_category ON grounding_material (category);

ALTER TABLE retrieval_hit
    DROP CONSTRAINT retrieval_hit_kind_check;

ALTER TABLE retrieval_hit
    ADD CONSTRAINT retrieval_hit_kind_check CHECK (kind IN ('CORPUS_SENTENCE', 'GROUNDING_MATERIAL'));

ALTER TABLE retrieval_hit
    ADD COLUMN grounding_material_id UUID REFERENCES grounding_material (id) ON DELETE SET NULL;

-- Grounding Material is retrieved when the explanation is generated, long after the Corpus Sentence
-- hits were written, so position counts per kind instead of per practice attempt.
ALTER TABLE retrieval_hit
    DROP CONSTRAINT retrieval_hit_practice_attempt_id_position_key;

ALTER TABLE retrieval_hit
    ADD CONSTRAINT retrieval_hit_practice_attempt_id_kind_position_key
        UNIQUE (practice_attempt_id, kind, position);

COMMENT ON COLUMN retrieval_hit.grounding_material_id IS
    'Cited Grounding Material; null when the explanation was generated without Grounding Material';
COMMENT ON COLUMN retrieval_hit.kind IS
    'CORPUS_SENTENCE for the feedback path, GROUNDING_MATERIAL for the explanation path';
COMMENT ON COLUMN retrieval_hit.skip_reason IS
    'NO_CORPUS_SENTENCE / NO_GROUNDING_MATERIAL when nothing relevant was found; '
    'RETRIEVAL_FAILED when the lookup threw';
