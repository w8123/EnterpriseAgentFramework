package com.enterprise.ai.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 检索测试请求 DTO
 */
@Data
public class RetrievalTestRequest {

    @NotBlank(message = "查询内容不能为空")
    private String query;

    /** 目标知识库编码列表，为空则搜索所有已启用知识库 */
    private List<String> knowledgeBaseCodes;

    /** 返回的 topK 数量，默认5 */
    private Integer topK;

    /** 相似度分数阈值 */
    private Float scoreThreshold;
}
