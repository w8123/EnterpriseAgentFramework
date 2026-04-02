package com.enterprise.ai.embedding;

import java.util.List;

/**
 * Embedding 服务接口 — 将文本转换为向量。
 * <p>扩展点：实现不同的 Embedding 模型（通义、OpenAI、BGE 等）。</p>
 */
public interface EmbeddingService {

    /**
     * 单文本向量化
     */
    List<Float> embed(String text);

    /**
     * 批量文本向量化
     */
    List<List<Float>> embedBatch(List<String> texts);

    /**
     * 返回当前模型标识
     */
    String getModelName();

    /**
     * 返回向量维度
     */
    int getDimension();
}
