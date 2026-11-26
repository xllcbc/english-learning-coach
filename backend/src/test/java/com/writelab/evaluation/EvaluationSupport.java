package com.writelab.evaluation;

import com.writelab.feedback.IssueCategory;
import com.writelab.retrieval.GroundingMaterial;
import com.writelab.retrieval.LearningMaterialRetriever;
import com.writelab.retrieval.RankedCorpusSentence;
import com.writelab.retrieval.RankedGroundingMaterial;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared fixtures for the on-demand gate evaluations: reading an annotated case set, formatting
 * percentages, deterministic Grounding Material, and the Practice Attempt rows that Learning Review
 * and Targeted Practice both consume.
 */
public final class EvaluationSupport {

    private EvaluationSupport() {
    }

    public static <T> List<T> readCases(
            ObjectMapper objectMapper,
            String resource,
            Class<T[]> type
    ) throws Exception {
        try (InputStream stream = EvaluationSupport.class.getResourceAsStream(resource)) {
            assertThat(stream).as("evaluation cases resource %s", resource).isNotNull();
            return List.of(objectMapper.readValue(stream, type));
        }
    }

    public static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    public static LearningMaterialRetriever stubMaterials() {
        return new StubMaterials();
    }

    public record AttemptFixture(int hour, String level, List<IssueFixture> issues) {

        public AttemptFixture {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    public record IssueFixture(String category, String severity) {
    }

    private static final class StubMaterials implements LearningMaterialRetriever {

        @Override
        public Map<String, List<RankedCorpusSentence>> contextualExamples(
                Map<String, String> learnerSentences
        ) {
            return new LinkedHashMap<>();
        }

        @Override
        public List<RankedGroundingMaterial> groundingMaterials(
                Collection<IssueCategory> categories,
                String queryText
        ) {
            List<RankedGroundingMaterial> materials = new ArrayList<>();
            for (IssueCategory category : categories) {
                materials.add(RankedGroundingMaterial.fixed(new GroundingMaterial(
                        UUID.nameUUIDFromBytes(category.name().getBytes(StandardCharsets.UTF_8)),
                        category,
                        category + " 依据材料",
                        "WriteLab 自建依据材料",
                        "CC BY 4.0")));
            }
            return materials;
        }
    }
}
