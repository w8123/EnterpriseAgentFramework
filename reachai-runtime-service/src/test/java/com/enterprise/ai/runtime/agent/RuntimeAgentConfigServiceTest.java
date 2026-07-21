package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.runtime.agent.RuntimeAgentEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeAgentConfigServiceTest {

    @Test
    void copiesArchivedSnapshotIntoANewDraftWithoutMutatingPublishedVersion() {
        RuntimeAgentConfigVersionMapper configMapper = mock(RuntimeAgentConfigVersionMapper.class);
        RuntimeAgentWorkflowToolMapper toolMapper = mock(RuntimeAgentWorkflowToolMapper.class);
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeWorkflowDefinitionMapper workflowMapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeWorkflowVersionMapper versionMapper = mock(RuntimeWorkflowVersionMapper.class);
        RuntimeAgentConfigService service = new RuntimeAgentConfigService(
                configMapper, toolMapper, agentMapper, workflowMapper, versionMapper, new ObjectMapper());

        RuntimeAgentEntity agent = new RuntimeAgentEntity();
        agent.setId("agent-1");
        when(agentMapper.selectById("agent-1")).thenReturn(agent);
        RuntimeAgentConfigVersionEntity archived = new RuntimeAgentConfigVersionEntity();
        archived.setId(3L);
        archived.setAgentId("agent-1");
        archived.setVersionNo(3);
        archived.setStatus("ARCHIVED");
        archived.setRuntimeType("AGENTSCOPE");
        archived.setSystemPrompt("snapshot prompt");
        archived.setModelInstanceId("model-1");

        AtomicReference<RuntimeAgentConfigVersionEntity> inserted = new AtomicReference<>();
        when(configMapper.selectById(any())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            if (Long.valueOf(3L).equals(id)) return archived;
            RuntimeAgentConfigVersionEntity draft = inserted.get();
            return draft != null && id.equals(draft.getId()) ? draft : null;
        });
        when(configMapper.selectOne(any())).thenReturn(null, null, archived, null);
        when(toolMapper.selectList(any())).thenReturn(List.of());
        when(configMapper.insert(any())).thenAnswer(invocation -> {
            RuntimeAgentConfigVersionEntity draft = invocation.getArgument(0);
            draft.setId(4L);
            inserted.set(draft);
            return 1;
        });

        RuntimeAgentConfigViews.AgentConfigVersionView result = service.copyToDraft("agent-1", 3L);

        assertEquals(4L, result.id());
        assertEquals(4, result.versionNo());
        assertEquals("DRAFT", result.status());
        assertEquals("snapshot prompt", result.systemPrompt());
        assertEquals("ARCHIVED", archived.getStatus());
    }

    @Test
    void addsPublishedPageWorkflowToSupervisorDraftInsteadOfRelyingOnBinding() {
        RuntimeAgentConfigVersionMapper configMapper = mock(RuntimeAgentConfigVersionMapper.class);
        RuntimeAgentWorkflowToolMapper toolMapper = mock(RuntimeAgentWorkflowToolMapper.class);
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeWorkflowDefinitionMapper workflowMapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeWorkflowVersionMapper versionMapper = mock(RuntimeWorkflowVersionMapper.class);
        RuntimeAgentConfigService service = new RuntimeAgentConfigService(
                configMapper, toolMapper, agentMapper, workflowMapper, versionMapper, new ObjectMapper());

        RuntimeAgentEntity agent = new RuntimeAgentEntity();
        agent.setId("agent-1");
        when(agentMapper.selectById("agent-1")).thenReturn(agent);

        RuntimeAgentConfigVersionEntity draft = new RuntimeAgentConfigVersionEntity();
        draft.setId(12L);
        draft.setAgentId("agent-1");
        draft.setVersionNo(2);
        draft.setStatus("DRAFT");
        when(configMapper.selectOne(any())).thenReturn(draft);
        when(configMapper.selectById(12L)).thenReturn(draft);
        when(toolMapper.selectList(any())).thenReturn(List.of());

        RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId("wf-1");
        workflow.setKeySlug("team-page-assistant");
        workflow.setDescription("Operate the team page");
        workflow.setStatus("ACTIVE");
        when(workflowMapper.selectById("wf-1")).thenReturn(workflow);
        RuntimeWorkflowVersionEntity version = new RuntimeWorkflowVersionEntity();
        version.setId(21L);
        version.setWorkflowId("wf-1");
        version.setVersion("v1.0.0");
        when(versionMapper.listActive("wf-1")).thenReturn(List.of(version));

        RuntimeAgentConfigViews.AgentConfigVersionView result =
                service.ensureWorkflowToolInDraft("agent-1", "wf-1", false);

        assertEquals(12L, result.id());
        ArgumentCaptor<RuntimeAgentWorkflowToolEntity> saved =
                ArgumentCaptor.forClass(RuntimeAgentWorkflowToolEntity.class);
        verify(toolMapper).insert(saved.capture());
        assertEquals("wf-1", saved.getValue().getWorkflowId());
        assertEquals("team_page_assistant", saved.getValue().getToolName());
        assertEquals("PAGE_ACTION", saved.getValue().getRiskLevel());
        assertEquals(false, saved.getValue().getReadOnly());
    }

    @Test
    void publishPinsEnabledWorkflowToolToCurrentActiveWorkflowVersion() {
        RuntimeAgentConfigVersionMapper configMapper = mock(RuntimeAgentConfigVersionMapper.class);
        RuntimeAgentWorkflowToolMapper toolMapper = mock(RuntimeAgentWorkflowToolMapper.class);
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeWorkflowDefinitionMapper workflowMapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeWorkflowVersionMapper versionMapper = mock(RuntimeWorkflowVersionMapper.class);
        RuntimeAgentConfigService service = new RuntimeAgentConfigService(
                configMapper, toolMapper, agentMapper, workflowMapper, versionMapper, new ObjectMapper());

        RuntimeAgentEntity agent = new RuntimeAgentEntity();
        agent.setId("agent-1");
        agent.setActiveConfigVersionId(5L);
        when(agentMapper.selectById("agent-1")).thenReturn(agent);

        RuntimeAgentConfigVersionEntity draft = new RuntimeAgentConfigVersionEntity();
        draft.setId(6L);
        draft.setAgentId("agent-1");
        draft.setVersionNo(2);
        draft.setStatus("DRAFT");
        draft.setRuntimeType("AGENTSCOPE");
        draft.setSystemPrompt("You are the orders supervisor");
        draft.setModelInstanceId("model-1");
        draft.setToolCatalogMode("ALLOW_LIST");
        RuntimeAgentConfigVersionEntity previousActive = new RuntimeAgentConfigVersionEntity();
        previousActive.setId(5L);
        previousActive.setAgentId("agent-1");
        previousActive.setVersionNo(1);
        previousActive.setStatus("ACTIVE");
        when(configMapper.selectById(6L)).thenReturn(draft);
        when(configMapper.selectList(any())).thenReturn(List.of(previousActive));

        RuntimeAgentWorkflowToolEntity tool = new RuntimeAgentWorkflowToolEntity();
        tool.setId(11L);
        tool.setAgentId("agent-1");
        tool.setAgentConfigVersionId(6L);
        tool.setWorkflowId("wf-orders");
        tool.setWorkflowVersionId(21L);
        tool.setToolName("query_orders");
        tool.setEnabled(true);
        when(toolMapper.selectList(any())).thenReturn(List.of(tool));

        RuntimeWorkflowVersionEntity activeWorkflowVersion = new RuntimeWorkflowVersionEntity();
        activeWorkflowVersion.setId(42L);
        activeWorkflowVersion.setWorkflowId("wf-orders");
        activeWorkflowVersion.setVersion("v2.0.0");
        activeWorkflowVersion.setStatus("ACTIVE");
        activeWorkflowVersion.setGraphSpecSnapshotJson("{\"entry\":\"start\"}");
        when(versionMapper.listActive("wf-orders")).thenReturn(List.of(activeWorkflowVersion));
        when(versionMapper.selectById(42L)).thenReturn(activeWorkflowVersion);
        when(versionMapper.selectBatchIds(any())).thenReturn(List.of(activeWorkflowVersion));
        RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId("wf-orders");
        workflow.setKeySlug("orders-workflow");
        workflow.setName("Orders Workflow");
        when(workflowMapper.selectBatchIds(any())).thenReturn(List.of(workflow));

        RuntimeAgentConfigViews.AgentConfigVersionView published = service.publish("agent-1", 6L, "tester");

        ArgumentCaptor<RuntimeAgentWorkflowToolEntity> pinned =
                ArgumentCaptor.forClass(RuntimeAgentWorkflowToolEntity.class);
        verify(toolMapper).updateById(pinned.capture());
        assertEquals(42L, pinned.getValue().getWorkflowVersionId());
        assertEquals(42L, published.tools().get(0).workflowVersionId());
        assertEquals("v2.0.0", published.tools().get(0).workflowVersion());
        assertEquals("ACTIVE", draft.getStatus());
        assertEquals("ARCHIVED", previousActive.getStatus());
        assertEquals(6L, agent.getActiveConfigVersionId());
    }

    @Test
    void publishRejectsInvalidConfigJson() {
        RuntimeAgentConfigVersionMapper configMapper = mock(RuntimeAgentConfigVersionMapper.class);
        RuntimeAgentWorkflowToolMapper toolMapper = mock(RuntimeAgentWorkflowToolMapper.class);
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeWorkflowDefinitionMapper workflowMapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeWorkflowVersionMapper versionMapper = mock(RuntimeWorkflowVersionMapper.class);
        RuntimeAgentConfigService service = new RuntimeAgentConfigService(
                configMapper, toolMapper, agentMapper, workflowMapper, versionMapper, new ObjectMapper());

        RuntimeAgentEntity agent = new RuntimeAgentEntity();
        agent.setId("agent-1");
        when(agentMapper.selectById("agent-1")).thenReturn(agent);
        RuntimeAgentConfigVersionEntity draft = new RuntimeAgentConfigVersionEntity();
        draft.setId(6L);
        draft.setAgentId("agent-1");
        draft.setStatus("DRAFT");
        draft.setRuntimeType("AGENTSCOPE");
        draft.setSystemPrompt("You are the orders supervisor");
        draft.setModelInstanceId("model-1");
        draft.setToolCatalogMode("ALLOW_LIST");
        draft.setConfigJson("{invalid-json");
        when(configMapper.selectById(6L)).thenReturn(draft);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.publish("agent-1", 6L, "tester"));

        assertEquals("Agent configJson must be valid JSON", error.getMessage());
    }
}
