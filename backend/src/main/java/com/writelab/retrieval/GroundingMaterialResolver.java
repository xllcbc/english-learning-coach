package com.writelab.retrieval;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.LearningIssue;
import com.writelab.feedback.StructuredFeedback;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the Grounding Material a Teacher Explanation may cite, together with the Retrieval Hits
 * that describe what happened. Empty or failed retrieval yields no material and one skipped hit, so
 * the explanation degrades to citing nothing rather than inventing a source. The caller owns
 * persistence; this class owns retrieval vocabulary.
 */
public class GroundingMaterialResolver {

    private static final Logger log = LoggerFactory.getLogger(GroundingMaterialResolver.class);

    private final LearningMaterialRetriever retriever;

    public GroundingMaterialResolver(LearningMaterialRetriever retriever) {
        this.retriever = retriever;
    }

    public GroundingResult resolve(StructuredFeedback feedback) {
        List<IssueCategory> categories = focusedCategories(feedback);
        if (categories.isEmpty()) {
            return new GroundingResult(List.of(), List.of());
        }

        List<RankedGroundingMaterial> rankedMaterials;
        String skipReason = null;
        try {
            rankedMaterials = retriever.groundingMaterials(categories, focusedQuery(feedback));
        } catch (RuntimeException exception) {
            log.warn("依据材料检索失败，本次讲解不引用依据: categories={}, cause={}",
                    categories, exception.getClass().getSimpleName());
            rankedMaterials = List.of();
            skipReason = RetrievalHit.RETRIEVAL_FAILED;
        }

        if (rankedMaterials.isEmpty()) {
            return new GroundingResult(List.of(), List.of(RetrievalHit.groundingMaterial(
                    null, null, skipReason == null ? RetrievalHit.NO_GROUNDING_MATERIAL : skipReason)));
        }

        List<GroundingMaterial> materials = new ArrayList<>(rankedMaterials.size());
        List<RetrievalHit> hits = new ArrayList<>(rankedMaterials.size());
        for (int index = 0; index < rankedMaterials.size(); index++) {
            RankedGroundingMaterial ranked = rankedMaterials.get(index);
            materials.add(ranked.material());
            hits.add(RetrievalHit.groundingMaterial(
                    ranked.material().id(), index + 1, ranked.similarityScore(), null));
        }
        return new GroundingResult(materials, hits);
    }

    /**
     * The focused Learning Issues are the semantic query for the vector implementation. The
     * deterministic implementation ignores it, so this text never reaches a prompt or a log.
     */
    private static String focusedQuery(StructuredFeedback feedback) {
        return feedback.focusedIssues().stream()
                .map(issue -> String.join("\n",
                        issue.originalSentence(), issue.suggestion(), issue.explanation()))
                .collect(Collectors.joining("\n"));
    }

    private static List<IssueCategory> focusedCategories(StructuredFeedback feedback) {
        Set<IssueCategory> categories = new LinkedHashSet<>();
        for (LearningIssue issue : feedback.focusedIssues()) {
            categories.add(issue.category());
        }
        return List.copyOf(categories);
    }

    /**
     * Citable material plus the Retrieval Hits recording it. The reference number a citation uses is
     * its position in {@code materials}, one-based.
     */
    public record GroundingResult(
            List<GroundingMaterial> materials,
            List<RetrievalHit> hits
    ) {
    }
}
