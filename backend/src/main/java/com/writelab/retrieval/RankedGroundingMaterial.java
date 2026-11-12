package com.writelab.retrieval;

/**
 * A Grounding Material candidate together with the cosine similarity that ranked it. A null score
 * means the deterministic implementation produced a fixed order instead of a similarity ranking.
 */
public record RankedGroundingMaterial(GroundingMaterial material, Double similarityScore) {

    public static RankedGroundingMaterial fixed(GroundingMaterial material) {
        return new RankedGroundingMaterial(material, null);
    }

    public static RankedGroundingMaterial semantic(GroundingMaterial material, double similarityScore) {
        return new RankedGroundingMaterial(material, similarityScore);
    }
}
