package com.enterprise.ai.agent.model;

import com.enterprise.ai.agent.model.interactive.UiSubmitPayload;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 对话请求模型，承载用户输入和会话上下文
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    /**
     * 用户自然语言；当携带 {@link #interactionId} 恢复交互时可为空。
     */
    private String message;

    private String sessionId;

    private String userId;

    /** 前端可选传入的意图提示，为空时由系统自动识别 */
    private String intentHint;

    /**
     * 调用者的角色编码列表（Phase 3.1 Tool ACL）。
     * <p>
     * 网关 / 前端可直接传入；空时后端会按旧行为跳过 ACL 并打 warn。
     * 线上接入时建议统一由网关从 JWT 解出后强制回填。
     */
    private List<String> roles;

    /** Phase 2.x：恢复挂起的交互式 Skill */
    private String interactionId;

    private UiSubmitPayload uiSubmit;
}
