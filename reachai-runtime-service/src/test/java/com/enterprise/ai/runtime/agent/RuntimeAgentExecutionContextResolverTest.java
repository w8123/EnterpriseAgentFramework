package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeAgentExecutionContextResolverTest {

    @Test
    void resolvesKeySlugWithOneLookupWithoutDisplayResolution() {
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeAgentConfigVersionMapper configMapper = mock(RuntimeAgentConfigVersionMapper.class);
        RuntimeAgentWorkflowToolMapper toolMapper = mock(RuntimeAgentWorkflowToolMapper.class);
        RuntimeWorkflowDefinitionMapper workflowMapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeWorkflowVersionMapper versionMapper = mock(RuntimeWorkflowVersionMapper.class);
        RuntimeAgentExecutionContextResolver resolver = new RuntimeAgentExecutionContextResolver(
                agentMapper, configMapper, toolMapper, workflowMapper, versionMapper);
        RuntimeAgentEntity agent = agent("agent-1", "orders-agent", 11L);
        RuntimeAgentConfigVersionEntity config = config("agent-1", 11L);

        when(agentMapper.selectOne(any())).thenReturn(agent);
        when(configMapper.selectById(11L)).thenReturn(config);
        when(toolMapper.selectList(any())).thenReturn(List.of());

        RuntimeAgentExecutionContext context = resolver.resolve("orders-agent").orElseThrow();

        assertEquals("agent-1", context.agentView().id());
        assertEquals(11L, context.config().getId());
        verify(agentMapper, times(1)).selectOne(any());
        verify(agentMapper, never()).selectById(any());
        verify(configMapper, never()).selectOne(any());
        verify(workflowMapper, never()).selectBatchIds(any());
        verify(versionMapper, never()).selectBatchIds(any());
    }

    @Test
    void resolvesWorkflowTargetsInSingleBatchPerTable() {
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeAgentConfigVersionMapper configMapper = mock(RuntimeAgentConfigVersionMapper.class);
        RuntimeAgentWorkflowToolMapper toolMapper = mock(RuntimeAgentWorkflowToolMapper.class);
        RuntimeWorkflowDefinitionMapper workflowMapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeWorkflowVersionMapper versionMapper = mock(RuntimeWorkflowVersionMapper.class);
        RuntimeAgentExecutionContextResolver resolver = new RuntimeAgentExecutionContextResolver(
                agentMapper, configMapper, toolMapper, workflowMapper, versionMapper);
        RuntimeAgentEntity agent = agent("agent-1", "orders-agent", 11L);
        RuntimeAgentConfigVersionEntity config = config("agent-1", 11L);
        RuntimeAgentWorkflowToolEntity tool = new RuntimeAgentWorkflowToolEntity();
        tool.setAgentId("agent-1");
        tool.setAgentConfigVersionId(11L);
        tool.setWorkflowId("wf-orders");
        tool.setWorkflowVersionId(21L);
        tool.setEnabled(true);

        RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId("wf-orders");
        workflow.setStatus("ACTIVE");
        RuntimeWorkflowVersionEntity version = new RuntimeWorkflowVersionEntity();
        version.setId(21L);
        version.setWorkflowId("wf-orders");
        version.setGraphSpecSnapshotJson("{\"nodes\":[]}");

        when(agentMapper.selectOne(any())).thenReturn(agent);
        when(configMapper.selectById(11L)).thenReturn(config);
        when(toolMapper.selectList(any())).thenReturn(List.of(tool));
        when(workflowMapper.selectBatchIds(any())).thenReturn(List.of(workflow));
        when(versionMapper.selectBatchIds(any())).thenReturn(List.of(version));

        RuntimeAgentExecutionContext context = resolver.resolve("orders-agent").orElseThrow();

        assertTrue(context.config() != null);
        assertEquals(1, context.resolvedTargets().size());
        verify(agentMapper, times(1)).selectOne(any());
        verify(agentMapper, never()).selectById(any());
        verify(workflowMapper, times(1)).selectBatchIds(any());
        verify(versionMapper, times(1)).selectBatchIds(any());
    }

    private RuntimeAgentEntity agent(String id, String keySlug, Long activeConfigVersionId) {
        RuntimeAgentEntity agent = new RuntimeAgentEntity();
        agent.setId(id);
        agent.setKeySlug(keySlug);
        agent.setProjectId(7L);
        agent.setProjectCode("orders");
        agent.setName("Orders Agent");
        agent.setEnabled(true);
        agent.setActiveConfigVersionId(activeConfigVersionId);
        return agent;
    }

    private RuntimeAgentConfigVersionEntity config(String agentId, Long id) {
        RuntimeAgentConfigVersionEntity config = new RuntimeAgentConfigVersionEntity();
        config.setId(id);
        config.setAgentId(agentId);
        config.setStatus("ACTIVE");
        config.setRuntimeType("AGENTSCOPE");
        return config;
    }
}
