package com.enterprise.ai.retrieval.runtime;

import java.util.List;

/**
 * Runtime-oriented retrieval response for Workflow KNOWLEDGE_RETRIEVAL nodes.
 */
public record KnowledgeRuntimeRetrievalResponse(
        String query,
        List<KnowledgeRuntimeRetrievalHit> hits,
        Integer hitCount
) {
}
