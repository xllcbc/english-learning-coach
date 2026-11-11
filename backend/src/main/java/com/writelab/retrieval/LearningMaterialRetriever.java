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
     *                         the deterministic implementation ranks by fixed order and ignores it
     * @return candidates per word form, already ordered; word forms without candidates are absent
     */
    Map<String, List<CorpusSentence>> contextualExamples(Map<String, String> learnerSentences);

    /**
     * Looks up the Grounding Material a Teacher Explanation may cite for the Learning Issue
     * categories in focus.
     *
     * @param categories focused categories; the deterministic implementation filters by them and
     *                   ignores their order
     * @return cited material in fixed order, capped by configuration; empty when nothing clears the
     *         relevance threshold, which means the explanation must not cite anything
     */
    List<GroundingMaterial> groundingMaterials(Collection<IssueCategory> categories);
}
