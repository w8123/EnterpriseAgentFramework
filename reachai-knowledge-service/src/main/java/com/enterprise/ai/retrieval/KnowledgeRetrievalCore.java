package com.enterprise.ai.retrieval;

import com.enterprise.ai.domain.dto.RetrievalTestRequest;
import com.enterprise.ai.domain.dto.RetrievalTestResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * User-authorized Knowledge retrieval entry for Runtime, RAG and duplicate search.
 * Administrative inspection shares the engine through its separately authorized catalog entry.
 * Runtime Internal API must call this Core — not {@code retrievalTest} / RetrievalTestRequest.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalCore {

    private final KnowledgeRetrievalEngine engine;
    private final KnowledgeRetrievalAuthorization authorization;

    public KnowledgeRetrievalCoreResponse retrieve(KnowledgeRetrievalCoreRequest request) {
        if (request == null) throw new IllegalArgumentException("检索请求不能为空");
        var snapshot = authorization.capture(request.getUserId());
        if (snapshot.grants().isEmpty()) {
            return KnowledgeRetrievalCoreResponse.builder().query(request.getQuery()).searchMode(request.getSearchMode())
                    .totalResults(0).costMs(0L).directReturn(false).items(List.of()).diagnostics(Map.of())
                    .accessSnapshot(snapshot).build();
        }
        var internal = toInternal(request);
        internal.setAccessSnapshot(snapshot);
        internal.setAccessibleFileIds(request.getAccessibleFileIds() == null ? snapshot.fileIds()
                : snapshot.fileIds().stream().filter(request.getAccessibleFileIds()::contains).toList());
        internal.setFileIdFilterExpression(authorization.filter(snapshot));
        RetrievalTestResponse response = engine.execute(internal);
        var result = toCore(response);
        result.setAccessSnapshot(snapshot);
        return result;
    }

    /** RAG uses the same content-release check immediately before and after its model call. */
    public boolean isCurrent(KnowledgeRetrievalCoreResponse response) {
        return response != null && authorization.isCurrent(response.getAccessSnapshot(), response.getItems());
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
                    .diagnostics(Map.of())
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
                .diagnostics(response.getDiagnostics() == null
                        ? Map.of()
                        : new LinkedHashMap<>(response.getDiagnostics()))
                .build();
    }
}
