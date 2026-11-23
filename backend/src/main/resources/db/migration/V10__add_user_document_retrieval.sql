-- Mock-mode User Document retrieval uses PostgreSQL character trigram similarity. The extension is
-- idempotent so a database created before this migration can be upgraded in place.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_document_chunk_content_trgm
    ON document_chunk USING gin (content gin_trgm_ops);

-- User Document is the third Retrieval Hit kind. The citation columns persist a snapshot so replay
-- never has to join the live document tables, and survive a later document deletion.
ALTER TABLE retrieval_hit
    DROP CONSTRAINT retrieval_hit_kind_check;

ALTER TABLE retrieval_hit
    ADD CONSTRAINT retrieval_hit_kind_check
        CHECK (kind IN ('CORPUS_SENTENCE', 'GROUNDING_MATERIAL', 'USER_DOCUMENT'));

ALTER TABLE retrieval_hit
    ADD COLUMN document_id UUID REFERENCES user_document (id) ON DELETE SET NULL,
    ADD COLUMN document_chunk_id UUID REFERENCES document_chunk (id) ON DELETE SET NULL,
    ADD COLUMN citation_reference INTEGER
        CHECK (citation_reference IS NULL OR citation_reference >= 1),
    ADD COLUMN citation_title TEXT,
    ADD COLUMN citation_text TEXT;

-- Mock-mode ranking uses character trigram similarity, which is neither a fixed order nor a vector
-- cosine score. Keep the two existing values so Grounding Material rows replay unchanged.
ALTER TABLE retrieval_hit
    DROP CONSTRAINT retrieval_hit_ordering_check;

ALTER TABLE retrieval_hit
    ADD CONSTRAINT retrieval_hit_ordering_check
        CHECK (ordering IN ('FIXED_ORDER', 'VECTOR_SIMILARITY', 'TRIGRAM_SIMILARITY'));

COMMENT ON COLUMN retrieval_hit.document_id IS
    'User Document cited by a USER_DOCUMENT hit; null when the document was deleted';
COMMENT ON COLUMN retrieval_hit.document_chunk_id IS
    'Document Chunk cited by a USER_DOCUMENT hit; null when the chunk or its document was deleted';
COMMENT ON COLUMN retrieval_hit.citation_reference IS
    'Unified 1-based [依据N] number shared by Grounding Material and User Document citations';
COMMENT ON COLUMN retrieval_hit.citation_title IS
    'Snapshot of document name + heading path at generation time';
COMMENT ON COLUMN retrieval_hit.citation_text IS
    'Snapshot of the cited chunk text at generation time';
COMMENT ON COLUMN retrieval_hit.ordering IS
    'FIXED_ORDER: deterministic ranking, similarity_score is null; '
    'VECTOR_SIMILARITY: ranked by cosine similarity; TRIGRAM_SIMILARITY: ranked by pg_trgm score';
COMMENT ON COLUMN retrieval_hit.kind IS
    'CORPUS_SENTENCE for the feedback path; GROUNDING_MATERIAL and USER_DOCUMENT for the explanation path';
COMMENT ON COLUMN retrieval_hit.skip_reason IS
    'NO_CORPUS_SENTENCE / NO_GROUNDING_MATERIAL / NO_USER_DOCUMENT when nothing relevant was found; '
    'RETRIEVAL_FAILED when the lookup threw';
