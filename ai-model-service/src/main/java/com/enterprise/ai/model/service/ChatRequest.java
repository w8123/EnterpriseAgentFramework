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

    /** 指定 Provider（tongyi / mimo 等），为空时使用默认 */
    private String provider;

    /** 模型名称（如 qwen-max、gpt-4o），为空时使用 Provider 默认模型 */
    private String model;

    /** 对话消息列表 */
    private List<ChatMessage> messages;

    /** OpenAI 格式 tools 定义（如 MiMo 多轮工具调用） */
    private JsonNode tools;

    /** 如 auto、none，或强制指定某 tool */
    @JsonAlias("tool_choice")
    private JsonNode toolChoice;

    /** 额外参数（temperature、maxTokens 等） */
    private Map<String, Object> options;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ChatMessage {
        /** system / user / assistant / tool */
        private String role;
        private String content;

        /** MiMo 思考链，多轮时需回传 */
        @JsonAlias("reasoning_content")
        private String reasoningContent;

        @JsonAlias("tool_calls")
        private JsonNode toolCalls;

        @JsonAlias("tool_call_id")
        private String toolCallId;

        /** assistant 带 tool_calls 时可选；tool 消息也可带 */
        private String name;
    }
}
