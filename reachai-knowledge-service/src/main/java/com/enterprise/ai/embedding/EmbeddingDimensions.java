package com.enterprise.ai.embedding;

/** Resolves a collection's dimension before any metadata or remote collection is created. */
public final class EmbeddingDimensions {
    private EmbeddingDimensions() {}

    public static int resolve(Integer requested, String modelInstanceId, EmbeddingService embeddings) {
        if (requested != null) {
            if (requested < 1) {
                throw new IllegalArgumentException("向量维度必须大于零");
            }
            return requested;
        }
        var vector = embeddings.embed(modelInstanceId, "ReachAI collection dimension probe");
        if (vector == null || vector.isEmpty()
                || vector.stream().anyMatch(value -> value == null || !Float.isFinite(value))) {
            throw new IllegalArgumentException("所选 Embedding 模型未返回有效向量，请先测试模型实例");
        }
        return vector.size();
    }
}
