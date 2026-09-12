package com.enterprise.ai.retrieval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Canonical production retrieval response shared by admin retrieval test and Runtime internal API.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeRetrievalCoreResponse {

    private String query;
    private String searchMode;
    private Integer totalResults;
    private Long costMs;
    private Boolean directReturn;
    private String directReturnContent;
    private List<RetrievalItem> items;
    /** Non-sensitive retrieval counters/timings for Runtime trace projection. */
    private Map<String, Object> diagnostics;

    @com.fasterxml.jackson.annotation.JsonIgnore
    private com.enterprise.ai.security.FileAccessSnapshot accessSnapshot;

    public List<com.enterprise.ai.domain.vo.SimilarItem> toSimilarItems() {
        return items == null ? List.of() : items.stream().map(item -> com.enterprise.ai.domain.vo.SimilarItem.builder()
                .chunkId(item.getChunkId()).fileId(item.getFileId()).fileName(item.getFileName())
                .content(item.getContent()).score(item.getScore()).knowledgeBaseCode(item.getKnowledgeBaseCode()).build()).toList();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RetrievalItem {
        private String chunkId;
        private Long chunkDbId;
        private String content;
        private Float score;
        private Float vectorScore;
        private Float keywordScore;
        private Float rerankScore;
        private String fileName;
        private String fileId;
        private String knowledgeBaseCode;
        private Integer chunkIndex;
        private Integer hitCount;
        private Boolean directReturn;
        private String reason;
    }
}
