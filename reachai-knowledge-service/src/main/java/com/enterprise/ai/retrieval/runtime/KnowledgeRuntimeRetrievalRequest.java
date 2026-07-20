package com.enterprise.ai.retrieval.runtime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Runtime-oriented retrieval request for Workflow KNOWLEDGE_RETRIEVAL nodes.
 */
public record KnowledgeRuntimeRetrievalRequest(
        @NotBlank String query,
        @NotEmpty List<String> knowledgeBaseCodes,
        String userId,
        Integer topK,
        Float similarityThreshold,
        String searchMode,
        Boolean rerankEnabled
) {
}
