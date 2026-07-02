package com.enterprise.ai.domain.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库信息视图对象
 */
@Data
public class KnowledgeBaseVO {
    private Long id;
    private String name;
    private String code;
    private String description;
    private String embeddingModelInstanceId;
    private String rerankModelInstanceId;
    private String llmModelInstanceId;
    private String workspaceId;
    private String projectCode;
    private String scope;
    private Integer dimension;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private String splitType;
    private String searchMode;
    private Integer topK;
    private Float similarityThreshold;
    private Boolean directReturnEnabled;
    private Float directReturnThreshold;
    private Boolean rerankEnabled;
    private Float vectorWeight;
    private Float keywordWeight;
    private Integer status;
    private Integer fileCount;
    private Integer chunkCount;
    private Integer questionCount;
    private Integer tagCount;
    private Integer hitCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
