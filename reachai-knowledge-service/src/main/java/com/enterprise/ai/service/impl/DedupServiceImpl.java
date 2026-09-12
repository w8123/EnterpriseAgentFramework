package com.enterprise.ai.service.impl;

import com.enterprise.ai.domain.dto.DedupRequest;
import com.enterprise.ai.domain.dto.DedupResponse;
import com.enterprise.ai.retrieval.KnowledgeRetrievalCore;
import com.enterprise.ai.retrieval.KnowledgeRetrievalCoreRequest;
import com.enterprise.ai.service.DedupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DedupServiceImpl implements DedupService {
    private final KnowledgeRetrievalCore retrieval;

    @Override
    public DedupResponse check(DedupRequest request) {
        var result = retrieval.retrieve(KnowledgeRetrievalCoreRequest.builder()
                .query(request.getText()).knowledgeBaseCodes(request.getKnowledgeBaseCodes()).userId(request.getUserId())
                .topK(request.getTopK() != null ? request.getTopK() : 10)
                .scoreThreshold(request.getScoreThreshold() != null ? request.getScoreThreshold() : 0.7f)
                .searchMode("vector").rerankEnabled(false).directReturnEnabled(false).recordHit(false).build());
        return DedupResponse.of(result.toSimilarItems());
    }
}
