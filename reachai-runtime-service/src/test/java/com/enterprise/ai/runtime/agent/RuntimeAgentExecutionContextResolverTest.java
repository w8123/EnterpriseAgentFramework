package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        RuntimeAgentSkillBindingMapper skillBindingMapper = mock(RuntimeAgentSkillBindingMapper.class);
        RuntimeWorkflowDefinitionMapper workflowMapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeWorkflowVersionMapper versionMapper = mock(RuntimeWorkflowVersionMapper.class);
        RuntimeAgentExecutionContextResolver resolver = new RuntimeAgentExecutionContextResolver(
                new RuntimeAgentIdentityReader(agentMapper), configMapper, toolMapper, skillBindingMapper, new com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionReader(workflowMapper, versionMapper), mock(com.enterprise.ai.runtime.agent.RuntimeAgentRemoteBindingQuery.class));
        RuntimeAgentEntity agent = agent("agent-1", "orders-agent", 11L);
        RuntimeAgentConfigVersionEntity config = config("agent-1", 11L);

        when(agentMapper.selectByIdOrKeySlug(any())).thenReturn(agent);
        when(configMapper.selectById(11L)).thenReturn(config);
        when(toolMapper.selectList(any())).thenReturn(List.of());
        RuntimeAgentSkillBindingEntity skill = new RuntimeAgentSkillBindingEntity();
        skill.setAgentId("agent-1");
        skill.setAgentConfigVersionId(11L);
        skill.setPublisher("reachai");
        skill.setStandardName("demo-skill");
        skill.setEnabled(true);
        when(skillBindingMapper.selectList(any())).thenReturn(List.of(skill));

        RuntimeAgentExecutionContext context = resolver.resolve("orders-agent").orElseThrow();

        assertEquals("agent-1", context.agentView().id());
        assertEquals(11L, context.config().getId());
        assertEquals(1, context.skills().size());
        verify(agentMapper, times(1)).selectByIdOrKeySlug(any());
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
        RuntimeAgentSkillBindingMapper skillBindingMapper = mock(RuntimeAgentSkillBindingMapper.class);
        RuntimeWorkflowDefinitionMapper workflowMapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeWorkflowVersionMapper versionMapper = mock(RuntimeWorkflowVersionMapper.class);
        RuntimeAgentExecutionContextResolver resolver = new RuntimeAgentExecutionContextResolver(
                new RuntimeAgentIdentityReader(agentMapper), configMapper, toolMapper, skillBindingMapper, new com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionReader(workflowMapper, versionMapper), mock(com.enterprise.ai.runtime.agent.RuntimeAgentRemoteBindingQuery.class));
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
        version.setStatus("ACTIVE");
        version.setWorkflowId("wf-orders");
        version.setGraphSpecSnapshotJson("{\"nodes\":[]}");

        when(agentMapper.selectByIdOrKeySlug(any())).thenReturn(agent);
        when(configMapper.selectById(11L)).thenReturn(config);
        when(toolMapper.selectList(any())).thenReturn(List.of(tool));
        when(workflowMapper.selectBatchIds(any())).thenReturn(List.of(workflow));
        when(versionMapper.selectBatchIds(any())).thenReturn(List.of(version));

        RuntimeAgentExecutionContext context = resolver.resolve("orders-agent").orElseThrow();

        assertTrue(context.config() != null);
        assertEquals(1, context.resolvedTargets().size());
        verify(agentMapper, times(1)).selectByIdOrKeySlug(any());
        verify(agentMapper, never()).selectById(any());
        verify(workflowMapper, times(1)).selectBatchIds(any());
        verify(versionMapper, times(1)).selectBatchIds(any());
    }

    @Test
    void exactDraftConfigIsAvailableOnlyThroughEvaluationResolver() {
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeAgentConfigVersionMapper configMapper = mock(RuntimeAgentConfigVersionMapper.class);
        RuntimeAgentWorkflowToolMapper toolMapper = mock(RuntimeAgentWorkflowToolMapper.class);
        RuntimeAgentSkillBindingMapper skillBindingMapper = mock(RuntimeAgentSkillBindingMapper.class);
        RuntimeWorkflowDefinitionMapper workflowMapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeWorkflowVersionMapper versionMapper = mock(RuntimeWorkflowVersionMapper.class);
        RuntimeAgentExecutionContextResolver resolver = new RuntimeAgentExecutionContextResolver(
                new RuntimeAgentIdentityReader(agentMapper), configMapper, toolMapper, skillBindingMapper, new com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionReader(workflowMapper, versionMapper), mock(com.enterprise.ai.runtime.agent.RuntimeAgentRemoteBindingQuery.class));
        RuntimeAgentEntity agent = agent("agent-1", "orders-agent", 11L);
        RuntimeAgentConfigVersionEntity draft = config("agent-1", 12L);
        draft.setStatus("DRAFT");
        when(agentMapper.selectByIdOrKeySlug(any())).thenReturn(agent);
        when(configMapper.selectById(12L)).thenReturn(draft);
        when(toolMapper.selectList(any())).thenReturn(List.of());
        when(skillBindingMapper.selectList(any())).thenReturn(List.of());

        RuntimeAgentExecutionContext replay =
                resolver.resolvePublished("agent-1", 12L).orElseThrow();
        RuntimeAgentExecutionContext evaluation =
                resolver.resolveForEvaluation("agent-1", 12L).orElseThrow();

        assertNull(replay.config());
        assertEquals(12L, evaluation.config().getId());
        assertEquals("DRAFT", evaluation.config().getStatus());
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

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"DELETED"})
    void incompleteOrUnknownConfigStatusIsNotAPublishedVersion(String status) {
        var agents = mock(RuntimeAgentMapper.class);
        var configs = mock(RuntimeAgentConfigVersionMapper.class);
        var resolver = new RuntimeAgentExecutionContextResolver(new RuntimeAgentIdentityReader(agents), configs,
                mock(RuntimeAgentWorkflowToolMapper.class), mock(RuntimeAgentSkillBindingMapper.class),
                mock(com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionQuery.class),
                mock(RuntimeAgentRemoteBindingQuery.class));
        var invalid = config("agent-1", 12L);
        invalid.setStatus(status);
        when(agents.selectByIdOrKeySlug(any())).thenReturn(agent("agent-1", "orders-agent", 11L));
        when(configs.selectById(12L)).thenReturn(invalid);

        assertNull(resolver.resolvePublished("agent-1", 12L).orElseThrow().config());
        assertNull(resolver.resolveForEvaluation("agent-1", 12L).orElseThrow().config());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "ARCHIVED"})
    void publishedExecutionKeepsAnExactKnownVersion(String status) {
        var agents = mock(RuntimeAgentMapper.class);
        var configs = mock(RuntimeAgentConfigVersionMapper.class);
        var resolver = new RuntimeAgentExecutionContextResolver(new RuntimeAgentIdentityReader(agents), configs,
                mock(RuntimeAgentWorkflowToolMapper.class), mock(RuntimeAgentSkillBindingMapper.class),
                mock(com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionQuery.class),
                mock(RuntimeAgentRemoteBindingQuery.class));
        var pinned = config("agent-1", 12L);
        pinned.setStatus(status);
        when(agents.selectByIdOrKeySlug(any())).thenReturn(agent("agent-1", "orders-agent", 11L));
        when(configs.selectById(12L)).thenReturn(pinned);

        assertEquals(12L, resolver.resolvePublished("agent-1", 12L).orElseThrow().config().getId());
        verify(configs, never()).selectById(11L);
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
