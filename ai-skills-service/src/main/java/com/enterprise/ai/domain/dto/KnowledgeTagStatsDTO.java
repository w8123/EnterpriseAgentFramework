package com.enterprise.ai.domain.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class KnowledgeTagStatsDTO {
    private String tagKey;
    private String tagValue;
    private String tagGroup;
    private String color;
    private String description;
    private Long parentId;
    private Integer sortOrder;
    private Integer totalCount;
    private Integer knowledgeCount;
    private Integer fileCount;
    private Integer chunkCount;
}
