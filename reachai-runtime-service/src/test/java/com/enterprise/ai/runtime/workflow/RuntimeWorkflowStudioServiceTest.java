package com.enterprise.ai.runtime.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeWorkflowStudioServiceTest {

    @Test
    void getStudioStateReturnsWorkflowCanvasAndRuntimeFields() {
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeWorkflowStudioService service = new RuntimeWorkflowStudioService(workflowService, new ObjectMapper());
        RuntimeWorkflowDefinitionEntity workflow = workflow("wf-1");
        when(workflowService.findById("wf-1")).thenReturn(java.util.Optional.of(workflow));

        RuntimeWorkflowStudioService.WorkflowStudioState state = service.getStudioState("wf-1");

        assertEquals("wf-1", state.workflowId());
        assertEquals("orders", state.keySlug());
        assertEquals("{\"nodes\":[]}", state.graphSpecJson());
        assertEquals("{\"viewport\":{}}", state.canvasJson());
    }

    @Test
    void saveStudioDraftRequiresGraphSpecAndDelegatesUpdate() {
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeWorkflowStudioService service = new RuntimeWorkflowStudioService(workflowService, new ObjectMapper());
        RuntimeWorkflowDefinitionEntity workflow = workflow("wf-1");
        when(workflowService.findById("wf-1")).thenReturn(java.util.Optional.of(workflow));
        RuntimeWorkflowDefinitionEntity updated = workflow("wf-1");
        updated.setGraphSpecJson("{\"nodes\":[{\"id\":\"answer\",\"type\":\"ANSWER\"}],\"entry\":\"answer\"}");
        when(workflowService.update(org.mockito.Mockito.eq("wf-1"), org.mockito.Mockito.any()))
                .thenReturn(updated);

        RuntimeWorkflowDefinitionEntity result = service.saveStudioDraft("wf-1",
                new RuntimeWorkflowStudioService.WorkflowStudioSaveRequest(
                        "{\"nodes\":[{\"id\":\"answer\",\"type\":\"ANSWER\"}],\"entry\":\"answer\"}",
                        "{\"viewport\":{\"x\":1}}",
                        "{\"draft\":true}"));

        assertEquals(updated, result);
        verify(workflowService).update(org.mockito.Mockito.eq("wf-1"), org.mockito.Mockito.any());
    }

    @Test
    void saveStudioDraftRejectsMissingGraphSpec() {
        RuntimeWorkflowStudioService service = new RuntimeWorkflowStudioService(
                mock(RuntimeWorkflowDefinitionService.class), new ObjectMapper());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.saveStudioDraft("wf-1", new RuntimeWorkflowStudioService.WorkflowStudioSaveRequest(null, null, null)));

        assertEquals("graphSpecJson is required", ex.getMessage());
    }

    private RuntimeWorkflowDefinitionEntity workflow(String id) {
        RuntimeWorkflowDefinitionEntity entity = new RuntimeWorkflowDefinitionEntity();
        entity.setId(id);
        entity.setProjectId(7L);
        entity.setProjectCode("demo");
        entity.setKeySlug("orders");
        entity.setName("Orders");
        entity.setDescription("Order workflow");
        entity.setWorkflowType("CHAT");
        entity.setRuntimeType("LANGGRAPH4J");
        entity.setDefaultModelInstanceId("llm-1");
        entity.setStatus("DRAFT");
        entity.setManagedBy("MANUAL");
        entity.setGraphSpecJson("{\"nodes\":[]}");
        entity.setCanvasJson("{\"viewport\":{}}");
        return entity;
    }
}
