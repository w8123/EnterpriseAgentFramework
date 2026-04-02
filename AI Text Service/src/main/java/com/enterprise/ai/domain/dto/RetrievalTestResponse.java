package com.enterprise.ai.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 检索测试响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalTestResponse {

    private String query;
    private Integer totalResults;
    private Long costMs;
    private List<RetrievalItem> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RetrievalItem {
        private String chunkId;
        private String content;
        private Float score;
        private String fileName;
        private String fileId;
        private String knowledgeBaseCode;
        private Integer chunkIndex;
    }
}
