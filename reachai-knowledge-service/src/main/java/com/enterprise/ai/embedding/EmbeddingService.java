package com.enterprise.ai.embedding;

import java.util.List;

public interface EmbeddingService {

    default List<Float> embed(String text) {
        throw new IllegalStateException("modelInstanceId is required for embedding");
    }

    List<Float> embed(String modelInstanceId, String text);

    default List<List<Float>> embedBatch(List<String> texts) {
        throw new IllegalStateException("modelInstanceId is required for embedding");
    }

    List<List<Float>> embedBatch(String modelInstanceId, List<String> texts);

    default String getModelName() {
        throw new IllegalStateException("modelInstanceId is required for embedding");
    }

    default int getDimension() {
        throw new IllegalStateException("modelInstanceId is required for embedding");
    }
}
