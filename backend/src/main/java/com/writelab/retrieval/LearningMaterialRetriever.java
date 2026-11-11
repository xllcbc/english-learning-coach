package com.writelab.retrieval;

import java.util.List;
import java.util.Map;

/**
 * The single application-side retrieval seam. This ticket implements Corpus Sentence lookup only;
 * Grounding Material lookup joins the interface with its own ticket. Callers live in the
 * application service layer, never inside a model adapter.
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
}
