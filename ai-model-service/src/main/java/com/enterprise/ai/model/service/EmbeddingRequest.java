package com.enterprise.ai.model.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmbeddingRequest {

    /** 指定 Provider，为空时使用默认 */
    private String provider;

    /** 模型名称，为空时使用 Provider 默认模型 */
    private String model;

    /** 待向量化文本列表 */
    private List<String> texts;
}
