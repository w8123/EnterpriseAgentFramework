package com.enterprise.ai.model.provider;

import com.enterprise.ai.model.service.ChatRequest;
import com.enterprise.ai.model.service.ChatResponse;
import com.enterprise.ai.model.service.EmbeddingResponse;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 模型提供商统一抽象。每个 Provider（通义、OpenAI、极视角等）实现此接口。
 */
public interface ModelProvider {

    /** Provider 标识（tongyi / openai / jishi） */
    String getName();

    /** 同步对话 */
    ChatResponse chat(ChatRequest request);

    /** 流式对话（SSE），返回 Flux 逐 token 推送 */
    Flux<String> chatStream(ChatRequest request);

    /** 批量 Embedding */
    EmbeddingResponse embed(List<String> texts, String model);

    /** 该 Provider 可用的模型列表 */
    List<String> listModels();

    /** 连通性测试 */
    boolean test();
}
