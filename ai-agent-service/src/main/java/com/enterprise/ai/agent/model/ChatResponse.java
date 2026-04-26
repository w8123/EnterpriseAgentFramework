package com.enterprise.ai.agent.model;

import com.enterprise.ai.agent.model.interactive.UiRequestPayload;
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

    /** 会话标识，客户端应在后续请求中携带以延续多轮对话 */
    private String sessionId;

    private String intentType;

    private List<String> toolCalls;

    /** Agent多步推理的中间步骤摘要 */
    private List<String> reasoningSteps;

    private Map<String, Object> metadata;

    /** Phase 2.x：交互式表单挂起 */
    private UiRequestPayload uiRequest;

    public static ChatResponse of(String answer) {
        return ChatResponse.builder().answer(answer).build();
    }

    public static ChatResponse error(String errorMessage) {
        return ChatResponse.builder()
                .answer("抱歉，处理您的请求时遇到了问题：" + errorMessage)
                .build();
    }
}
