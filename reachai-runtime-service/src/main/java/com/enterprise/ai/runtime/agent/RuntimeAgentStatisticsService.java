package com.enterprise.ai.runtime.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.agent.RuntimeAgentEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RuntimeAgentStatisticsService {

    private final RuntimeAgentMapper agentMapper;
    private final RuntimeAgentWorkflowToolMapper workflowToolMapper;

    @Transactional(readOnly = true)
    public RuntimeAgentStatisticsView statistics(Long projectId, String projectCode) {
        var agentQuery = Wrappers.<RuntimeAgentEntity>lambdaQuery();
        if (projectId != null) {
            agentQuery.eq(RuntimeAgentEntity::getProjectId, projectId);
        }
        if (StringUtils.hasText(projectCode)) {
            agentQuery.eq(RuntimeAgentEntity::getProjectCode, projectCode.trim());
        }

        List<RuntimeAgentEntity> agents = agentMapper.selectList(agentQuery);
        if (agents.isEmpty()) {
            return new RuntimeAgentStatisticsView(0, 0, 0, 0);
        }

        List<String> agentIds = agents.stream()
                .map(RuntimeAgentEntity::getId)
                .filter(StringUtils::hasText)
                .toList();
        List<Long> activeConfigVersionIds = agents.stream()
                .map(RuntimeAgentEntity::getActiveConfigVersionId)
                .filter(Objects::nonNull)
                .toList();
        List<RuntimeAgentWorkflowToolEntity> activeWorkflowTools = agentIds.isEmpty() || activeConfigVersionIds.isEmpty()
                ? List.of()
                : workflowToolMapper.selectList(Wrappers.<RuntimeAgentWorkflowToolEntity>lambdaQuery()
                        .in(RuntimeAgentWorkflowToolEntity::getAgentId, agentIds)
                        .in(RuntimeAgentWorkflowToolEntity::getAgentConfigVersionId, activeConfigVersionIds)
                        .and(query -> query.isNull(RuntimeAgentWorkflowToolEntity::getEnabled)
                                .or()
                                .eq(RuntimeAgentWorkflowToolEntity::getEnabled, true)));

        long enabledAgents = agents.stream()
                .filter(agent -> !Boolean.FALSE.equals(agent.getEnabled()))
                .count();
        Set<String> workflowToolAgentIds = activeWorkflowTools.stream()
                .map(RuntimeAgentWorkflowToolEntity::getAgentId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());

        return new RuntimeAgentStatisticsView(
                agents.size(),
                enabledAgents,
                workflowToolAgentIds.size(),
                activeWorkflowTools.size());
    }
}
