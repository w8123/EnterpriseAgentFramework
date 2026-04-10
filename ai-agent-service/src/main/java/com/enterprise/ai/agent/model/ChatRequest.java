package com.enterprise.ai.agent.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话请求模型，承载用户输入和会话上下文
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    @NotBlank(message = "消息内容不能为空")
    private String message;

    private String sessionId;

    private String userId;

    /** 前端可选传入的意图提示，为空时由系统自动识别 */
    private String intentHint;
}
