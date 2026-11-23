-- A changed upload with an existing file name replaces the same User Document in place.
-- The new content is indexed under a pending generation while active_generation keeps pointing at
-- the last usable content. Only a successful indexing run switches the pointer and removes the old
-- generation; a failure keeps the old content active and exposes a stable replacement error code.
ALTER TABLE user_document
    ADD COLUMN pending_generation INTEGER,
    ADD COLUMN pending_content_hash CHAR(64),
    ADD COLUMN pending_char_count INTEGER,
    ADD COLUMN pending_error_code VARCHAR(64);

ALTER TABLE user_document
    ADD CONSTRAINT chk_user_document_pending_generation
        CHECK (pending_generation IS NULL OR pending_generation > active_generation),
    ADD CONSTRAINT chk_user_document_pending_hash
        CHECK ((pending_generation IS NULL) = (pending_content_hash IS NULL)),
    ADD CONSTRAINT chk_user_document_pending_char_count
        CHECK ((pending_generation IS NULL) = (pending_char_count IS NULL)),
    ADD CONSTRAINT chk_user_document_pending_char_count_non_negative
        CHECK (pending_char_count IS NULL OR pending_char_count >= 0);

CREATE UNIQUE INDEX uq_user_document_pending_content_hash
    ON user_document (pending_content_hash)
    WHERE pending_content_hash IS NOT NULL;

-- Uploads are serialized by an advisory lock, so the same-name lookup has one deterministic target
-- without a unique index that could fail on data written before this migration.

COMMENT ON COLUMN user_document.pending_generation IS
    'Generation being indexed for an in-place replacement; null when no replacement is pending';
COMMENT ON COLUMN user_document.pending_content_hash IS
    'SHA-256 of the pending replacement text; swapped into content_hash on success';
COMMENT ON COLUMN user_document.pending_error_code IS
    'Stable DocumentErrorCode when the pending replacement failed; active content stays usable';
