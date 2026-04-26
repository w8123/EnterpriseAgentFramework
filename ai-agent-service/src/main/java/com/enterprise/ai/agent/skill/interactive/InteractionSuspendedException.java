package com.enterprise.ai.agent.skill.interactive;

import com.enterprise.ai.agent.model.interactive.UiRequestPayload;
import lombok.Getter;

/**
 * 交互式 Skill 挂起：由 {@link com.enterprise.ai.agent.agentscope.adapter.AiToolAgentAdapter} 捕获，
 * 将 {@link UiRequestPayload} 写入 {@link com.enterprise.ai.agent.tool.log.ToolExecutionContext}。
 */
@Getter
public class InteractionSuspendedException extends RuntimeException {

    private final UiRequestPayload payload;
    private final String userVisibleMessage;

    public InteractionSuspendedException(UiRequestPayload payload, String userVisibleMessage) {
        super(userVisibleMessage);
        this.payload = payload;
        this.userVisibleMessage = userVisibleMessage == null ? "" : userVisibleMessage;
    }
}
