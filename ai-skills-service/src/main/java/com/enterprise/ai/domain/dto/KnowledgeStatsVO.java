package com.enterprise.ai.domain.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class KnowledgeStatsVO {
    private String knowledgeBaseCode;
    private Integer fileCount;
    private Integer chunkCount;
    private Integer questionCount;
    private Integer tagCount;
    private Integer hitCount;
    private Integer activeChunkCount;
}
