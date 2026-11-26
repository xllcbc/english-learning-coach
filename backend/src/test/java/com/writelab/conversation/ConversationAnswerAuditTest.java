package com.writelab.conversation;

import com.writelab.agent.AgentStep;
import com.writelab.agent.AgentStepKind;
import com.writelab.agent.AgentStepStatus;
import com.writelab.agent.TutorCitableSource;
import com.writelab.agent.TutorRefusal;
import com.writelab.agent.TutorSourceKind;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The acceptance rule for one Teacher Conversation answer. A grounded answer may be persisted; an
 * uncited assertion, an invented citation, or a blank answer may not. The service and the gate
 * evaluation share this audit, so these are the same rules production enforces.
 */
class ConversationAnswerAuditTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<TutorCitableSource> SOURCES = List.of(new TutorCitableSource(
            1,
            TutorSourceKind.GROUNDING_MATERIAL,
            "让步连接词",
            "although 引导让步状语从句。",
            "WriteLab 自建依据材料",
            "CC BY 4.0"));

    @Test
    void acceptsAnAnswerThatCitesAnAvailableSource() {
        ConversationAnswerAudit audit = ConversationAnswerAudit.inspect(
                "although 引导让步状语从句 [依据1]。", SOURCES, List.of(), OBJECT_MAPPER);

        assertThat(audit.accepted(true)).isTrue();
        assertThat(audit.citations()).singleElement().satisfies(citation ->
                assertThat(citation.reference()).isEqualTo(1));
    }

    @Test
    void rejectsAnUncitedAssertionWhenASourceWasAvailable() {
        ConversationAnswerAudit audit = ConversationAnswerAudit.inspect(
                "这里应该用让步状语从句。", SOURCES, List.of(), OBJECT_MAPPER);

        assertThat(audit.accepted(true)).isFalse();
        assertThat(audit.hasCitableSource()).isTrue();
        assertThat(audit.citations()).isEmpty();
    }

    @Test
    void rejectsAnInventedCitation() {
        ConversationAnswerAudit audit = ConversationAnswerAudit.inspect(
                "这里应该用让步状语从句 [依据2]。", SOURCES, List.of(), OBJECT_MAPPER);

        assertThat(audit.unsupportedReferences()).isTrue();
        assertThat(audit.accepted(true)).isFalse();
    }

    @Test
    void acceptsAnExplicitRefusalWhenThereIsNothingToCite() {
        ConversationAnswerAudit audit = ConversationAnswerAudit.inspect(
                TutorRefusal.NO_GROUNDED_SOURCE, List.of(), List.of(), OBJECT_MAPPER);

        assertThat(audit.accepted(true)).isTrue();
        assertThat(audit.hasCitableSource()).isFalse();
        assertThat(audit.refusal()).isTrue();
    }

    @Test
    void rejectsAFabricatedAnswerWithoutACitableSource() {
        ConversationAnswerAudit audit = ConversationAnswerAudit.inspect(
                "你应该多用被动语态和分词短语。", List.of(), List.of(), OBJECT_MAPPER);

        assertThat(audit.rejectionReason(true))
                .isEqualTo(ConversationAnswerAudit.RejectionReason.UNGROUNDED_ANSWER);
        assertThat(audit.accepted(true)).isFalse();
    }

    @Test
    void rejectsABlankAnswerEvenWithoutSources() {
        ConversationAnswerAudit audit = ConversationAnswerAudit.inspect(
                "   ", List.of(), List.of(), OBJECT_MAPPER);

        assertThat(audit.accepted(true)).isFalse();
    }

    @Test
    void treatsASuccessfulDictionaryLookupAsACitableSource() {
        AgentStep lookup = new AgentStep(
                1,
                AgentStepKind.TOOL_CALL,
                "lookup_word",
                Map.of("word", "despite"),
                AgentStepStatus.SUCCESS,
                "{\"reference\":1,\"word\":\"despite\",\"meaningZh\":\"尽管\",\"partOfSpeech\":\"prep.\"}",
                1);

        ConversationAnswerAudit audit = ConversationAnswerAudit.inspect(
                "despite 表示「尽管」 [依据1]。", List.of(), List.of(lookup), OBJECT_MAPPER);

        assertThat(audit.hasCitableSource()).isTrue();
        assertThat(audit.accepted(true)).isTrue();
        assertThat(audit.citations()).singleElement().satisfies(citation ->
                assertThat(citation.kind()).isEqualTo(ConversationCitationKind.DICTIONARY_ENTRY));
    }
}
