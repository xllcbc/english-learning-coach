package com.writelab.conversation;

import com.writelab.agent.AgentStep;
import com.writelab.agent.AgentStepKind;
import com.writelab.agent.AgentStepStatus;
import com.writelab.agent.TutorCitableSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationCitationsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void numbersBriefingSourcesThenSuccessfulDictionaryLookups() {
        List<TutorCitableSource> sources = List.of(
                new TutorCitableSource(
                        1, "GROUNDING_MATERIAL", "连贯性",
                        "用连接词显式标明逻辑关系。", "WriteLab 自建依据材料", "CC BY 4.0"),
                new TutorCitableSource(
                        2, "USER_DOCUMENT", "grammar.md · Grammar > Connectors",
                        "However 用于转折。", "我的文档", null));
        List<AgentStep> steps = List.of(
                toolStep("lookup_word", AgentStepStatus.SUCCESS, """
                        {"status":"SUCCESS","word":"moreover","phonetic":"/mɔːrˈoʊvər/","partOfSpeech":"副词","meaningZh":"此外；而且。","exampleEn":"The plan is affordable; moreover, it is easy to implement."}
                        """),
                toolStep("lookup_word", AgentStepStatus.NOT_FOUND, "{\"status\":\"NOT_FOUND\"}"));

        List<ConversationCitation> available = ConversationCitations.available(sources, steps, objectMapper);

        assertThat(available).extracting(ConversationCitation::reference).containsExactly(1, 2, 3);
        assertThat(available.get(0).kind()).isEqualTo(ConversationCitationKind.GROUNDING_MATERIAL);
        assertThat(available.get(1).kind()).isEqualTo(ConversationCitationKind.USER_DOCUMENT);
        assertThat(available.get(2).kind()).isEqualTo(ConversationCitationKind.DICTIONARY_ENTRY);
        assertThat(available.get(2).title()).contains("moreover");
        assertThat(available.get(2).content()).contains("此外").contains("副词");
    }

    @Test
    void keepsOnlyTheCitationsTheAnswerActuallyReferences() {
        List<ConversationCitation> available = List.of(
                new ConversationCitation(1, ConversationCitationKind.GROUNDING_MATERIAL,
                        "连贯性", "规则一", "WriteLab 自建依据材料", "CC BY 4.0"),
                new ConversationCitation(2, ConversationCitationKind.GROUNDING_MATERIAL,
                        "词汇", "规则二", "WriteLab 自建依据材料", "CC BY 4.0"),
                new ConversationCitation(3, ConversationCitationKind.DICTIONARY_ENTRY,
                        "Dictionary Entry · moreover", "此外", "内置词典", null));

        List<ConversationCitation> cited = ConversationCitations.cited(
                "这里更适合用 moreover [依据3]，同时注意衔接 [依据2]；[依据9] 不存在。",
                available);

        assertThat(cited).extracting(ConversationCitation::reference).containsExactly(2, 3);
        assertThat(ConversationCitations.hasUnsupportedReferences(
                "这里更适合用 moreover [依据3]，同时注意衔接 [依据2]；[依据9] 不存在。", cited))
                .isTrue();
    }

    @Test
    void acceptsAnAnswerThatOnlyCitesAvailableSources() {
        List<ConversationCitation> cited = List.of(
                new ConversationCitation(1, ConversationCitationKind.GROUNDING_MATERIAL,
                        "连贯性", "规则一", "WriteLab 自建依据材料", "CC BY 4.0"));

        assertThat(ConversationCitations.hasUnsupportedReferences("用连接词更连贯 [依据1]。", cited))
                .isFalse();
        assertThat(ConversationCitations.hasUnsupportedReferences("缺少可引用的依据。", cited))
                .isFalse();
    }

    private static AgentStep toolStep(String toolName, AgentStepStatus status, String output) {
        return new AgentStep(1, AgentStepKind.TOOL_CALL, toolName, Map.of(), status, output, 0L);
    }
}
