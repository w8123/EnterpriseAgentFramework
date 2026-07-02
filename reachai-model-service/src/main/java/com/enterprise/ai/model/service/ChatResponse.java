package com.enterprise.ai.model.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatResponse {

    private String content;

    private String model;

    private String provider;

    private Usage usage;

    /** MiMo 等思考模式返回的思考文本，多轮工具调用需随 assistant 消息回传 */
    private String reasoningContent;

    /** assistant 消息中的 tool_calls，需原样写入下一轮请求的 messages */
    private JsonNode toolCalls;

    /** 如 stop、tool_calls、length */
    private String finishReason;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Usage {
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;
    }
}
