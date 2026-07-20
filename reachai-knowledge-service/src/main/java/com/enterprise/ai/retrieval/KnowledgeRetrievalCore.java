package com.enterprise.ai.retrieval;

import com.enterprise.ai.domain.dto.RetrievalTestRequest;
import com.enterprise.ai.domain.dto.RetrievalTestResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Formal production Knowledge retrieval entry for Runtime and admin reuse.
 * Runtime Internal API must call this Core — not {@code retrievalTest} / RetrievalTestRequest.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalCore {

    private final KnowledgeRetrievalEngine engine;

    public KnowledgeRetrievalCoreResponse retrieve(KnowledgeRetrievalCoreRequest request) {
        RetrievalTestResponse response = engine.execute(toInternal(request));
        return toCore(response);
    }

    private static RetrievalTestRequest toInternal(KnowledgeRetrievalCoreRequest request) {
        RetrievalTestRequest internal = new RetrievalTestRequest();
        if (request == null) {
            return internal;
        }
        internal.setQuery(request.getQuery());
        internal.setKnowledgeBaseCodes(request.getKnowledgeBaseCodes());
        internal.setTopK(request.getTopK());
        internal.setScoreThreshold(request.getScoreThreshold());
        internal.setSearchMode(request.getSearchMode());
        internal.setRerankEnabled(request.getRerankEnabled());
        internal.setDirectReturnEnabled(request.getDirectReturnEnabled());
        internal.setDirectReturnThreshold(request.getDirectReturnThreshold());
        internal.setVectorWeight(request.getVectorWeight());
        internal.setKeywordWeight(request.getKeywordWeight());
        internal.setRecordHit(request.getRecordHit());
        internal.setTraceId(request.getTraceId());
        internal.setUserId(request.getUserId());
        internal.setAccessibleFileIds(request.getAccessibleFileIds());
        internal.setFileIdFilterExpression(request.getFileIdFilterExpression());
        return internal;
    }

    private static KnowledgeRetrievalCoreResponse toCore(RetrievalTestResponse response) {
        if (response == null) {
            return KnowledgeRetrievalCoreResponse.builder()
                    .query("")
                    .searchMode("hybrid")
                    .totalResults(0)
                    .costMs(0L)
                    .directReturn(false)
                    .items(List.of())
                    .build();
        }
        List<KnowledgeRetrievalCoreResponse.RetrievalItem> items = new ArrayList<>();
        if (response.getItems() != null) {
            for (RetrievalTestResponse.RetrievalItem item : response.getItems()) {
                if (item == null) {
                    continue;
                }
                items.add(KnowledgeRetrievalCoreResponse.RetrievalItem.builder()
                        .chunkId(item.getChunkId())
                        .chunkDbId(item.getChunkDbId())
                        .content(item.getContent())
                        .score(item.getScore())
                        .vectorScore(item.getVectorScore())
                        .keywordScore(item.getKeywordScore())
                        .rerankScore(item.getRerankScore())
                        .fileName(item.getFileName())
                        .fileId(item.getFileId())
                        .knowledgeBaseCode(item.getKnowledgeBaseCode())
                        .chunkIndex(item.getChunkIndex())
                        .hitCount(item.getHitCount())
                        .directReturn(item.getDirectReturn())
                        .reason(item.getReason())
                        .build());
            }
        }
        return KnowledgeRetrievalCoreResponse.builder()
                .query(response.getQuery())
                .searchMode(response.getSearchMode())
                .totalResults(response.getTotalResults())
                .costMs(response.getCostMs())
                .directReturn(response.getDirectReturn())
                .directReturnContent(response.getDirectReturnContent())
                .items(items)
                .build();
    }
}
