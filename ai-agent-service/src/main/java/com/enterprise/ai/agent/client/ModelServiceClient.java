package com.enterprise.ai.agent.client;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * ai-model-service Feign Client — 统一模型网关调用入口。
 * <p>
 * 对应 ai-model-service 的 /model/* 接口，所有 LLM 和 Embedding 调用统一通过此客户端。
 */
@FeignClient(
        name = "ai-model-service",
        url = "${services.model-service.url:http://localhost:18601}",
        path = "/model"
)
public interface ModelServiceClient {

    @PostMapping("/chat")
    ModelChatResult chat(@RequestBody ModelChatRequest request);

    @PostMapping("/embedding")
    ModelEmbeddingResult embed(@RequestBody ModelEmbeddingRequest request);

    @GetMapping("/instances/{id}")
    ModelInstanceResult getModelInstance(@PathVariable("id") String id);

    @GetMapping("/instances")
    ModelInstanceListResult listModelInstances(@RequestParam(value = "modelType", required = false) String modelType,
                                               @RequestParam(value = "provider", required = false) String provider,
                                               @RequestParam(value = "workspaceId", required = false) String workspaceId);

    // ==================== Request / Response DTOs ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    class ModelChatRequest {
        private String modelInstanceId;
        private List<ChatMessage> messages;
        private Map<String, Object> options;
        private JsonNode tools;

        @JsonAlias("tool_choice")
        private JsonNode toolChoice;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class ChatMessage {
            private String role;
            private String content;
            @JsonAlias("reasoning_content")
            private String reasoningContent;
            @JsonAlias("tool_calls")
            private JsonNode toolCalls;
            @JsonAlias("tool_call_id")
            private String toolCallId;
            private String name;
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
    @JsonInclude(JsonInclude.Include.NON_NULL)
    class ModelChatData {
        private String content;
        private String model;
        private String provider;
        private ModelUsage usage;
        private String reasoningContent;
        private JsonNode toolCalls;
        private String finishReason;
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
        private String modelInstanceId;
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

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ModelInstanceListResult {
        private int code;
        private String message;
        private List<ModelInstanceData> data;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ModelInstanceResult {
        private int code;
        private String message;
        private ModelInstanceData data;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    class ModelInstanceData {
        private String id;
        private String name;
        private String provider;
        private String modelType;
        private String modelName;
        private String endpointType;
        private String workspaceId;
        private Map<String, Object> defaultOptions;
        private Object paramsSchema;
        private String status;
        private String remark;
    }
}
