package com.enterprise.ai.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 知识库创建/更新请求
 */
@Data
public class KnowledgeBaseRequest {

    @NotBlank(message = "知识库名称不能为空")
    private String name;

    @NotBlank(message = "知识库编码不能为空")
    private String code;

    private String description;

    @NotBlank(message = "embeddingModelInstanceId cannot be empty")
    private String embeddingModelInstanceId;

    private String rerankModelInstanceId;

    @NotBlank(message = "llmModelInstanceId cannot be empty")
    private String llmModelInstanceId;

    private String workspaceId;

    private String projectCode;

    private String scope;

    private Integer dimension;

    private String searchMode;

    private Integer topK;

    private Float similarityThreshold;

    private Boolean directReturnEnabled;

    private Float directReturnThreshold;

    private Boolean rerankEnabled;

    private Float vectorWeight;

    private Float keywordWeight;
}
