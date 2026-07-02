package com.enterprise.ai.runtime.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

class RuntimeWorkflowVersionServiceTest {

    private RuntimeWorkflowVersionMapper versionMapper;
    private RuntimeWorkflowDefinitionService workflowService;
    private RuntimeWorkflowReleaseValidationService validationService;
    private RuntimeWorkflowVersionService service;
    private List<RuntimeWorkflowVersionEntity> store;
    private AtomicLong ids;

    @BeforeEach
    void setUp() {
        versionMapper = mock(RuntimeWorkflowVersionMapper.class);
        workflowService = mock(RuntimeWorkflowDefinitionService.class);
        validationService = mock(RuntimeWorkflowReleaseValidationService.class);
        store = new ArrayList<>();
        ids = new AtomicLong(1);

        when(versionMapper.insert(any(RuntimeWorkflowVersionEntity.class))).thenAnswer(inv -> {
            RuntimeWorkflowVersionEntity entity = inv.getArgument(0);
            entity.setId(ids.getAndIncrement());
            store.add(entity);
            return 1;
        });
        when(versionMapper.updateById(any(RuntimeWorkflowVersionEntity.class))).thenAnswer(inv -> {
            RuntimeWorkflowVersionEntity entity = inv.getArgument(0);
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

        RuntimeWorkflowDefinitionEntity workflow = workflow();
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(workflowService.update(eq("wf-1"), any(RuntimeWorkflowDefinitionEntity.class)))
                .thenAnswer(inv -> {
                    RuntimeWorkflowDefinitionEntity update = inv.getArgument(1);
                    if (update.getGraphSpecJson() != null) workflow.setGraphSpecJson(update.getGraphSpecJson());
                    if (update.getCanvasJson() != null) workflow.setCanvasJson(update.getCanvasJson());
                    if (update.getStatus() != null) workflow.setStatus(update.getStatus());
                    return workflow;
                });
        when(validationService.validate(any(RuntimeWorkflowDefinitionEntity.class)))
                .thenReturn(RuntimeWorkflowReleaseValidationResult.builder().build());

        service = new RuntimeWorkflowVersionService(versionMapper, workflowService, validationService, new ObjectMapper());
    }

    @Test
    void listVersionsDelegatesToRuntimeOwnedVersionMapper() {
        RuntimeWorkflowVersionEntity version = service.publish("wf-1", "v1.0.0", 100, "first", "alice");

        List<RuntimeWorkflowVersionEntity> versions = service.listVersions("wf-1");

        assertEquals(List.of(version), versions);
        verify(versionMapper).listByWorkflow("wf-1");
    }

    @Test
    void publishCreatesActiveWorkflowVersionSnapshot() {
        RuntimeWorkflowVersionEntity published = service.publish("wf-1", "v1.0.0", 100, "first", "alice");

        assertEquals("wf-1", published.getWorkflowId());
        assertEquals("v1.0.0", published.getVersion());
        assertEquals("ACTIVE", published.getStatus());
        assertEquals(100, published.getRolloutPercent());
        assertEquals("{\"nodes\":[{\"id\":\"answer\",\"type\":\"ANSWER\"}],\"entry\":\"answer\"}",
                published.getGraphSpecSnapshotJson());
        assertNotNull(published.getSnapshotJson());
        verify(validationService).validate(any(RuntimeWorkflowDefinitionEntity.class));
        verify(workflowService).update(eq("wf-1"), any(RuntimeWorkflowDefinitionEntity.class));
    }

    @Test
    void publishRejectsReleaseValidationErrors() {
        when(validationService.validate(any(RuntimeWorkflowDefinitionEntity.class)))
                .thenReturn(RuntimeWorkflowReleaseValidationResult.builder()
                        .error("GRAPH_ENTRY_MISSING", null, "GraphSpec entry is required")
                        .build());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.publish("wf-1", "v1.0.0", 100, "bad", "alice"));

        assertEquals("workflow release validation failed: GRAPH_ENTRY_MISSING", error.getMessage());
    }

    @Test
    void rollbackReactivatesSelectedVersionAndRestoresWorkflowSnapshots() {
        RuntimeWorkflowVersionEntity v1 = service.publish("wf-1", "v1.0.0", 100, "first", "alice");
        RuntimeWorkflowVersionEntity v2 = service.publish("wf-1", "v1.0.1", 100, "second", "bob");

        RuntimeWorkflowVersionEntity rolled = service.rollback("wf-1", v1.getId(), "carol");

        assertEquals("ACTIVE", rolled.getStatus());
        assertEquals("carol", rolled.getPublishedBy());
        RuntimeWorkflowVersionEntity second = store.stream()
                .filter(v -> v.getId().equals(v2.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("RETIRED", second.getStatus());
        verify(workflowService, org.mockito.Mockito.atLeastOnce())
                .update(eq("wf-1"), org.mockito.Mockito.argThat(update ->
                        "ACTIVE".equals(update.getStatus())
                                && v1.getGraphSpecSnapshotJson().equals(update.getGraphSpecJson())
                                && v1.getCanvasSnapshotJson().equals(update.getCanvasJson())));
    }

    private RuntimeWorkflowDefinitionEntity workflow() {
        RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId("wf-1");
        workflow.setKeySlug("page-search");
        workflow.setName("Page Search");
        workflow.setGraphSpecJson("{\"nodes\":[{\"id\":\"answer\",\"type\":\"ANSWER\"}],\"entry\":\"answer\"}");
        workflow.setCanvasJson("{\"nodes\":[]}");
        workflow.setRuntimeType("LANGGRAPH4J");
        workflow.setStatus("DRAFT");
        return workflow;
    }
}
