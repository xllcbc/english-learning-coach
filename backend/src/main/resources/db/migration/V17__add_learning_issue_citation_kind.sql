-- Teacher Conversation answers can now cite a recurring Learning Issue pattern read from the
-- learner's past Practice Attempts. The citation kind stays distinct from Grounding Material,
-- Dictionary Entry and User Document so provenance remains unambiguous (ADR-0010).
ALTER TABLE teacher_conversation_citation
    DROP CONSTRAINT teacher_conversation_citation_kind_check;

ALTER TABLE teacher_conversation_citation
    ADD CONSTRAINT teacher_conversation_citation_kind_check
        CHECK (kind IN ('GROUNDING_MATERIAL', 'DICTIONARY_ENTRY', 'USER_DOCUMENT', 'LEARNING_ISSUE'));
