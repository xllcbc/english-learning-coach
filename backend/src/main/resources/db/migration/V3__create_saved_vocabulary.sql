CREATE TABLE saved_vocabulary (
    id UUID PRIMARY KEY,
    word VARCHAR(64) NOT NULL UNIQUE,
    phonetic VARCHAR(64) NOT NULL,
    part_of_speech VARCHAR(32) NOT NULL,
    meaning_zh TEXT NOT NULL,
    example_en TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_saved_vocabulary_created_at ON saved_vocabulary (created_at DESC);
