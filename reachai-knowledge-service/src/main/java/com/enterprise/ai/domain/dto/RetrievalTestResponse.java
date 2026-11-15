package com.enterprise.ai.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 检索测试响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalTestResponse {

    private String query;
    private String searchMode;
    private Integer totalResults;
    private Long costMs;
    private List<RetrievalItem> items;
    /**
     * Non-sensitive retrieval counters and stage timings. Query text, hit content,
     * file names and user identity must never be added to this map.
     */
    private Map<String, Object> diagnostics;

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
        private String reason;
        /** RAGFLOW / IMA / DIFY when the passage came from an external knowledge base; null for local chunks. */
        private String externalProvider;
        /** Knowledge base row of an external passage, for the content-release check; never serialized. */
        @com.fasterxml.jackson.annotation.JsonIgnore
        private Long externalKnowledgeBaseId;
    }
}
