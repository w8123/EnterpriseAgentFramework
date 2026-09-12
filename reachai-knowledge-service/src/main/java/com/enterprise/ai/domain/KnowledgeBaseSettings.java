package com.enterprise.ai.domain;

import com.enterprise.ai.domain.entity.KnowledgeBase;
import java.util.Locale;

/** Shared Knowledge Base configuration semantics for catalog commands and retrieval execution. */
public final class KnowledgeBaseSettings {
    private KnowledgeBaseSettings() { }

    public static String requireVectorCollectionName(KnowledgeBase kb) {
        if (kb == null || kb.getVectorCollectionName() == null || kb.getVectorCollectionName().isBlank()) {
            throw new IllegalStateException("知识库缺少物理向量集合身份");
        }
        return kb.getVectorCollectionName();
    }

    public static String normalizeSearchMode(String requested, String fallback) {
        String value = requested != null && !requested.isBlank() ? requested : fallback;
        if (value == null || value.isBlank()) {
            return "hybrid";
        }
        value = value.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "vector", "embedding" -> "vector";
            case "keyword", "keywords" -> "keyword";
            default -> "hybrid";
        };
    }

    public static String requireEmbeddingModelInstanceId(KnowledgeBase kb) {
        if (kb == null) {
            throw new IllegalArgumentException("knowledge base is required");
        }
        return requireEmbeddingModelInstanceId(kb.getEmbeddingModelInstanceId(), kb.getCode());
    }

    public static String requireEmbeddingModelInstanceId(String modelInstanceId, String owner) {
        if (modelInstanceId == null || modelInstanceId.isBlank()) {
            throw new IllegalArgumentException("embeddingModelInstanceId is required for " + owner);
        }
        return modelInstanceId.trim();
    }

    public static String requireLlmModelInstanceId(String modelInstanceId, String owner) {
        if (modelInstanceId == null || modelInstanceId.isBlank()) {
            throw new IllegalArgumentException("llmModelInstanceId is required for " + owner);
        }
        return modelInstanceId.trim();
    }


}
