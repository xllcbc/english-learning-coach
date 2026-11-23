package com.writelab.retrieval;

import com.writelab.feedback.IssueCategory;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The single application-side retrieval seam. Callers live in the application service layer, never
 * inside a model adapter.
 */
public interface LearningMaterialRetriever {

    /**
     * Looks up ordered Corpus Sentence candidates for each word form.
     *
     * @param learnerSentences normalized word form to the learner's own sentence containing it;
     *                         the deterministic implementation ranks by fixed order and ignores it,
     *                         the vector implementation uses it as the similarity query
     * @return candidates per word form, already ordered; word forms without candidates are absent
     */
    Map<String, List<RankedCorpusSentence>> contextualExamples(Map<String, String> learnerSentences);

    /**
     * Looks up the Grounding Material a Teacher Explanation may cite for the Learning Issue
     * categories in focus.
     *
     * @param categories focused categories; both implementations filter by them and ignore their order
     * @param queryText  the focused Learning Issues' text; the deterministic implementation ignores it,
     *                   the vector implementation uses it as the similarity query
     * @return cited material in relevance order, capped by configuration; empty when nothing clears
     *         the relevance threshold, which means the explanation must not cite anything
     */
    List<RankedGroundingMaterial> groundingMaterials(Collection<IssueCategory> categories, String queryText);

    /**
     * The shared-query-vector variant used by the explanation orchestrator. The deterministic
     * implementation ignores the vector; the semantic implementation ranks with it instead of
     * embedding the query text a second time.
     *
     * @param queryVector the vector already built from {@code queryText}, or {@code null} when the
     *                    caller has no embedding client (Mock mode)
     */
    default List<RankedGroundingMaterial> groundingMaterials(
            Collection<IssueCategory> categories, String queryText, float[] queryVector) {
        return groundingMaterials(categories, queryText);
    }
}
