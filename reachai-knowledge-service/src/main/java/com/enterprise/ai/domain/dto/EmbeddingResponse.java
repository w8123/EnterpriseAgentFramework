package com.enterprise.ai.domain.dto;

import lombok.Data;

import java.util.List;

@Data
public class EmbeddingResponse {

    /** 向量结果列表，与输入文本一一对应 */
    private List<List<Float>> embeddings;

    /** 使用的模型 */
    private String model;

    /** token 使用量 */
    private Integer totalTokens;
}
