package com.enterprise.ai.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowStudioServiceTest {

    @Test
    void loadStudioStateReturnsWorkflowStudioPayload() {
        WorkflowDefinitionService workflowDefinitionService = mock(WorkflowDefinitionService.class);
        WorkflowStudioService service = new WorkflowStudioService(workflowDefinitionService, new ObjectMapper());
        WorkflowDefinitionEntity current = workflow();
        when(workflowDefinitionService.findById("wf-1")).thenReturn(Optional.of(current));

        WorkflowStudioService.WorkflowStudioState state = service.getStudioState("wf-1");

        assertEquals("wf-1", state.workflowId());
        assertEquals(7L, state.projectId());
        assertEquals("orders", state.projectCode());
        assertEquals("orders-page", state.keySlug());
        assertEquals("Orders Page", state.name());
        assertEquals("{\"nodes\":[]}", state.graphSpecJson());
        assertEquals("{\"nodes\":[]}", state.canvasJson());
        assertEquals("LANGGRAPH4J", state.runtimeType());
        assertEquals("llm-main", state.defaultModelInstanceId());
        assertEquals("DRAFT", state.status());
        assertEquals("MANUAL", state.managedBy());
    }

    @Test
    void saveStudioDraftUpdatesWorkflowGraphAndCanvas() {
        WorkflowDefinitionService workflowDefinitionService = mock(WorkflowDefinitionService.class);
        WorkflowStudioService service = new WorkflowStudioService(workflowDefinitionService, new ObjectMapper());
        WorkflowDefinitionEntity current = workflow();
        when(workflowDefinitionService.findById("wf-1")).thenReturn(Optional.of(current));
        when(workflowDefinitionService.update(eq("wf-1"), any(WorkflowDefinitionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));

        WorkflowDefinitionEntity saved = service.saveStudioDraft("wf-1", new WorkflowStudioService.WorkflowStudioSaveRequest(
                "{\"nodes\":[{\"id\":\"start\"}]}",
                "{\"nodes\":[{\"id\":\"start\",\"x\":0,\"y\":0}]}",
                "{\"source\":\"studio\"}"));

        assertTrue(saved.getGraphSpecJson().contains("start"));
        assertTrue(saved.getCanvasJson().contains("\"x\":0"));
        assertEquals("{\"source\":\"studio\"}", saved.getExtraJson());
        verify(workflowDefinitionService).update(eq("wf-1"), any(WorkflowDefinitionEntity.class));
    }

    @Test
    void saveStudioDraftRejectsBlankGraphSpec() {
        WorkflowDefinitionService workflowDefinitionService = mock(WorkflowDefinitionService.class);
        WorkflowStudioService service = new WorkflowStudioService(workflowDefinitionService, new ObjectMapper());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.saveStudioDraft("wf-1",
                        new WorkflowStudioService.WorkflowStudioSaveRequest(" ", "{\"nodes\":[]}", null)));

        assertEquals("graphSpecJson is required", error.getMessage());
    }

    private WorkflowDefinitionEntity workflow() {
        WorkflowDefinitionEntity entity = new WorkflowDefinitionEntity();
        entity.setId("wf-1");
        entity.setProjectId(7L);
        entity.setProjectCode("orders");
        entity.setKeySlug("orders-page");
        entity.setName("Orders Page");
        entity.setGraphSpecJson("{\"nodes\":[]}");
        entity.setCanvasJson("{\"nodes\":[]}");
        entity.setRuntimeType("LANGGRAPH4J");
        entity.setDefaultModelInstanceId("llm-main");
        entity.setStatus("DRAFT");
        entity.setManagedBy("MANUAL");
        return entity;
    }
}
