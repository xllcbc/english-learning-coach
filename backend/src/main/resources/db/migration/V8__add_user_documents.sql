-- User Document is a third, learner-owned corpus. It is independent of grounding_material and
-- corpus_sentence, and it never stores the original uploaded file.
CREATE TABLE user_document (
    id UUID PRIMARY KEY,
    filename VARCHAR(255) NOT NULL,
    content_hash CHAR(64) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL CHECK (status IN (
        'UPLOADED', 'PARSING', 'CHUNKING', 'EMBEDDING', 'READY', 'FAILED'
    )),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    char_count INTEGER NOT NULL CHECK (char_count >= 0),
    chunk_count INTEGER NOT NULL DEFAULT 0 CHECK (chunk_count >= 0),
    embedding_model VARCHAR(128),
    chunker_version VARCHAR(32) NOT NULL,
    error_code VARCHAR(64),
    -- Replacement writes a new generation before switching this pointer; the first upload uses 1.
    active_generation INTEGER NOT NULL DEFAULT 1 CHECK (active_generation >= 1),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_user_document_created_at ON user_document (created_at DESC);
CREATE INDEX idx_user_document_status ON user_document (status);

CREATE TABLE document_chunk (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES user_document (id) ON DELETE CASCADE,
    generation INTEGER NOT NULL DEFAULT 1 CHECK (generation >= 1),
    position INTEGER NOT NULL CHECK (position >= 0),
    heading_path TEXT NOT NULL DEFAULT '',
    content TEXT NOT NULL,
    start_offset INTEGER NOT NULL CHECK (start_offset >= 0),
    end_offset INTEGER NOT NULL CHECK (end_offset >= start_offset),
    -- Null in Mock mode and until the REAL embedding path fills it.
    embedding halfvec(512),
    UNIQUE (document_id, generation, position)
);

CREATE INDEX idx_document_chunk_document_generation
    ON document_chunk (document_id, generation, position);

COMMENT ON COLUMN user_document.content_hash IS
    'SHA-256 of the normalized parsed text; identical uploads return the existing document';
COMMENT ON COLUMN user_document.filename IS
    'Display-only name; never used to build a filesystem path';
COMMENT ON COLUMN user_document.status IS
    'UPLOADED/PARSING/CHUNKING while indexing, READY or FAILED at a terminal state';
COMMENT ON COLUMN user_document.error_code IS
    'Stable DocumentErrorCode when status is FAILED; null otherwise';
COMMENT ON COLUMN document_chunk.heading_path IS
    'Markdown heading path such as Grammar > Subject-verb agreement; empty for plain text';
COMMENT ON COLUMN document_chunk.start_offset IS
    'Start offset into the normalized parsed text (start and end both point at UTF-16 indexes)';
