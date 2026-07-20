package com.enterprise.ai.retrieval.runtime;

import java.util.Map;

/**
 * Runtime-oriented retrieval hit returned to Workflow execution.
 */
public record KnowledgeRuntimeRetrievalHit(
        String id,
        String chunkId,
        String knowledgeBaseCode,
        String title,
        String source,
        String content,
        Float score,
        Map<String, Object> metadata
) {
}
