package com.enterprise.ai.common.agent;

import java.util.Map;

/** An obsolete command must never obtain a fresh revision or mutate an Agent. */
public final class AgentDraftProtocol {
    private AgentDraftProtocol() { }
    public static Map<String, Object> revisionRequired() {
        return Map.of("code", "AGENT_DRAFT_REVISION_REQUIRED",
                "message", "请通过 Control 读取 Agent 当前配置和修订，再使用带修订的保存、发布或复制接口；旧入口不再写入。",
                "contextPath", "/api/agents/{agentId}/config-versions/draft-context",
                "savePath", "/api/agents/{agentId}/config-versions/draft-if-unchanged",
                "publishPath", "/api/agents/{agentId}/config-versions/{configVersionId}/publish-if-unchanged",
                "copyPath", "/api/agents/{agentId}/config-versions/{configVersionId}/copy-to-draft-if-unchanged");
    }
}
