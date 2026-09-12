package com.enterprise.ai.runtime.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RuntimeAgentPublishedWorkflowToolReader implements RuntimeAgentPublishedWorkflowToolQuery {

    private final RuntimeAgentWorkflowToolMapper workflowToolMapper;
    private final RuntimeAgentMapper agentMapper;
    private final RuntimeAgentConfigVersionMapper agentConfigMapper;

    @Override
    @Transactional(readOnly = true)
    public Optional<Binding> findPublishedBinding(String workflowId, Long workflowVersionId) {
        if (!StringUtils.hasText(workflowId) || workflowVersionId == null) return Optional.empty();
        List<RuntimeAgentWorkflowToolEntity> tools = workflowToolMapper.selectList(
                Wrappers.<RuntimeAgentWorkflowToolEntity>lambdaQuery()
                        .eq(RuntimeAgentWorkflowToolEntity::getWorkflowId, workflowId)
                        .eq(RuntimeAgentWorkflowToolEntity::getWorkflowVersionId, workflowVersionId)
                        .eq(RuntimeAgentWorkflowToolEntity::getEnabled, true)
                        .orderByDesc(RuntimeAgentWorkflowToolEntity::getUpdatedAt));
        for (RuntimeAgentWorkflowToolEntity tool : tools) {
            RuntimeAgentEntity agent = agentMapper.selectById(tool.getAgentId());
            if (agent == null || !Boolean.TRUE.equals(agent.getEnabled())
                    || !Objects.equals(agent.getActiveConfigVersionId(), tool.getAgentConfigVersionId())) {
                continue;
            }
            RuntimeAgentConfigVersionEntity config = agentConfigMapper.selectById(tool.getAgentConfigVersionId());
            if (config != null && "ACTIVE".equalsIgnoreCase(config.getStatus())
                    && Objects.equals(agent.getId(), config.getAgentId())) {
                return Optional.of(new Binding(agent.getId(), agent.getKeySlug(), agent.getName(),
                        config.getId(), config.getVersionNo(), config.getModelInstanceId(),
                        tool.getToolName(), tool.getRiskLevel(), tool.getPermissionKey()));
            }
        }
        return Optional.empty();
    }
}
