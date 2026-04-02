package com.enterprise.ai.rag;

/**
 * LLM 调用服务接口。
 * <p>扩展点：可替换为 OpenAI、文心一言、Llama 等不同模型。</p>
 */
public interface LlmService {

    /**
     * 根据 prompt 调用大模型并返回生成文本
     */
    String chat(String prompt);

    /**
     * 返回当前模型标识
     */
    String getModelName();
}
