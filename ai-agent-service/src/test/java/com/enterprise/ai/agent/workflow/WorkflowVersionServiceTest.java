package com.enterprise.ai.agent.workflow;

import com.enterprise.ai.agent.workflow.WorkflowReleaseValidationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowVersionServiceTest {

    private WorkflowVersionMapper versionMapper;
    private WorkflowDefinitionService workflowService;
    private WorkflowReleaseValidationService releaseValidationService;
    private WorkflowVersionService service;
    private List<WorkflowVersionEntity> store;
    private AtomicLong ids;

    @BeforeEach
    void setUp() {
        versionMapper = mock(WorkflowVersionMapper.class);
        workflowService = mock(WorkflowDefinitionService.class);
        releaseValidationService = mock(WorkflowReleaseValidationService.class);
        store = new ArrayList<>();
        ids = new AtomicLong(1);

        when(versionMapper.insert(any(WorkflowVersionEntity.class))).thenAnswer(inv -> {
            WorkflowVersionEntity entity = inv.getArgument(0);
            entity.setId(ids.getAndIncrement());
            store.add(entity);
            return 1;
        });
        when(versionMapper.updateById(any(WorkflowVersionEntity.class))).thenAnswer(inv -> {
            WorkflowVersionEntity entity = inv.getArgument(0);
            store.removeIf(v -> v.getId().equals(entity.getId()));
            store.add(entity);
            return 1;
        });
        when(versionMapper.selectById(any())).thenAnswer(inv -> {
            Object id = inv.getArgument(0);
            return store.stream().filter(v -> v.getId().equals(id)).findFirst().orElse(null);
        });
        when(versionMapper.selectOne(any())).thenReturn(null);
        when(versionMapper.listActive(anyString())).thenAnswer(inv -> {
            String workflowId = inv.getArgument(0);
            return store.stream()
                    .filter(v -> workflowId.equals(v.getWorkflowId()))
                    .filter(v -> "ACTIVE".equals(v.getStatus()))
                    .toList();
        });
        when(versionMapper.listByWorkflow(anyString())).thenAnswer(inv -> {
            String workflowId = inv.getArgument(0);
            return store.stream()
                    .filter(v -> workflowId.equals(v.getWorkflowId()))
                    .toList();
        });

        WorkflowDefinitionEntity workflow = new WorkflowDefinitionEntity();
        workflow.setId("wf-1");
        workflow.setKeySlug("page-search");
        workflow.setName("Page Search");
        workflow.setGraphSpecJson("{\"nodes\":[{\"id\":\"answer\"}]}");
        workflow.setCanvasJson("{\"nodes\":[]}");
        workflow.setRuntimeType("LANGGRAPH4J");
        workflow.setStatus("DRAFT");
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(workflowService.update(eq("wf-1"), any(WorkflowDefinitionEntity.class)))
                .thenAnswer(inv -> {
                    WorkflowDefinitionEntity update = inv.getArgument(1);
                    if (update.getGraphSpecJson() != null) workflow.setGraphSpecJson(update.getGraphSpecJson());
                    if (update.getCanvasJson() != null) workflow.setCanvasJson(update.getCanvasJson());
                    if (update.getStatus() != null) workflow.setStatus(update.getStatus());
                    return workflow;
                });
        when(releaseValidationService.validate(any(WorkflowDefinitionEntity.class)))
                .thenReturn(WorkflowReleaseValidationResult.ok());

        service = new WorkflowVersionService(versionMapper, workflowService, releaseValidationService, new ObjectMapper());
    }

    @Test
    void publishCreatesActiveWorkflowVersionSnapshot() {
        WorkflowVersionEntity published = service.publish("wf-1", "v1.0.0", 100, "first", "alice");

        assertEquals("wf-1", published.getWorkflowId());
        assertEquals("v1.0.0", published.getVersion());
        assertEquals("ACTIVE", published.getStatus());
        assertEquals(100, published.getRolloutPercent());
        assertEquals("{\"nodes\":[{\"id\":\"answer\"}]}", published.getGraphSpecSnapshotJson());
        assertNotNull(published.getSnapshotJson());
        verify(releaseValidationService).validate(any(WorkflowDefinitionEntity.class));
        verify(workflowService).update(eq("wf-1"), any(WorkflowDefinitionEntity.class));
    }

    @Test
    void publishRetiresExistingActiveVersionsWhenRolloutIsFull() {
        WorkflowVersionEntity v1 = service.publish("wf-1", "v1.0.0", 100, "first", "alice");
        service.publish("wf-1", "v1.0.1", 100, "second", "bob");

        WorkflowVersionEntity old = store.stream()
                .filter(v -> v.getId().equals(v1.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("RETIRED", old.getStatus());
    }

    @Test
    void publishRejectsInvalidRollout() {
        assertThrows(IllegalArgumentException.class,
                () -> service.publish("wf-1", "v1.0.0", -1, "bad", "alice"));
        assertThrows(IllegalArgumentException.class,
                () -> service.publish("wf-1", "v1.0.0", 101, "bad", "alice"));
    }

    @Test
    void publishRejectsReleaseValidationErrors() {
        when(releaseValidationService.validate(any(WorkflowDefinitionEntity.class)))
                .thenReturn(WorkflowReleaseValidationResult.builder()
                        .error("GRAPH_ENTRY_MISSING", null, "GraphSpec entry is required")
                        .build());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.publish("wf-1", "v1.0.0", 100, "bad", "alice"));

        assertEquals("workflow release validation failed: GRAPH_ENTRY_MISSING", error.getMessage());
    }

    @Test
    void rollbackReactivatesSelectedWorkflowVersion() {
        WorkflowVersionEntity v1 = service.publish("wf-1", "v1.0.0", 100, "first", "alice");
        WorkflowVersionEntity v2 = service.publish("wf-1", "v1.0.1", 100, "second", "bob");

        WorkflowVersionEntity rolled = service.rollback("wf-1", v1.getId(), "carol");

        assertEquals("ACTIVE", rolled.getStatus());
        WorkflowVersionEntity second = store.stream()
                .filter(v -> v.getId().equals(v2.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("RETIRED", second.getStatus());
    }

    @Test
    void rollbackRestoresWorkflowDraftFromVersionSnapshot() {
        WorkflowVersionEntity v1 = service.publish("wf-1", "v1.0.0", 100, "first", "alice");
        WorkflowDefinitionEntity changed = new WorkflowDefinitionEntity();
        changed.setGraphSpecJson("{\"nodes\":[{\"id\":\"changed\"}]}");
        changed.setCanvasJson("{\"nodes\":[{\"id\":\"changed\"}]}");
        workflowService.update("wf-1", changed);

        service.rollback("wf-1", v1.getId(), "carol");

        ArgumentCaptor<WorkflowDefinitionEntity> captor = ArgumentCaptor.forClass(WorkflowDefinitionEntity.class);
        verify(workflowService, org.mockito.Mockito.atLeastOnce()).update(eq("wf-1"), captor.capture());
        WorkflowDefinitionEntity restored = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals("{\"nodes\":[{\"id\":\"answer\"}]}", restored.getGraphSpecJson());
        assertEquals("{\"nodes\":[]}", restored.getCanvasJson());
        assertEquals("ACTIVE", restored.getStatus());
    }
}
