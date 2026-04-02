package com.enterprise.ai.domain.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class EmbeddingRequest {

    /** 待向量化的文本列表 */
    @NotEmpty(message = "文本列表不能为空")
    private List<String> texts;
}
