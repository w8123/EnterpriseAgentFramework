package com.enterprise.ai.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class DedupRequest {

    /** 待查重文本 */
    @NotBlank(message = "查重文本不能为空")
    private String text;

    /** 指定知识库编码列表（为空则查询全部） */
    private List<String> knowledgeBaseCodes;

    /** 用户ID（用于权限过滤） */
    @NotBlank(message = "用户ID不能为空")
    private String userId;

    /** 返回 TopK 数量 */
    private Integer topK = 10;

    /** 相似度阈值（0-1） */
    private Float scoreThreshold = 0.7f;
}
