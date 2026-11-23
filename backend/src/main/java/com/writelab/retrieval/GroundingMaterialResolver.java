package com.writelab.retrieval;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.LearningIssue;
import com.writelab.feedback.StructuredFeedback;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
        return resolve(feedback, null);
    }

    /**
     * Resolves Grounding Material with a query vector that the explanation orchestrator already
     * built when available. When the shared vector could not be built, a semantic retriever falls
     * back to its own existing path: Grounding Material must not be dropped merely because the
     * optional User Document lookup failed.
     */
    public GroundingResult resolve(StructuredFeedback feedback, float[] queryVector) {
        List<IssueCategory> categories = focusedCategories(feedback);
        if (categories.isEmpty()) {
            return new GroundingResult(List.of(), List.of());
        }

        List<RankedGroundingMaterial> rankedMaterials;
        String skipReason = null;
        try {
            rankedMaterials = retriever.groundingMaterials(
                    categories, FocusedIssueQuery.text(feedback), queryVector);
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
