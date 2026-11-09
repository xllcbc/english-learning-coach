CREATE TABLE dictionary_entry (
    word VARCHAR(64) PRIMARY KEY,
    phonetic VARCHAR(64) NOT NULL,
    part_of_speech VARCHAR(32) NOT NULL,
    meaning_zh TEXT NOT NULL,
    example_en TEXT NOT NULL
);

INSERT INTO dictionary_entry (word, phonetic, part_of_speech, meaning_zh, example_en) VALUES
    ('moreover', '/mɔːrˈoʊvər/', '副词', '此外；而且，用于补充递进的信息。', 'The plan is affordable; moreover, it is easy to implement.'),
    ('consequently', '/ˈkɑːnsɪkwentli/', '副词', '因此；结果，用于表达因果关系。', 'He trained every day; consequently, his writing became more fluent.'),
    ('effective', '/ɪˈfektɪv/', '形容词', '有效的；能产生预期结果的。', 'Daily practice is an effective way to improve your writing.'),
    ('particularly', '/pərˈtɪkjələrli/', '副词', '尤其；特别。', 'I particularly enjoy writing about my hometown.');

CREATE TABLE tool_invocation (
    id UUID PRIMARY KEY,
    practice_attempt_id UUID NOT NULL REFERENCES practice_attempt (id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    tool_name VARCHAR(64) NOT NULL,
    arguments JSONB NOT NULL,
    result_status VARCHAR(16) NOT NULL CHECK (result_status IN ('SUCCESS', 'NOT_FOUND', 'BLOCKED', 'FAILED')),
    duration_millis BIGINT NOT NULL CHECK (duration_millis >= 0),
    UNIQUE (practice_attempt_id, position)
);
