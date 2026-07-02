package com.enterprise.ai.runtime.chat;

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
public class RuntimeChatResponse {

    private String answer;

    private String sessionId;

    private String intentType;

    private List<String> toolCalls;

    private List<String> reasoningSteps;

    private Map<String, Object> metadata;

    private Object uiRequest;

    public static RuntimeChatResponse of(String answer) {
        return RuntimeChatResponse.builder().answer(answer).build();
    }

    public static RuntimeChatResponse error(String errorMessage) {
        return RuntimeChatResponse.builder()
                .answer("抱歉，处理您的请求时遇到了问题：" + errorMessage)
                .build();
    }
}
