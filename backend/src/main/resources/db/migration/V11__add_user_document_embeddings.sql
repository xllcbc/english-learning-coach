-- REAL User Document retrieval reuses the 512-dimension halfvec column from V8. The HNSW index is
-- partial because Mock-mode uploads still leave every chunk vector NULL and must stay searchable
-- through the pg_trgm index from V10 instead.
CREATE INDEX idx_document_chunk_embedding_hnsw
    ON document_chunk USING hnsw (embedding halfvec_cosine_ops)
    WHERE embedding IS NOT NULL;

COMMENT ON COLUMN user_document.embedding_model IS
    'Embedding model that produced every active Document Chunk vector; null means the document has '
    'no vector index (for example a Mock-mode upload) and REAL retrieval must skip it';
COMMENT ON COLUMN document_chunk.embedding IS
    '512-dimension halfvec used by REAL cosine retrieval; null in Mock mode or before indexing';
COMMENT ON COLUMN user_document.chunker_version IS
    'Document chunker version that produced the active generation; recorded so re-chunking needs are '
    'visible, while the vector rebuild CLI only refreshes embeddings';
