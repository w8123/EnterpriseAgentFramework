package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.runtime.agent.RuntimeAgentEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeAgentStatisticsServiceTest {

    @Test
    void aggregatesAgentAndActiveWorkflowToolDimensions() {
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeAgentWorkflowToolMapper workflowToolMapper = mock(RuntimeAgentWorkflowToolMapper.class);
        when(agentMapper.selectList(any())).thenReturn(List.of(
                agent("agent-1", true, 11L),
                agent("agent-2", false, 12L),
                agent("agent-3", null, null)));
        when(workflowToolMapper.selectList(any())).thenReturn(List.of(
                workflowTool("agent-1", 11L),
                workflowTool("agent-1", 11L),
                workflowTool("agent-2", 12L)));

        RuntimeAgentStatisticsView result =
                new RuntimeAgentStatisticsService(agentMapper, workflowToolMapper).statistics(7L, "orders");

        assertEquals(3, result.totalAgents());
        assertEquals(2, result.enabledAgents());
        assertEquals(2, result.workflowToolAgents());
        assertEquals(3, result.activeWorkflowTools());
        verify(agentMapper).selectList(any());
        verify(workflowToolMapper).selectList(any());
    }

    @Test
    void skipsWorkflowToolQueryForAnEmptyAgentScope() {
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeAgentWorkflowToolMapper workflowToolMapper = mock(RuntimeAgentWorkflowToolMapper.class);
        when(agentMapper.selectList(any())).thenReturn(List.of());

        RuntimeAgentStatisticsView result =
                new RuntimeAgentStatisticsService(agentMapper, workflowToolMapper).statistics(null, null);

        assertEquals(new RuntimeAgentStatisticsView(0, 0, 0, 0), result);
    }

    private RuntimeAgentEntity agent(String id, Boolean enabled, Long activeConfigVersionId) {
        RuntimeAgentEntity entity = new RuntimeAgentEntity();
        entity.setId(id);
        entity.setEnabled(enabled);
        entity.setActiveConfigVersionId(activeConfigVersionId);
        return entity;
    }

    private RuntimeAgentWorkflowToolEntity workflowTool(String agentId, Long configVersionId) {
        RuntimeAgentWorkflowToolEntity entity = new RuntimeAgentWorkflowToolEntity();
        entity.setAgentId(agentId);
        entity.setAgentConfigVersionId(configVersionId);
        entity.setEnabled(true);
        return entity;
    }
}
