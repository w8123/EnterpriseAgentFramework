package com.enterprise.ai.agent.workflow;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.identity.PageActionRegistryEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowActionReferenceServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void findsPageActionReferencesFromActiveWorkflowVersionAndBindingContext() throws Exception {
        WorkflowDefinitionMapper workflowMapper = mock(WorkflowDefinitionMapper.class);
        WorkflowVersionMapper versionMapper = mock(WorkflowVersionMapper.class);
        AgentWorkflowBindingMapper bindingMapper = mock(AgentWorkflowBindingMapper.class);
        AgentEntryMapper agentMapper = mock(AgentEntryMapper.class);
        WorkflowActionReferenceService service = new WorkflowActionReferenceService(
                workflowMapper,
                versionMapper,
                bindingMapper,
                agentMapper,
                objectMapper);

        PageActionRegistryEntity action = new PageActionRegistryEntity();
        action.setProjectCode("team-system");
        action.setPageKey("teamArchive.list");
        action.setActionKey("teamArchive.search");

        WorkflowDefinitionEntity workflow = new WorkflowDefinitionEntity();
        workflow.setId("workflow-1");
        workflow.setProjectCode("team-system");
        workflow.setKeySlug("team-archive-workflow");
        workflow.setName("班组档案页面流程");
        workflow.setStatus("ACTIVE");
        workflow.setGraphSpecJson(objectMapper.writeValueAsString(GraphSpec.builder()
                .node(GraphSpec.Node.builder()
                        .id("draft-node")
                        .type("PAGE_ACTION")
                        .config(Map.of(
                                "projectCode", "team-system",
                                "pageKey", "teamArchive.list",
                                "actionKey", "teamArchive.openDetail"))
                        .build())
                .build()));

        WorkflowVersionEntity activeVersion = new WorkflowVersionEntity();
        activeVersion.setId(9L);
        activeVersion.setWorkflowId("workflow-1");
        activeVersion.setVersion("v9");
        activeVersion.setStatus("ACTIVE");
        activeVersion.setGraphSpecSnapshotJson(objectMapper.writeValueAsString(GraphSpec.builder()
                .node(GraphSpec.Node.builder()
                        .id("node-page-action")
                        .type("PAGE_ACTION")
                        .name("查询班组档案")
                        .config(Map.of(
                                "projectCode", "team-system",
                                "pageKey", "teamArchive.list",
                                "actionKey", "teamArchive.search"))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("node-other-page-action")
                        .type("PAGE_ACTION")
                        .config(Map.of(
                                "projectCode", "team-system",
                                "pageKey", "teamArchive.detail",
                                "actionKey", "teamArchive.search"))
                        .build())
                .build()));

        AgentWorkflowBindingEntity binding = new AgentWorkflowBindingEntity();
        binding.setId(7L);
        binding.setAgentId("agent-1");
        binding.setWorkflowId("workflow-1");
        binding.setBindingType("PAGE");
        binding.setPageKey("teamArchive.list");
        binding.setEnabled(true);

        AgentEntryEntity agent = new AgentEntryEntity();
        agent.setId("agent-1");
        agent.setKeySlug("team-global-ai");
        agent.setName("班组全局助手");
        agent.setProjectCode("team-system");
        agent.setEnabled(true);

        when(workflowMapper.selectList(any())).thenReturn(List.of(workflow));
        when(versionMapper.selectList(any())).thenReturn(List.of(activeVersion));
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding));
        when(agentMapper.selectById("agent-1")).thenReturn(agent);

        List<WorkflowActionReferenceService.PageActionWorkflowReference> references =
                service.findReferences(action);

        assertEquals(1, references.size());
        WorkflowActionReferenceService.PageActionWorkflowReference reference = references.get(0);
        assertEquals("workflow-1", reference.workflowId());
        assertEquals("team-archive-workflow", reference.workflowKeySlug());
        assertEquals("班组档案页面流程", reference.workflowName());
        assertEquals("ACTIVE", reference.workflowStatus());
        assertEquals(9L, reference.workflowVersionId());
        assertEquals("v9", reference.workflowVersion());
        assertEquals("ACTIVE_VERSION", reference.graphSource());
        assertEquals("node-page-action", reference.nodeId());
        assertEquals("查询班组档案", reference.nodeName());
        assertEquals("teamArchive.list", reference.pageKey());
        assertEquals("teamArchive.search", reference.actionKey());
        assertEquals("agent-1", reference.entryAgentId());
        assertEquals("team-global-ai", reference.entryAgentKeySlug());
        assertEquals("班组全局助手", reference.entryAgentName());
        assertEquals(7L, reference.bindingId());
        assertEquals("PAGE", reference.bindingType());
        assertTrue(reference.bindingEnabled());
    }
}
