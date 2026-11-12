-- Embeddings stay optional: rows imported before an embedding index exists carry NULL and are
-- skipped by the vector retriever, which degrades to "no candidates" rather than an error.
ALTER TABLE corpus_sentence
    ADD COLUMN embedding halfvec(512);

CREATE INDEX idx_corpus_sentence_embedding
    ON corpus_sentence USING hnsw (embedding halfvec_cosine_ops);

ALTER TABLE grounding_material
    ADD COLUMN embedding halfvec(512);

CREATE INDEX idx_grounding_material_embedding
    ON grounding_material USING hnsw (embedding halfvec_cosine_ops);

ALTER TABLE retrieval_hit
    DROP CONSTRAINT retrieval_hit_ordering_check;

ALTER TABLE retrieval_hit
    ALTER COLUMN ordering TYPE VARCHAR(32);

ALTER TABLE retrieval_hit
    ADD CONSTRAINT retrieval_hit_ordering_check
        CHECK (ordering IN ('FIXED_ORDER', 'VECTOR_SIMILARITY'));

COMMENT ON COLUMN corpus_sentence.embedding IS
    '512-dimension embedding for semantic ranking; NULL until the offline index build fills it';
COMMENT ON COLUMN grounding_material.embedding IS
    '512-dimension embedding for semantic ranking; NULL until the offline index build fills it';
COMMENT ON COLUMN retrieval_hit.ordering IS
    'FIXED_ORDER: deterministic ranking, similarity_score is null; '
    'VECTOR_SIMILARITY: ranked by cosine similarity, similarity_score is the score';
COMMENT ON COLUMN retrieval_hit.similarity_score IS
    'Cosine similarity in [-1, 1] when ordering is VECTOR_SIMILARITY; null for FIXED_ORDER';
