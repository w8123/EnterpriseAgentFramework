package com.enterprise.ai.agent.client;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

/**
 * ai-model-service Feign Client — 统一模型网关调用入口。
 * <p>
 * 对应 ai-model-service 的 /model/* 接口，所有 LLM 和 Embedding 调用统一通过此客户端。
 */
@FeignClient(
        name = "ai-model-service",
        url = "${services.model-service.url:http://localhost:8090}",
        path = "/model"
)
public interface ModelServiceClient {

    @PostMapping("/chat")
    ModelChatResult chat(@RequestBody ModelChatRequest request);

    @PostMapping("/embedding")
    ModelEmbeddingResult embed(@RequestBody ModelEmbeddingRequest request);

    // ==================== Request / Response DTOs ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class ModelChatRequest {
        private String provider;
        private String model;
        private List<ChatMessage> messages;
        private Map<String, Object> options;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class ChatMessage {
            private String role;
            private String content;
        }
    }

    /**
     * 模型网关标准响应：ApiResult 包裹 ChatResponse
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ModelChatResult {
        private int code;
        private String message;
        private ModelChatData data;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ModelChatData {
        private String content;
        private String model;
        private String provider;
        private ModelUsage usage;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ModelUsage {
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class ModelEmbeddingRequest {
        private String provider;
        private String model;
        private List<String> texts;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ModelEmbeddingResult {
        private int code;
        private String message;
        private ModelEmbeddingData data;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ModelEmbeddingData {
        private List<List<Float>> embeddings;
        private int dimension;
        private String model;
        private String provider;
    }
}
