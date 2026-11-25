package com.writelab.tools;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueCategoryLabels;
import com.writelab.retrieval.GroundingMaterial;
import com.writelab.retrieval.LearningMaterialRetriever;
import com.writelab.retrieval.RankedGroundingMaterial;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Exposes Grounding Material retrieval as a model-driven tool. The model supplies the focused query
 * and, when it can, the Learning Issue categories; retrieval stays an application-side structured
 * action, not free-form browsing. Successful material reserves the turn's next citation numbers.
 */
public final class RetrieveGroundingMaterialToolDefinition implements ToolDefinition {

    public static final String NAME = "retrieve_grounding_material";
    public static final String DESCRIPTION = """
            Retrieve the Grounding Material rules that can ground an answer. Pass {"query": "..."} \
            and optionally {"categories": ["GRAMMAR", "VOCABULARY", "SENTENCE_STRUCTURE", \
            "COHERENCE", "PUNCTUATION"]}. Cite each returned source by its reference number.""";

    private static final String KIND = "GROUNDING_MATERIAL";

    private final LearningMaterialRetriever materialRetriever;
    private final ObjectMapper objectMapper;

    public RetrieveGroundingMaterialToolDefinition(
            LearningMaterialRetriever materialRetriever,
            ObjectMapper objectMapper
    ) {
        this.materialRetriever = materialRetriever;
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
                List<RankedGroundingMaterial> ranked =
                        materialRetriever.groundingMaterials(categories(arguments), query);
                if (ranked.isEmpty()) {
                    return CitableSourcesObservation.success(List.of(), objectMapper);
                }
                int firstReference = context.references().reserve(ranked.size());
                List<CitableSourcesObservation.CitableToolSource> sources =
                        new ArrayList<>(ranked.size());
                for (int index = 0; index < ranked.size(); index++) {
                    GroundingMaterial material = ranked.get(index).material();
                    sources.add(new CitableSourcesObservation.CitableToolSource(
                            firstReference + index,
                            KIND,
                            IssueCategoryLabels.label(material.category()),
                            material.content(),
                            material.source(),
                            material.license()));
                }
                return CitableSourcesObservation.success(sources, objectMapper);
            } catch (RuntimeException exception) {
                return CitableSourcesObservation.failed(
                        "{\"status\":\"FAILED\",\"sources\":[],\"message\":\"依据材料检索失败。\"}");
            }
        };
    }

    private static Collection<IssueCategory> categories(Map<String, Object> arguments) {
        Object raw = arguments.get("categories");
        if (raw == null) {
            return Arrays.asList(IssueCategory.values());
        }
        Set<IssueCategory> categories = new LinkedHashSet<>();
        if (raw instanceof Collection<?> values) {
            for (Object value : values) {
                categories.add(category(value));
            }
        } else {
            for (String value : raw.toString().split("[,\\s]+")) {
                if (!value.isBlank()) {
                    categories.add(category(value));
                }
            }
        }
        if (categories.isEmpty()) {
            throw new IllegalArgumentException("No retrieval categories supplied");
        }
        return categories;
    }

    private static IssueCategory category(Object raw) {
        String name = raw == null ? "" : raw.toString().strip().toUpperCase(Locale.ROOT);
        return IssueCategory.valueOf(name);
    }
}
