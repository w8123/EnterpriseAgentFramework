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

    private String embeddingModel;

    private Integer dimension;
}
