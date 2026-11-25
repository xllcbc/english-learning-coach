package com.writelab.tools;

import com.writelab.retrieval.RankedDocumentChunk;
import com.writelab.retrieval.UserDocumentRetriever;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Exposes User Document search as a model-driven tool. The tool searches the learner's own READY
 * documents by the query the model supplies and returns citable chunks; retrieval failure degrades
 * to a failed observation instead of a fabricated source.
 */
public final class SearchUserDocumentsToolDefinition implements ToolDefinition {

    public static final String NAME = "search_user_documents";
    public static final String DESCRIPTION = """
            Search the learner's own uploaded User Documents. Pass {"query": "..."} with the topic \
            or sentence to look for. Cite each returned source by its reference number.""";

    private static final String KIND = "USER_DOCUMENT";
    private static final String SOURCE = "我的文档";

    private final UserDocumentRetriever documentRetriever;
    private final ObjectMapper objectMapper;

    public SearchUserDocumentsToolDefinition(
            UserDocumentRetriever documentRetriever,
            ObjectMapper objectMapper
    ) {
        this.documentRetriever = documentRetriever;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolSession openSession() {
        return openSession(ToolTurnContext.empty());
    }

    @Override
    public ToolSession openSession(ToolTurnContext context) {
        return arguments -> {
            String query = ToolArguments.text(arguments, "query");
            if (query.isBlank()) {
                return CitableSourcesObservation.success(List.of(), objectMapper);
            }
            try {
                List<RankedDocumentChunk> chunks = documentRetriever.retrieve(query);
                if (chunks.isEmpty()) {
                    return CitableSourcesObservation.success(List.of(), objectMapper);
                }
                int firstReference = context.references().reserve(chunks.size());
                List<CitableSourcesObservation.CitableToolSource> sources =
                        new ArrayList<>(chunks.size());
                for (int index = 0; index < chunks.size(); index++) {
                    RankedDocumentChunk chunk = chunks.get(index);
                    sources.add(new CitableSourcesObservation.CitableToolSource(
                            firstReference + index,
                            KIND,
                            chunk.title(),
                            chunk.content(),
                            SOURCE,
                            null));
                }
                return CitableSourcesObservation.success(sources, objectMapper);
            } catch (RuntimeException exception) {
                return CitableSourcesObservation.failed(
                        "{\"status\":\"FAILED\",\"sources\":[],\"message\":\"我的文档检索失败。\"}");
            }
        };
    }
}
