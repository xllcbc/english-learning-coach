package com.writelab.tools;

import com.writelab.feedback.IssueCategory;
import com.writelab.retrieval.GroundingMaterial;
import com.writelab.retrieval.LearningMaterialRetriever;
import com.writelab.retrieval.RankedDocumentChunk;
import com.writelab.retrieval.RankedGroundingMaterial;
import com.writelab.retrieval.UserDocumentRetriever;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalToolDefinitionsTest {

    private static final UUID MATERIAL_ID = UUID.randomUUID();
    private static final UUID DOCUMENT_ID = UUID.randomUUID();
    private static final UUID CHUNK_ID = UUID.randomUUID();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LearningMaterialRetriever materialRetriever = mock(LearningMaterialRetriever.class);
    private final UserDocumentRetriever documentRetriever = mock(UserDocumentRetriever.class);

    @Test
    void retrievesGroundingMaterialByQueryAndAllocatesTheNextCitableReference() throws Exception {
        when(materialRetriever.groundingMaterials(
                any(Collection.class), eq("为什么用 although")))
                .thenReturn(List.of(
                        RankedGroundingMaterial.fixed(new GroundingMaterial(
                                MATERIAL_ID, IssueCategory.COHERENCE,
                                "用连接词显式标明逻辑关系。", "WriteLab 自建依据材料", "CC BY 4.0")),
                        RankedGroundingMaterial.fixed(new GroundingMaterial(
                                UUID.randomUUID(), IssueCategory.GRAMMAR,
                                "主谓一致：谓语动词要与主语一致。", "WriteLab 自建依据材料", "CC BY 4.0"))));
        ToolRegistry registry = new ToolRegistry(List.of(
                new RetrieveGroundingMaterialToolDefinition(materialRetriever, objectMapper)));

        ToolTurnContext turnContext = new ToolTurnContext(3);
        ToolResult first = registry.find(RetrieveGroundingMaterialToolDefinition.NAME).orElseThrow()
                .openSession(turnContext)
                .invoke(Map.of("query", "为什么用 although"));
        ToolResult second = registry.find(RetrieveGroundingMaterialToolDefinition.NAME).orElseThrow()
                .openSession(turnContext)
                .invoke(Map.of("query", "为什么用 although"));

        assertThat(first.status()).isEqualTo(ToolInvocationStatus.SUCCESS);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(first.observation(), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) payload.get("sources");
        assertThat(sources).hasSize(2);
        assertThat(sources.getFirst())
                .containsEntry("reference", 3)
                .containsEntry("kind", "GROUNDING_MATERIAL")
                .containsEntry("title", "连贯性")
                .containsEntry("content", "用连接词显式标明逻辑关系。")
                .containsEntry("source", "WriteLab 自建依据材料")
                .containsEntry("license", "CC BY 4.0");
        assertThat(sources.get(1))
                .containsEntry("reference", 4)
                .containsEntry("title", "语法")
                .containsEntry("content", "主谓一致：谓语动词要与主语一致。");

        @SuppressWarnings("unchecked")
        Map<String, Object> secondPayload = objectMapper.readValue(second.observation(), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> secondSources = (List<Map<String, Object>>) secondPayload.get("sources");
        assertThat(secondSources).extracting(source -> source.get("reference")).containsExactly(5, 6);
    }

    @Test
    void defaultsToEveryCategoryWhenTheModelDoesNotChooseAny() {
        when(materialRetriever.groundingMaterials(any(Collection.class), anyString()))
                .thenReturn(List.of());
        ToolResult result = new RetrieveGroundingMaterialToolDefinition(materialRetriever, objectMapper)
                .openSession(new ToolTurnContext(1))
                .invoke(Map.of("query", "任意问题"));

        assertThat(result.status()).isEqualTo(ToolInvocationStatus.NOT_FOUND);
        assertThat(result.observation()).contains("\"sources\":[]");
    }

    @Test
    void searchesUserDocumentsByQueryAndCitesThemAsUserDocuments() throws Exception {
        when(documentRetriever.retrieve(eq("subject verb agreement"))).thenReturn(List.of(
                new RankedDocumentChunk(
                        CHUNK_ID, DOCUMENT_ID, "grammar.md", "Grammar > Agreement",
                        "I have a problem with this sentence.", 0.82)));

        ToolResult result = new SearchUserDocumentsToolDefinition(documentRetriever, objectMapper)
                .openSession(new ToolTurnContext(5))
                .invoke(Map.of("query", "subject verb agreement"));

        assertThat(result.status()).isEqualTo(ToolInvocationStatus.SUCCESS);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(result.observation(), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) payload.get("sources");
        assertThat(sources).singleElement().satisfies(source -> {
            assertThat(source).containsEntry("reference", 5);
            assertThat(source).containsEntry("kind", "USER_DOCUMENT");
            assertThat(source).containsEntry("title", "grammar.md · Grammar > Agreement");
            assertThat(source).containsEntry("content", "I have a problem with this sentence.");
            assertThat(source).containsEntry("source", "我的文档");
            assertThat(source.get("license")).isNull();
        });
    }

    @Test
    void reportsRetrievalFailureWithoutInventingASource() {
        when(documentRetriever.retrieve(anyString())).thenThrow(new IllegalStateException("离线"));

        ToolResult result = new SearchUserDocumentsToolDefinition(documentRetriever, objectMapper)
                .openSession(new ToolTurnContext(1))
                .invoke(Map.of("query", "任何查询"));

        assertThat(result.status()).isEqualTo(ToolInvocationStatus.FAILED);
        assertThat(result.observation()).doesNotContain("[依据");
    }
}
