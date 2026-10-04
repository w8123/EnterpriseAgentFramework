package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeWorkflowVersionServiceTest {
    private static final String REVISION = "2026-07-14T10:30:00";
    private final RuntimeWorkflowReleaseEventMapper releaseEvents = mock(RuntimeWorkflowReleaseEventMapper.class);

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
        when(workflowService.lockForRelease(eq("wf-1"), anyString())).thenReturn(workflow);
        when(workflowService.update(eq("wf-1"), any(RuntimeWorkflowDefinitionEntity.class), anyString()))
                .thenAnswer(inv -> {
                    RuntimeWorkflowDefinitionEntity update = inv.getArgument(1);
                    if (update.getGraphSpecJson() != null) workflow.setGraphSpecJson(update.getGraphSpecJson());
                    if (update.getCanvasJson() != null) workflow.setCanvasJson(update.getCanvasJson());
                    if (update.getStatus() != null) workflow.setStatus(update.getStatus());
                    return workflow;
                });
        when(validationService.validate(any(RuntimeWorkflowDefinitionEntity.class)))
                .thenReturn(RuntimeWorkflowReleaseValidationResult.builder().build());
        when(validationService.validateProposed(any(RuntimeWorkflowDefinitionEntity.class), any()))
                .thenReturn(RuntimeWorkflowReleaseValidationResult.builder().build());
        when(validationService.readGraph(anyString(), any(RuntimeWorkflowReleaseValidationResult.Builder.class)))
                .thenAnswer(inv -> {
                    String json = inv.getArgument(0);
                    RuntimeWorkflowReleaseValidationResult.Builder report = inv.getArgument(1);
                    return new RuntimeWorkflowReleaseValidationService(
                            mock(RuntimeControlCatalogClient.class), new ObjectMapper())
                            .readGraph(json, report);
                });

        service = new RuntimeWorkflowVersionService(versionMapper, workflowService, validationService, new ObjectMapper(), passThroughPins(), releaseEvents, mock(RuntimeWorkflowReferenceIndex.class));
    }

    @Test
    void listVersionsDelegatesToRuntimeOwnedVersionMapper() {
        RuntimeWorkflowVersionEntity version = service.publish("wf-1", "v1.0.0", 100, "first", "alice", REVISION);

        List<RuntimeWorkflowVersionEntity> versions = service.listVersions("wf-1");

        assertEquals(List.of(version), versions);
        verify(versionMapper).listByWorkflow("wf-1");
    }

    @Test
    void publishCreatesActiveWorkflowVersionSnapshot() {
        RuntimeWorkflowVersionEntity published = service.publish("wf-1", "v1.0.0", 100, "first", "alice", REVISION);

        assertEquals("wf-1", published.getWorkflowId());
        assertEquals("v1.0.0", published.getVersion());
        assertEquals("ACTIVE", published.getStatus());
        assertEquals(100, published.getRolloutPercent());
        assertEquals("{\"nodes\":[{\"id\":\"answer\",\"type\":\"ANSWER\"}],\"entryNodeId\":\"answer\"}",
                published.getGraphSpecSnapshotJson());
        assertNotNull(published.getSnapshotJson());
        verify(validationService).validate(any(RuntimeWorkflowDefinitionEntity.class));
        verify(workflowService).update(eq("wf-1"), any(RuntimeWorkflowDefinitionEntity.class), anyString());
    }

    @Test
    void publishRejectsPercentageWithoutARealVersionRouter() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.publish("wf-1", "v1.0.0", 20, "partial", "alice", REVISION));

        assertEquals(
                "rolloutPercent must be 100 until deterministic workflow version routing is implemented",
                error.getMessage());
        verify(versionMapper, never()).insert(any(RuntimeWorkflowVersionEntity.class));
    }

    @Test
    void publishRejectsReleaseValidationErrors() {
        when(validationService.validate(any(RuntimeWorkflowDefinitionEntity.class)))
                .thenReturn(RuntimeWorkflowReleaseValidationResult.builder()
                        .error("GRAPH_ENTRY_MISSING", null, "GraphSpec entry is required")
                        .build());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.publish("wf-1", "v1.0.0", 100, "bad", "alice", REVISION));

        assertEquals("workflow release validation failed: GRAPH_ENTRY_MISSING", error.getMessage());
    }

    @Test
    void publishRejectsJsonNullGraphSpecWithoutCreatingVersion() {
        RuntimeWorkflowDefinitionEntity workflow = workflowService.findById("wf-1").orElseThrow();
        workflow.setGraphSpecJson("null");
        RuntimeWorkflowReleaseValidationService actualValidation = new RuntimeWorkflowReleaseValidationService(
                mock(RuntimeControlCatalogClient.class),
                new ObjectMapper());
        RuntimeWorkflowVersionService actualService = new RuntimeWorkflowVersionService(
                versionMapper, workflowService, actualValidation, new ObjectMapper(), passThroughPins(), releaseEvents, mock(RuntimeWorkflowReferenceIndex.class));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> actualService.publish("wf-1", "v1.0.0", 100, "bad", "alice", REVISION));

        assertEquals("workflow release validation failed: GRAPH_SPEC_INVALID", error.getMessage());
        verify(versionMapper, never()).insert(any(RuntimeWorkflowVersionEntity.class));
    }

    @Test
    void publishRejectsStaleBaseRevisionBeforeValidationOrSnapshot() {
        RuntimeWorkflowDefinitionEntity workflow = workflowService.findById("wf-1").orElseThrow();
        String baseRevision = "2026-07-14T10:30:00";
        RuntimeWorkflowRevisionConflictException conflict = new RuntimeWorkflowRevisionConflictException(
                "wf-1", baseRevision, "2026-07-14T10:31:00");
        doThrow(conflict).when(workflowService).lockForRelease("wf-1", baseRevision);

        RuntimeWorkflowRevisionConflictException thrown = assertThrows(
                RuntimeWorkflowRevisionConflictException.class,
                () -> service.publish("wf-1", "v1.0.0", 100, "first", "alice", baseRevision));

        assertEquals(conflict, thrown);
        verify(validationService, never()).validate(any(RuntimeWorkflowDefinitionEntity.class));
        verify(versionMapper, never()).insert(any(RuntimeWorkflowVersionEntity.class));
    }

    @Test
    void publishUsesBaseRevisionAgainForAtomicActivation() {
        String baseRevision = "2026-07-14T10:30:00";

        service.publish("wf-1", "v1.0.0", 100, "first", "alice", baseRevision);

        verify(workflowService).lockForRelease("wf-1", baseRevision);
        verify(workflowService).update(
                eq("wf-1"),
                org.mockito.ArgumentMatchers.argThat(update -> "ACTIVE".equals(update.getStatus())),
                eq(baseRevision));
    }

    @Test
    void rollbackReactivatesSelectedVersionAndPreservesPublisherAndDraft() {
        RuntimeWorkflowVersionEntity v1 = service.publish("wf-1", "v1.0.0", 100, "first", "alice", REVISION);
        RuntimeWorkflowVersionEntity v2 = service.publish("wf-1", "v1.0.1", 100, "second", "bob", REVISION);

        RuntimeWorkflowVersionEntity rolled = service.rollback("wf-1", v1.getId(), "carol", REVISION);

        assertEquals("ACTIVE", rolled.getStatus());
        assertEquals("alice", rolled.getPublishedBy());
        RuntimeWorkflowVersionEntity second = store.stream()
                .filter(v -> v.getId().equals(v2.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("RETIRED", second.getStatus());
        verify(workflowService, org.mockito.Mockito.atLeastOnce())
                .update(eq("wf-1"), org.mockito.Mockito.argThat(update ->
                        "ACTIVE".equals(update.getStatus())
                                && update.getGraphSpecJson() == null
                                && update.getCanvasJson() == null), eq(REVISION));
        verify(validationService).validateProposed(any(RuntimeWorkflowDefinitionEntity.class), any());
    }

    @Test
    void rollbackRejectsHistoricalInteractionSnapshotBeforeAnyWrite() {
        RuntimeWorkflowVersionService actualService = realValidationService();
        RuntimeWorkflowVersionEntity historical = historicalVersion("""
                {"nodes":[{"id":"ask","type":"INTERACTION"}],"entryNodeId":"ask","exitNodeIds":["ask"]}
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> actualService.rollback("wf-1", historical.getId(), "carol", REVISION));

        assertTrue(error.getMessage().contains("workflow rollback validation failed: GRAPH_NODE_NOT_PUBLISHABLE"));
        verify(versionMapper, never()).updateById(any(RuntimeWorkflowVersionEntity.class));
        verify(versionMapper, never()).insert(any(RuntimeWorkflowVersionEntity.class));
        verify(workflowService, never()).update(eq("wf-1"), any(RuntimeWorkflowDefinitionEntity.class), anyString());
    }

    @Test
    void rollbackRejectsHistoricalCodeSnapshotBeforeAnyWrite() {
        RuntimeWorkflowVersionService actualService = realValidationService();
        RuntimeWorkflowVersionEntity historical = historicalVersion("""
                {"nodes":[{"id":"code","type":"CODE"}],"entryNodeId":"code","exitNodeIds":["code"]}
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> actualService.rollback("wf-1", historical.getId(), "carol", REVISION));

        assertTrue(error.getMessage().contains("workflow rollback validation failed: GRAPH_NODE_RUNTIME_UNSUPPORTED"));
        verify(versionMapper, never()).updateById(any(RuntimeWorkflowVersionEntity.class));
        verify(workflowService, never()).update(eq("wf-1"), any(RuntimeWorkflowDefinitionEntity.class), anyString());
    }

    @Test
    void rollbackRejectsPageActionWithoutCatalogBeforeAnyWrite() {
        RuntimeControlCatalogClient catalogClient = mock(RuntimeControlCatalogClient.class);
        when(catalogClient.getPageAction("demo", "orders", "open")).thenReturn(null);
        RuntimeWorkflowVersionService actualService = new RuntimeWorkflowVersionService(
                versionMapper, workflowService,
                new RuntimeWorkflowReleaseValidationService(catalogClient, new ObjectMapper()),
                new ObjectMapper(), passThroughPins(), releaseEvents, mock(RuntimeWorkflowReferenceIndex.class));
        RuntimeWorkflowDefinitionEntity workflow = workflowService.findById("wf-1").orElseThrow();
        workflow.setProjectCode("demo");
        RuntimeWorkflowVersionEntity historical = historicalVersion("""
                {"nodes":[{"id":"open","type":"PAGE_ACTION","config":{"projectCode":"demo","pageKey":"orders","actionKey":"open"}}],"entryNodeId":"open","exitNodeIds":["open"]}
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> actualService.rollback("wf-1", historical.getId(), "carol", REVISION));

        assertTrue(error.getMessage().contains("workflow rollback validation failed:"));
        verify(versionMapper, never()).updateById(any(RuntimeWorkflowVersionEntity.class));
        verify(workflowService, never()).update(eq("wf-1"), any(RuntimeWorkflowDefinitionEntity.class), anyString());
    }

    @Test
    void rollbackAllowsStableHistoricalSnapshot() {
        RuntimeWorkflowVersionService actualService = realValidationService();
        RuntimeWorkflowVersionEntity active = historicalVersion(
                "{\"nodes\":[{\"id\":\"answer\",\"type\":\"ANSWER\"}],\"entryNodeId\":\"answer\",\"exitNodeIds\":[\"answer\"]}");
        active.setStatus("ACTIVE");
        active.setVersion("v-active");
        RuntimeWorkflowVersionEntity historical = historicalVersion(
                "{\"nodes\":[{\"id\":\"answer\",\"type\":\"ANSWER\"}],\"entryNodeId\":\"answer\",\"exitNodeIds\":[\"answer\"]}");
        historical.setVersion("v-stable");

        RuntimeWorkflowVersionEntity rolled = actualService.rollback("wf-1", historical.getId(), "carol", REVISION);

        assertEquals("ACTIVE", rolled.getStatus());
        assertEquals("RETIRED", active.getStatus());
        verify(workflowService).update(eq("wf-1"), any(RuntimeWorkflowDefinitionEntity.class), anyString());
    }

    private RuntimeWorkflowVersionService realValidationService() {
        return new RuntimeWorkflowVersionService(
                versionMapper,
                workflowService,
                new RuntimeWorkflowReleaseValidationService(mock(RuntimeControlCatalogClient.class), new ObjectMapper()),
                new ObjectMapper(), passThroughPins(), releaseEvents, mock(RuntimeWorkflowReferenceIndex.class));
    }

    private RuntimeWorkflowVersionEntity historicalVersion(String graphSpecJson) {
        RuntimeWorkflowVersionEntity entity = new RuntimeWorkflowVersionEntity();
        entity.setId(ids.getAndIncrement());
        entity.setWorkflowId("wf-1");
        entity.setVersion("v-hist-" + entity.getId());
        entity.setStatus("RETIRED");
        entity.setSnapshotJson("{\"defaultModelInstanceId\":null,\"workflowKind\":\"GENERAL\"}");
        entity.setRolloutPercent(100);
        entity.setGraphSpecSnapshotJson(graphSpecJson);
        entity.setCanvasSnapshotJson("{\"nodes\":[]}");
        store.add(entity);
        return entity;
    }

    private RuntimeWorkflowDefinitionEntity workflow() {
        RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId("wf-1");
        workflow.setKeySlug("page-search");
        workflow.setName("Page Search");
        workflow.setGraphSpecJson("{\"nodes\":[{\"id\":\"answer\",\"type\":\"ANSWER\"}],\"entryNodeId\":\"answer\"}");
        workflow.setCanvasJson("{\"nodes\":[]}");
        workflow.setWorkflowKind("GENERAL");
        workflow.setExecutionEngine("GRAPH_SPEC");
        workflow.setDefinitionAuthority("USER");
        workflow.setCreationChannel("STUDIO");
        workflow.setStatus("DRAFT");
        return workflow;
    }

    private RuntimeCapabilityContractPins passThroughPins() {
        RuntimeCapabilityContractPins pins = org.mockito.Mockito.mock(RuntimeCapabilityContractPins.class);
        org.mockito.Mockito.when(pins.pin(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> call.getArgument(0));
        return pins;
    }
}
