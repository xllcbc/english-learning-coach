package com.writelab.retrieval;

import java.util.List;

/**
 * Validation and literal-formatting for the 512-dimension embeddings shared by the retrieval
 * implementations. A malformed provider response must be rejected before it reaches a halfvec cast,
 * so the resolver can degrade to "no citation" with a single stable skip reason.
 */
public final class EmbeddingVectors {

    private EmbeddingVectors() {
    }

    public static float[] requireSingle(List<float[]> vectors) {
        if (vectors == null || vectors.size() != 1) {
            throw new IllegalStateException("embedding 返回的向量数量与输入不一致");
        }
        return requireDimensions(vectors.getFirst());
    }

    public static float[] requireDimensions(float[] vector) {
        if (vector == null || vector.length != EmbeddingClient.VECTOR_DIMENSIONS) {
            throw new IllegalStateException("embedding 返回的向量维度不是 " + EmbeddingClient.VECTOR_DIMENSIONS);
        }
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalStateException("embedding 返回了非有限数值");
            }
        }
        return vector;
    }

    public static String toLiteral(float[] vector) {
        StringBuilder literal = new StringBuilder(vector.length * 8).append('[');
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append(vector[index]);
        }
        return literal.append(']').toString();
    }
}
