package com.enterprise.ai.agent.client;

import com.enterprise.ai.agent.client.ModelServiceClient.ModelChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/**
 * model-service 流式调用客户端
 * <p>
 * 使用 WebClient 消费 model-service 的 SSE 端点 /model/chat/stream。
 * Feign 不支持 SSE 流式响应，因此单独使用 WebClient 实现。
 */
@Slf4j
@Component
public class ModelStreamClient {

    private final WebClient webClient;

    public ModelStreamClient(@Value("${services.model-service.url:http://localhost:8601}") String modelServiceUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(modelServiceUrl)
                .build();
    }

    /**
     * 流式对话 — 返回逐 token 的文本片段
     */
    public Flux<String> chatStream(ModelChatRequest request) {
        return webClient.post()
                .uri("/model/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .doOnSubscribe(s -> log.debug("[StreamClient] 开始流式对话"))
                .doOnComplete(() -> log.debug("[StreamClient] 流式对话完成"))
                .doOnError(e -> log.error("[StreamClient] 流式对话异常", e));
    }
}
