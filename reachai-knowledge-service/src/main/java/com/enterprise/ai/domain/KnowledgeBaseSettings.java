package com.enterprise.ai.domain;

import com.enterprise.ai.domain.entity.KnowledgeBase;
import java.util.Locale;

/** Shared Knowledge Base configuration semantics for catalog commands and retrieval execution. */
public final class KnowledgeBaseSettings {
    private KnowledgeBaseSettings() { }

    public static final String SOURCE_LOCAL = "LOCAL";
    public static final String SOURCE_EXTERNAL = "EXTERNAL";

    /** External knowledge bases keep their content in another system and are searched through a connector. */
    public static boolean isExternal(KnowledgeBase kb) {
        return kb != null && SOURCE_EXTERNAL.equalsIgnoreCase(kb.getSourceType());
    }

    /** File upload, chunk import and re-embedding only exist for knowledge bases stored in ReachAI. */
    public static void requireLocal(KnowledgeBase kb) {
        if (isExternal(kb)) {
            throw new IllegalArgumentException("这是外部知识库，内容请在外部系统里维护，这里不能上传文件或修改段落");
        }
    }

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
