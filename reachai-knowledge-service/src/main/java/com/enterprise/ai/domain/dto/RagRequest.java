package com.enterprise.ai.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class RagRequest {

    /** 用户问题 */
    @NotBlank(message = "问题不能为空")
    private String question;

    /** 指定知识库编码列表（为空则查询全部可用知识库） */
    private List<String> knowledgeBaseCodes;

    /** 用户ID（用于权限过滤） */
    @NotBlank(message = "用户ID不能为空")
    private String userId;

    /** 返回 TopK */
    private Integer topK = 5;

    /** 相似度阈值 */
    private Float scoreThreshold = 0.5f;
}
