package com.enterprise.ai.retrieval;

import com.enterprise.ai.domain.dto.RetrievalTestRequest;
import com.enterprise.ai.domain.dto.RetrievalTestResponse;

/**
 * Internal engine contract for production retrieval execution.
 * Admin {@code retrievalTest} and {@link KnowledgeRetrievalCore} use the same retrieval implementation.
 * Implementations do not own catalog, document or chunk management commands.
 */
public interface KnowledgeRetrievalEngine {

    RetrievalTestResponse execute(RetrievalTestRequest request);
}
