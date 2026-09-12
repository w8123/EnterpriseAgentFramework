package com.enterprise.ai.runtime.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class RuntimeAgentPublishedConfigReader implements RuntimeAgentPublishedConfigQuery {

    private final RuntimeAgentMapper agents;
    private final RuntimeAgentConfigVersionMapper versions;

    @Override
    @Transactional(readOnly = true)
    public Target resolve(String agentId, Long versionId) {
        RuntimeAgentEntity agent = StringUtils.hasText(agentId) ? agents.selectById(agentId.trim()) : null;
        if (agent == null || !Boolean.TRUE.equals(agent.getEnabled())) {
            throw new LookupFailure(Reason.AGENT_UNAVAILABLE, "Published Agent is unavailable: " + agentId);
        }
        RuntimeAgentConfigVersionEntity version = versionId == null || versionId <= 0
                ? null : versions.selectById(versionId);
        if (version == null || !agent.getId().equals(version.getAgentId())
                || !RuntimeAgentConfigStatus.isPublished(version.getStatus())) {
            throw new LookupFailure(Reason.VERSION_INVALID,
                    "Agent has no exact published configuration: " + agent.getId() + "#" + versionId);
        }
        return new Target(agent.getId(), agent.getProjectId(), agent.getProjectCode(), agent.getKeySlug(),
                agent.getName(), version.getId(), version.getVersionNo(), version.getRuntimeType(), version.getPublishedAt());
    }
}
