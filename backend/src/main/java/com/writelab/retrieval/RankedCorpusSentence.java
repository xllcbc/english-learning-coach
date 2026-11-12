package com.writelab.retrieval;

/**
 * A Corpus Sentence candidate together with the cosine similarity that ranked it. A null score
 * means the deterministic implementation produced a fixed order instead of a similarity ranking.
 */
public record RankedCorpusSentence(CorpusSentence corpusSentence, Double similarityScore) {

    public static RankedCorpusSentence fixed(CorpusSentence corpusSentence) {
        return new RankedCorpusSentence(corpusSentence, null);
    }

    public static RankedCorpusSentence semantic(CorpusSentence corpusSentence, double similarityScore) {
        return new RankedCorpusSentence(corpusSentence, similarityScore);
    }
}
