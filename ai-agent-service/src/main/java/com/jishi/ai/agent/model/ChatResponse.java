package com.jishi.ai.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 对话响应模型，包含AI回答和执行过程元数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {

    private String answer;

    private String intentType;

    private List<String> toolCalls;

    /** Agent多步推理的中间步骤摘要 */
    private List<String> reasoningSteps;

    private Map<String, Object> metadata;

    public static ChatResponse of(String answer) {
        return ChatResponse.builder().answer(answer).build();
    }

    public static ChatResponse error(String errorMessage) {
        return ChatResponse.builder()
                .answer("抱歉，处理您的请求时遇到了问题：" + errorMessage)
                .build();
    }
}
