package com.enterprise.ai.retrieval;

import com.enterprise.ai.domain.dto.RetrievalTestRequest;
import com.enterprise.ai.domain.dto.RetrievalTestResponse;

/**
 * Internal engine contract for production retrieval execution.
 * Kept package-facing so admin {@code retrievalTest} and {@link KnowledgeRetrievalCore} share one implementation.
 */
public interface KnowledgeRetrievalEngine {

    RetrievalTestResponse execute(RetrievalTestRequest request);
}
