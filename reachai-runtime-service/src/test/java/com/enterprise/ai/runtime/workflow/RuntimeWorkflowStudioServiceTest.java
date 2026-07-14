package com.enterprise.ai.runtime.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeWorkflowStudioServiceTest {

    @Test
    void getStudioStateReturnsWorkflowCanvasAndRuntimeFields() {
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeWorkflowVersionService versionService = mock(RuntimeWorkflowVersionService.class);
        RuntimeWorkflowStudioService service = new RuntimeWorkflowStudioService(
                workflowService, versionService, new ObjectMapper());
        RuntimeWorkflowDefinitionEntity workflow = workflow("wf-1");
        RuntimeWorkflowVersionEntity active = activeVersion(workflow);
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(versionService.resolveActive("wf-1")).thenReturn(active);

        RuntimeWorkflowStudioService.WorkflowStudioState state = service.getStudioState("wf-1");

        assertEquals("wf-1", state.workflowId());
        assertEquals("wf-1", state.id());
        assertEquals("orders", state.keySlug());
        assertEquals("{\"nodes\":[]}", state.graphSpecJson());
        assertEquals("{\"viewport\":{}}", state.canvasJson());
        assertEquals("2026-07-14T09:30", state.revision());
        assertEquals("v1.0.0", state.activeVersion().version());
        assertFalse(state.hasUnpublishedChanges());
    }

    @Test
    void saveStudioDraftUpdatesMetadataGraphAndCanvasWithBaseRevision() {
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeWorkflowVersionService versionService = mock(RuntimeWorkflowVersionService.class);
        RuntimeWorkflowStudioService service = new RuntimeWorkflowStudioService(
                workflowService, versionService, new ObjectMapper());
        RuntimeWorkflowDefinitionEntity workflow = workflow("wf-1");
        RuntimeWorkflowDefinitionEntity updated = workflow("wf-1");
        updated.setName("Updated Orders");
        updated.setGraphSpecJson("{\"nodes\":[{\"id\":\"answer\",\"type\":\"ANSWER\"}],\"entry\":\"answer\"}");
        updated.setCanvasJson("{\"viewport\":{\"x\":1}}");
        updated.setUpdatedAt(LocalDateTime.of(2026, 7, 14, 9, 31));
        when(workflowService.update(eq("wf-1"), any(), eq("2026-07-14T09:30")))
                .thenReturn(updated);

        RuntimeWorkflowStudioService.WorkflowStudioState result = service.saveStudioDraft("wf-1",
                new RuntimeWorkflowStudioService.WorkflowStudioSaveRequest(
                        "{\"nodes\":[{\"id\":\"answer\",\"type\":\"ANSWER\"}],\"entry\":\"answer\"}",
                        "{\"viewport\":{\"x\":1}}",
                        "{\"draft\":true}",
                        "2026-07-14T09:30",
                        "updated-orders",
                        "Updated Orders",
                        "Updated description",
                        "CHAT",
                        "LANGGRAPH4J",
                        "{\"type\":\"object\"}",
                        "{\"type\":\"string\"}",
                        "llm-2",
                        "{\"temperature\":0.2}"));

        assertEquals("wf-1", result.id());
        assertEquals("Updated Orders", result.name());
        assertEquals("2026-07-14T09:31", result.revision());
        assertTrue(result.hasUnpublishedChanges());
        ArgumentCaptor<RuntimeWorkflowDefinitionEntity> update = ArgumentCaptor.forClass(RuntimeWorkflowDefinitionEntity.class);
        verify(workflowService).update(eq("wf-1"), update.capture(), eq("2026-07-14T09:30"));
        assertEquals("updated-orders", update.getValue().getKeySlug());
        assertEquals("Updated Orders", update.getValue().getName());
        assertEquals("llm-2", update.getValue().getDefaultModelInstanceId());
        assertEquals("{\"viewport\":{\"x\":1}}", update.getValue().getCanvasJson());
    }

    @Test
    void saveStudioDraftRejectsMissingGraphSpec() {
        RuntimeWorkflowStudioService service = new RuntimeWorkflowStudioService(
                mock(RuntimeWorkflowDefinitionService.class),
                mock(RuntimeWorkflowVersionService.class),
                new ObjectMapper());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.saveStudioDraft("wf-1", new RuntimeWorkflowStudioService.WorkflowStudioSaveRequest(null, null, null)));

        assertEquals("graphSpecJson is required", ex.getMessage());
    }

    @Test
    void saveStudioDraftRejectsJsonNullGraphSpecBeforeUpdatingWorkflow() {
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeWorkflowStudioService service = new RuntimeWorkflowStudioService(
                workflowService,
                mock(RuntimeWorkflowVersionService.class),
                new ObjectMapper());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.saveStudioDraft(
                        "wf-1",
                        new RuntimeWorkflowStudioService.WorkflowStudioSaveRequest("null", null, null)));

        assertEquals("graphSpecJson must be a JSON object", ex.getMessage());
        verify(workflowService, never()).update(eq("wf-1"), any(), any());
    }

    @Test
    void getStudioStateMarksPublishedMetadataChangesAsUnpublished() {
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeWorkflowVersionService versionService = mock(RuntimeWorkflowVersionService.class);
        RuntimeWorkflowStudioService service = new RuntimeWorkflowStudioService(
                workflowService, versionService, new ObjectMapper());
        RuntimeWorkflowDefinitionEntity workflow = workflow("wf-1");
        RuntimeWorkflowVersionEntity active = activeVersion(workflow);
        active.setSnapshotJson("{\"name\":\"Published Orders\"}");
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(versionService.resolveActive("wf-1")).thenReturn(active);

        RuntimeWorkflowStudioService.WorkflowStudioState state = service.getStudioState("wf-1");

        assertTrue(state.hasUnpublishedChanges());
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
        entity.setUpdatedAt(LocalDateTime.of(2026, 7, 14, 9, 30));
        return entity;
    }

    private RuntimeWorkflowVersionEntity activeVersion(RuntimeWorkflowDefinitionEntity workflow) {
        RuntimeWorkflowVersionEntity active = new RuntimeWorkflowVersionEntity();
        active.setId(9L);
        active.setWorkflowId(workflow.getId());
        active.setVersion("v1.0.0");
        active.setStatus("ACTIVE");
        active.setRolloutPercent(100);
        active.setGraphSpecSnapshotJson("{ \"nodes\": [] }");
        active.setCanvasSnapshotJson("{\"viewport\": {}}");
        active.setSnapshotJson("{}");
        active.setPublishedAt(LocalDateTime.of(2026, 7, 14, 9, 0));
        return active;
    }
}
