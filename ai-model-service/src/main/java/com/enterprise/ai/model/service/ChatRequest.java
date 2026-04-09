package com.enterprise.ai.model.service;

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
public class ChatRequest {

    /** 指定 Provider（tongyi / openai / jishi），为空时使用默认 */
    private String provider;

    /** 模型名称（如 qwen-max、gpt-4o），为空时使用 Provider 默认模型 */
    private String model;

    /** 对话消息列表 */
    private List<ChatMessage> messages;

    /** 额外参数（temperature、maxTokens 等） */
    private Map<String, Object> options;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatMessage {
        /** system / user / assistant */
        private String role;
        private String content;
    }
}
