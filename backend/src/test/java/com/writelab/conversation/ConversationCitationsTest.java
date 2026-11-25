package com.writelab.conversation;

import com.writelab.agent.AgentStep;
import com.writelab.agent.AgentStepKind;
import com.writelab.agent.AgentStepStatus;
import com.writelab.feedback.IssueCategory;
import com.writelab.retrieval.GroundingMaterial;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationCitationsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void numbersGroundingSourcesThenSuccessfulDictionaryLookups() {
        List<GroundingMaterial> materials = List.of(
                new GroundingMaterial(UUID.randomUUID(), IssueCategory.COHERENCE,
                        "用连接词显式标明逻辑关系。", "WriteLab 自建依据材料", "CC BY 4.0"),
                new GroundingMaterial(UUID.randomUUID(), IssueCategory.VOCABULARY,
                        "选近义词要核对语域。", "WriteLab 自建依据材料", "CC BY 4.0"));
        List<AgentStep> steps = List.of(
                toolStep("lookup_word", AgentStepStatus.SUCCESS, """
                        {"status":"SUCCESS","word":"moreover","phonetic":"/mɔːrˈoʊvər/","partOfSpeech":"副词","meaningZh":"此外；而且。","exampleEn":"The plan is affordable; moreover, it is easy to implement."}
                        """),
                toolStep("lookup_word", AgentStepStatus.NOT_FOUND, "{\"status\":\"NOT_FOUND\"}"));

        List<ConversationCitation> available = ConversationCitations.available(materials, steps, objectMapper);

        assertThat(available).extracting(ConversationCitation::reference).containsExactly(1, 2, 3);
        assertThat(available.get(0).kind()).isEqualTo(ConversationCitationKind.GROUNDING_MATERIAL);
        assertThat(available.get(1).kind()).isEqualTo(ConversationCitationKind.GROUNDING_MATERIAL);
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
    }

    private static AgentStep toolStep(String toolName, AgentStepStatus status, String output) {
        return new AgentStep(1, AgentStepKind.TOOL_CALL, toolName, Map.of(), status, output, 0L);
    }
}
