package com.enterprise.ai.runtime.agent;

import java.util.Optional;

/** Agent 配置所属模块对外提供的已发布 Workflow 工具绑定。 */
public interface RuntimeAgentPublishedWorkflowToolQuery {

    Optional<Binding> findPublishedBinding(String workflowId, Long workflowVersionId);

    record Binding(
            String agentId,
            String agentKeySlug,
            String agentName,
            Long agentConfigVersionId,
            Integer agentConfigVersion,
            String modelInstanceId,
            String toolName,
            String riskLevel,
            String permissionKey) {
    }
}
