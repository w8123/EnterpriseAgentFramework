package com.enterprise.ai.runtime.client.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

@FeignClient(
        name = "reachai-model-service",
        url = "${services.model-service.url:http://localhost:18601}",
        path = "/model"
)
public interface RuntimeModelServiceClient {

    @PostMapping("/chat")
    ModelChatResult chat(@RequestBody ModelChatRequest request);

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
}
