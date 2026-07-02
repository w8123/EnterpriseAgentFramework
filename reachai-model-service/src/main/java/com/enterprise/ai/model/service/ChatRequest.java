package com.enterprise.ai.model.service;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatRequest {

    private String modelInstanceId;
    private List<ChatMessage> messages;
    private JsonNode tools;

    @JsonAlias("tool_choice")
    private JsonNode toolChoice;

    private Map<String, Object> options;

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
