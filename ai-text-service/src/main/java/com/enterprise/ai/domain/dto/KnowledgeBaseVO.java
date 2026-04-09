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
    private String embeddingModel;
    private Integer dimension;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private String splitType;
    private Integer status;
    private Integer fileCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
