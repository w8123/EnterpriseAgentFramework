package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.runtime.agent.RuntimeAgentEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentMapper;
import com.enterprise.ai.runtime.a2a.RuntimeA2aRemoteAgentBindingMapper;
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
        RuntimeAgentSkillBindingMapper skillBindingMapper = mock(RuntimeAgentSkillBindingMapper.class);
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeWorkflowDefinitionMapper workflowMapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeWorkflowVersionMapper versionMapper = mock(RuntimeWorkflowVersionMapper.class);
        RuntimeAgentConfigService service = new RuntimeAgentConfigService(
                configMapper, toolMapper, skillBindingMapper, mock(RuntimeA2aRemoteAgentBindingMapper.class),
                agentMapper, workflowMapper, versionMapper, new ObjectMapper());

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
        RuntimeAgentSkillBindingMapper skillBindingMapper = mock(RuntimeAgentSkillBindingMapper.class);
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeWorkflowDefinitionMapper workflowMapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeWorkflowVersionMapper versionMapper = mock(RuntimeWorkflowVersionMapper.class);
        RuntimeAgentConfigService service = new RuntimeAgentConfigService(
                configMapper, toolMapper, skillBindingMapper, mock(RuntimeA2aRemoteAgentBindingMapper.class),
                agentMapper, workflowMapper, versionMapper, new ObjectMapper());

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
        RuntimeAgentSkillBindingMapper skillBindingMapper = mock(RuntimeAgentSkillBindingMapper.class);
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeWorkflowDefinitionMapper workflowMapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeWorkflowVersionMapper versionMapper = mock(RuntimeWorkflowVersionMapper.class);
        RuntimeAgentConfigService service = new RuntimeAgentConfigService(
                configMapper, toolMapper, skillBindingMapper, mock(RuntimeA2aRemoteAgentBindingMapper.class),
                agentMapper, workflowMapper, versionMapper, new ObjectMapper());

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
        activeWorkflowVersion.setGraphSpecSnapshotJson("{\"entryNodeId\":\"start\"}");
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
        RuntimeAgentSkillBindingMapper skillBindingMapper = mock(RuntimeAgentSkillBindingMapper.class);
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeWorkflowDefinitionMapper workflowMapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeWorkflowVersionMapper versionMapper = mock(RuntimeWorkflowVersionMapper.class);
        RuntimeAgentConfigService service = new RuntimeAgentConfigService(
                configMapper, toolMapper, skillBindingMapper, mock(RuntimeA2aRemoteAgentBindingMapper.class),
                agentMapper, workflowMapper, versionMapper, new ObjectMapper());

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

    @Test
    void publishRejectsManagedExecutorAuthorityOutsideTheFixedVersionSchema() {
        RuntimeAgentConfigVersionMapper configMapper = mock(RuntimeAgentConfigVersionMapper.class);
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeAgentConfigService service = new RuntimeAgentConfigService(
                configMapper,
                mock(RuntimeAgentWorkflowToolMapper.class),
                mock(RuntimeAgentSkillBindingMapper.class),
                mock(RuntimeA2aRemoteAgentBindingMapper.class),
                agentMapper,
                mock(RuntimeWorkflowDefinitionMapper.class),
                mock(RuntimeWorkflowVersionMapper.class),
                new ObjectMapper());
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
        draft.setConfigJson("""
                {"managedExecutor":{
                  "enabled":true,
                  "allowedTools":["managed_executor.start"],
                  "sandboxProfile":"ANALYZE_READONLY",
                  "acceptanceProfile":"PROJECT_DEFAULT",
                  "maxWallTimeSeconds":900,
                  "approvalTimeoutSeconds":300,
                  "network":"ALLOW_ALL"
                }}
                """);
        when(configMapper.selectById(6L)).thenReturn(draft);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.publish("agent-1", 6L, "tester"));

        assertEquals("Agent managedExecutor config contains unsupported field: network", error.getMessage());
    }

    @Test
    void explicitlyReplacesOneWorkflowAndPreservesTheOtherCatalogEntries() {
        RuntimeAgentConfigVersionMapper configMapper = mock(RuntimeAgentConfigVersionMapper.class);
        RuntimeAgentWorkflowToolMapper toolMapper = mock(RuntimeAgentWorkflowToolMapper.class);
        RuntimeAgentSkillBindingMapper skillBindingMapper = mock(RuntimeAgentSkillBindingMapper.class);
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeWorkflowDefinitionMapper workflowMapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeWorkflowVersionMapper versionMapper = mock(RuntimeWorkflowVersionMapper.class);
        RuntimeAgentConfigService service = new RuntimeAgentConfigService(
                configMapper, toolMapper, skillBindingMapper, mock(RuntimeA2aRemoteAgentBindingMapper.class),
                agentMapper, workflowMapper, versionMapper, new ObjectMapper());

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

        RuntimeAgentWorkflowToolEntity oldTool = workflowTool(
                31L, "wf-old", 21L, "old_page_assistant", 0);
        RuntimeAgentWorkflowToolEntity retainedTool = workflowTool(
                32L, "wf-retained", 22L, "cycle_audit", 1);
        when(toolMapper.selectList(any())).thenReturn(List.of(oldTool, retainedTool));

        RuntimeWorkflowDefinitionEntity oldWorkflow = workflow(
                "wf-old", "old-page-assistant", "Old page assistant");
        RuntimeWorkflowDefinitionEntity retainedWorkflow = workflow(
                "wf-retained", "cycle-audit", "Cycle audit");
        RuntimeWorkflowDefinitionEntity replacementWorkflow = workflow(
                "wf-new", "new-page-assistant", "New page assistant");
        when(workflowMapper.selectById("wf-old")).thenReturn(oldWorkflow);
        when(workflowMapper.selectById("wf-retained")).thenReturn(retainedWorkflow);
        when(workflowMapper.selectById("wf-new")).thenReturn(replacementWorkflow);
        when(workflowMapper.selectBatchIds(any())).thenReturn(
                List.of(oldWorkflow, retainedWorkflow));

        RuntimeWorkflowVersionEntity oldVersion = workflowVersion(21L, "wf-old");
        RuntimeWorkflowVersionEntity retainedVersion = workflowVersion(22L, "wf-retained");
        RuntimeWorkflowVersionEntity replacementVersion = workflowVersion(23L, "wf-new");
        when(versionMapper.listActive("wf-old")).thenReturn(List.of(oldVersion));
        when(versionMapper.listActive("wf-retained")).thenReturn(List.of(retainedVersion));
        when(versionMapper.listActive("wf-new")).thenReturn(List.of(replacementVersion));
        when(versionMapper.selectBatchIds(any())).thenReturn(
                List.of(oldVersion, retainedVersion));

        service.replaceWorkflowToolInDraft(
                "agent-1",
                "wf-old",
                new RuntimeAgentConfigViews.WorkflowToolRequest(
                        "wf-new",
                        "new_page_assistant",
                        "New page assistant",
                        null,
                        null,
                        "PAGE_ACTION",
                        "workflow:new-page-assistant",
                        false,
                        true,
                        null));

        ArgumentCaptor<RuntimeAgentWorkflowToolEntity> inserted =
                ArgumentCaptor.forClass(RuntimeAgentWorkflowToolEntity.class);
        verify(toolMapper, org.mockito.Mockito.times(2)).insert(inserted.capture());
        List<RuntimeAgentWorkflowToolEntity> saved = inserted.getAllValues();
        assertEquals(List.of("wf-new", "wf-retained"),
                saved.stream().map(RuntimeAgentWorkflowToolEntity::getWorkflowId).toList());
        assertEquals(0, saved.get(0).getPriority());
        assertEquals(1, saved.get(1).getPriority());
    }

    @Test
    void rejectsReplacementWhenTheNamedOldWorkflowIsNotAttached() {
        RuntimeAgentConfigVersionMapper configMapper = mock(RuntimeAgentConfigVersionMapper.class);
        RuntimeAgentWorkflowToolMapper toolMapper = mock(RuntimeAgentWorkflowToolMapper.class);
        RuntimeAgentSkillBindingMapper skillBindingMapper = mock(RuntimeAgentSkillBindingMapper.class);
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeWorkflowDefinitionMapper workflowMapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeWorkflowVersionMapper versionMapper = mock(RuntimeWorkflowVersionMapper.class);
        RuntimeAgentConfigService service = new RuntimeAgentConfigService(
                configMapper, toolMapper, skillBindingMapper, mock(RuntimeA2aRemoteAgentBindingMapper.class),
                agentMapper, workflowMapper, versionMapper, new ObjectMapper());

        RuntimeAgentEntity agent = new RuntimeAgentEntity();
        agent.setId("agent-1");
        when(agentMapper.selectById("agent-1")).thenReturn(agent);
        RuntimeAgentConfigVersionEntity draft = new RuntimeAgentConfigVersionEntity();
        draft.setId(12L);
        draft.setAgentId("agent-1");
        draft.setStatus("DRAFT");
        when(configMapper.selectOne(any())).thenReturn(draft);
        when(configMapper.selectById(12L)).thenReturn(draft);
        when(toolMapper.selectList(any())).thenReturn(List.of());

        RuntimeWorkflowDefinitionEntity replacementWorkflow = workflow(
                "wf-new", "new-page-assistant", "New page assistant");
        when(workflowMapper.selectById("wf-new")).thenReturn(replacementWorkflow);
        when(versionMapper.listActive("wf-new")).thenReturn(
                List.of(workflowVersion(23L, "wf-new")));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.replaceWorkflowToolInDraft(
                        "agent-1",
                        "wf-missing",
                        new RuntimeAgentConfigViews.WorkflowToolRequest(
                                "wf-new", "new_page_assistant", null, null,
                                null, "PAGE_ACTION", null, false, true, null)));

        assertEquals(
                "replaceWorkflowId is not attached to the Agent tool catalog: wf-missing",
                error.getMessage());
    }

    @Test
    void storesCatalogAttestedExactSkillVersionInAgentDraft() throws Exception {
        RuntimeAgentConfigVersionMapper configMapper = mock(RuntimeAgentConfigVersionMapper.class);
        RuntimeAgentWorkflowToolMapper toolMapper = mock(RuntimeAgentWorkflowToolMapper.class);
        RuntimeAgentSkillBindingMapper skillBindingMapper = mock(RuntimeAgentSkillBindingMapper.class);
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeWorkflowDefinitionMapper workflowMapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeWorkflowVersionMapper versionMapper = mock(RuntimeWorkflowVersionMapper.class);
        RuntimeAgentConfigService service = new RuntimeAgentConfigService(
                configMapper, toolMapper, skillBindingMapper, mock(RuntimeA2aRemoteAgentBindingMapper.class),
                agentMapper, workflowMapper, versionMapper, new ObjectMapper());

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
        when(skillBindingMapper.selectList(any())).thenReturn(List.of());

        String sourceSha = "a".repeat(64);
        String treeSha = "b".repeat(64);
        String manifest = new ObjectMapper().writeValueAsString(java.util.Map.of(
                "name", "demo-skill",
                "sourceRoot", "demo-skill/",
                "sourceSha256", sourceSha,
                "contentTreeSha256", treeSha,
                "files", List.of(java.util.Map.of(
                        "path", "SKILL.md", "size", 10, "sha256", "c".repeat(64)))));
        RuntimeAgentConfigViews.SkillBindingRequest skill =
                new RuntimeAgentConfigViews.SkillBindingRequest(
                        11L, 21L, "reachai", "demo-skill", "Demo Skill", "1.2.3",
                        "PUBLISHED", sourceSha, treeSha, "demo-skill/", manifest,
                        "{\"hasScripts\":false}", false, "MODEL_SELECTED", "DENY",
                        true, true, 3, "PUBLIC", null);

        service.saveDraft("agent-1", new RuntimeAgentConfigViews.AgentConfigDraftRequest(
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, List.of(skill)));

        ArgumentCaptor<RuntimeAgentSkillBindingEntity> saved =
                ArgumentCaptor.forClass(RuntimeAgentSkillBindingEntity.class);
        verify(skillBindingMapper).insert(saved.capture());
        assertEquals(11L, saved.getValue().getSkillId());
        assertEquals(21L, saved.getValue().getSkillVersionId());
        assertEquals("reachai", saved.getValue().getPublisher());
        assertEquals("demo-skill", saved.getValue().getStandardName());
        assertEquals("PUBLIC", saved.getValue().getVisibility());
        assertEquals(sourceSha, saved.getValue().getSourceSha256());
        assertEquals("DENY", saved.getValue().getScriptPolicy());
        assertEquals(true, saved.getValue().getRequired());
        assertEquals(0, saved.getValue().getPriority());
    }

    @Test
    void rejectsRequiredSkillThatIsDisabledBeforeAgentPublish() {
        RuntimeAgentConfigVersionMapper configMapper = mock(RuntimeAgentConfigVersionMapper.class);
        RuntimeAgentWorkflowToolMapper toolMapper = mock(RuntimeAgentWorkflowToolMapper.class);
        RuntimeAgentSkillBindingMapper skillBindingMapper = mock(RuntimeAgentSkillBindingMapper.class);
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeWorkflowDefinitionMapper workflowMapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeWorkflowVersionMapper versionMapper = mock(RuntimeWorkflowVersionMapper.class);
        RuntimeAgentConfigService service = new RuntimeAgentConfigService(
                configMapper, toolMapper, skillBindingMapper, mock(RuntimeA2aRemoteAgentBindingMapper.class),
                agentMapper, workflowMapper, versionMapper, new ObjectMapper());

        RuntimeAgentEntity agent = new RuntimeAgentEntity();
        agent.setId("agent-1");
        when(agentMapper.selectById("agent-1")).thenReturn(agent);
        RuntimeAgentConfigVersionEntity draft = new RuntimeAgentConfigVersionEntity();
        draft.setId(12L);
        draft.setAgentId("agent-1");
        draft.setStatus("DRAFT");
        draft.setRuntimeType("AGENTSCOPE");
        draft.setSystemPrompt("prompt");
        draft.setModelInstanceId("model-1");
        draft.setToolCatalogMode("ALLOW_LIST");
        when(configMapper.selectById(12L)).thenReturn(draft);
        when(toolMapper.selectList(any())).thenReturn(List.of());
        RuntimeAgentSkillBindingEntity binding = new RuntimeAgentSkillBindingEntity();
        binding.setPublisher("reachai");
        binding.setStandardName("required-skill");
        binding.setVisibility("PUBLIC");
        binding.setRequired(true);
        binding.setEnabled(false);
        when(skillBindingMapper.selectList(any())).thenReturn(List.of(binding));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.publish("agent-1", 12L, "tester"));

        assertEquals("Required Agent Skill must be enabled: reachai/required-skill", error.getMessage());
    }

    @Test
    void rejectsProjectSkillWhenAgentProjectChangedBeforePublish() {
        RuntimeAgentConfigVersionMapper configMapper = mock(RuntimeAgentConfigVersionMapper.class);
        RuntimeAgentWorkflowToolMapper toolMapper = mock(RuntimeAgentWorkflowToolMapper.class);
        RuntimeAgentSkillBindingMapper skillBindingMapper = mock(RuntimeAgentSkillBindingMapper.class);
        RuntimeAgentMapper agentMapper = mock(RuntimeAgentMapper.class);
        RuntimeWorkflowDefinitionMapper workflowMapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeWorkflowVersionMapper versionMapper = mock(RuntimeWorkflowVersionMapper.class);
        RuntimeAgentConfigService service = new RuntimeAgentConfigService(
                configMapper, toolMapper, skillBindingMapper, mock(RuntimeA2aRemoteAgentBindingMapper.class),
                agentMapper, workflowMapper, versionMapper, new ObjectMapper());

        RuntimeAgentEntity agent = new RuntimeAgentEntity();
        agent.setId("agent-1");
        agent.setProjectCode("project-b");
        when(agentMapper.selectById("agent-1")).thenReturn(agent);
        RuntimeAgentConfigVersionEntity draft = new RuntimeAgentConfigVersionEntity();
        draft.setId(12L);
        draft.setAgentId("agent-1");
        draft.setStatus("DRAFT");
        draft.setRuntimeType("AGENTSCOPE");
        draft.setSystemPrompt("prompt");
        draft.setModelInstanceId("model-1");
        draft.setToolCatalogMode("ALLOW_LIST");
        when(configMapper.selectById(12L)).thenReturn(draft);
        when(toolMapper.selectList(any())).thenReturn(List.of());
        RuntimeAgentSkillBindingEntity binding = new RuntimeAgentSkillBindingEntity();
        binding.setPublisher("reachai");
        binding.setStandardName("project-skill");
        binding.setVisibility("PROJECT");
        binding.setProjectCode("project-a");
        when(skillBindingMapper.selectList(any())).thenReturn(List.of(binding));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.publish("agent-1", 12L, "tester"));

        assertEquals("Project-scoped Agent Skill no longer matches the Agent project: reachai/project-skill",
                error.getMessage());
    }

    private RuntimeAgentWorkflowToolEntity workflowTool(
            Long id,
            String workflowId,
            Long workflowVersionId,
            String toolName,
            int priority) {
        RuntimeAgentWorkflowToolEntity tool = new RuntimeAgentWorkflowToolEntity();
        tool.setId(id);
        tool.setAgentId("agent-1");
        tool.setAgentConfigVersionId(12L);
        tool.setWorkflowId(workflowId);
        tool.setWorkflowVersionId(workflowVersionId);
        tool.setToolName(toolName);
        tool.setRiskLevel("PAGE_ACTION");
        tool.setReadOnly(false);
        tool.setEnabled(true);
        tool.setPriority(priority);
        return tool;
    }

    private RuntimeWorkflowDefinitionEntity workflow(
            String id,
            String keySlug,
            String description) {
        RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId(id);
        workflow.setKeySlug(keySlug);
        workflow.setName(description);
        workflow.setDescription(description);
        workflow.setStatus("ACTIVE");
        return workflow;
    }

    private RuntimeWorkflowVersionEntity workflowVersion(Long id, String workflowId) {
        RuntimeWorkflowVersionEntity version = new RuntimeWorkflowVersionEntity();
        version.setId(id);
        version.setWorkflowId(workflowId);
        version.setVersion("v1.0.0");
        return version;
    }
}
