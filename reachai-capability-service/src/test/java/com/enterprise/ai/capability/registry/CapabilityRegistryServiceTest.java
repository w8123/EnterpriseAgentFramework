package com.enterprise.ai.capability.registry;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolMapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import com.enterprise.ai.agent.registry.ProjectInstanceEntity;
import com.enterprise.ai.agent.registry.ProjectInstanceMapper;
import com.enterprise.ai.agent.registry.CapabilitySyncLogEntity;
import com.enterprise.ai.agent.registry.CapabilitySyncLogMapper;
import com.enterprise.ai.agent.registry.CapabilityApplyRecordEntity;
import com.enterprise.ai.agent.registry.CapabilityApplyRecordMapper;
import com.enterprise.ai.agent.registry.CapabilityDiffItemEntity;
import com.enterprise.ai.agent.registry.CapabilityDiffItemMapper;
import com.enterprise.ai.agent.registry.CapabilitySnapshotEntity;
import com.enterprise.ai.agent.registry.CapabilitySnapshotMapper;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityRegistration;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityDiffItemDTO;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityReviewRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilitySnapshotDTO;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilitySyncRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilitySyncResponse;
import com.enterprise.ai.agent.registry.RegistryContracts.InstanceHeartbeatRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.InstanceHeartbeatResponse;
import com.enterprise.ai.agent.registry.RegistryContracts.ProjectRegisterRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.RegistryProjectResponse;
import com.enterprise.ai.agent.registry.RegistryContracts.RuntimeGovernancePolicyUpdateRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.SdkCapabilityDescriptionSettings;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityRegistryServiceTest {

    private final ScanProjectMapper scanProjectMapper = mock(ScanProjectMapper.class);
    private final ScanProjectToolMapper scanProjectToolMapper = mock(ScanProjectToolMapper.class);
    private final ToolDefinitionMapper toolDefinitionMapper = mock(ToolDefinitionMapper.class);
    private final ProjectInstanceMapper instanceMapper = mock(ProjectInstanceMapper.class);
    private final CapabilitySyncLogMapper syncLogMapper = mock(CapabilitySyncLogMapper.class);
    private final CapabilitySnapshotMapper snapshotMapper = mock(CapabilitySnapshotMapper.class);
    private final CapabilityDiffItemMapper diffItemMapper = mock(CapabilityDiffItemMapper.class);
    private final CapabilityApplyRecordMapper applyRecordMapper = mock(CapabilityApplyRecordMapper.class);
    private final RegistrySecurityService registrySecurityService = mock(RegistrySecurityService.class);
    private final CapabilityRegistryService service = new CapabilityRegistryService(
            scanProjectMapper,
            scanProjectToolMapper,
            toolDefinitionMapper,
            instanceMapper,
            syncLogMapper,
            snapshotMapper,
            diffItemMapper,
            applyRecordMapper,
            registrySecurityService,
            new ObjectMapper()
    );

    @Test
    void registersProjectIntoScanProjectAndCredentialTables() {
        AtomicReference<ScanProjectEntity> inserted = new AtomicReference<>();
        when(scanProjectMapper.selectOne(any())).thenReturn(null);
        when(scanProjectMapper.insert(any())).thenAnswer(invocation -> {
            ScanProjectEntity entity = invocation.getArgument(0);
            entity.setId(42L);
            inserted.set(entity);
            return 1;
        });

        RegistryProjectResponse response = service.registerProject(new ProjectRegisterRequest(
                "Orders API",
                "Orders",
                "dev",
                "platform",
                "SHARED",
                "http://orders.local",
                "/orders",
                "app-key",
                "app-secret",
                List.of("http://localhost:5173"),
                List.of("agent-1"),
                300,
                Map.of("source", "test")
        ));

        assertEquals(42L, response.projectId());
        assertEquals("orders-api", response.projectCode());
        assertEquals("Orders", response.name());
        ScanProjectEntity project = inserted.get();
        assertNotNull(project);
        assertEquals("orders-api", project.getProjectCode());
        assertEquals("REGISTERED", project.getProjectKind());
        assertEquals("auto", project.getScanType());
        verify(registrySecurityService).upsertCredential(42L, "orders-api", "app-key", "app-secret");
        verify(registrySecurityService).updateEmbedPolicy(
                "orders-api",
                "app-key",
                List.of("http://localhost:5173"),
                List.of("agent-1"),
                300
        );
    }

    @Test
    void recordsHeartbeatIntoProjectInstanceTable() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        project.setBaseUrl("http://orders.default");
        when(scanProjectMapper.selectOne(any())).thenReturn(project);
        when(instanceMapper.selectOne(any())).thenReturn(null);
        when(instanceMapper.insert(any())).thenAnswer(invocation -> {
            ProjectInstanceEntity entity = invocation.getArgument(0);
            entity.setId(11L);
            return 1;
        });

        InstanceHeartbeatResponse response = service.heartbeat("orders", new InstanceHeartbeatRequest(
                "dev-1",
                null,
                "orders-host",
                8080,
                "1.0.0",
                "0.3.0",
                Map.of("zone", "dev")
        ));

        ProjectInstanceEntity instance = response.instance();
        assertEquals(11L, instance.getId());
        assertEquals(7L, instance.getProjectId());
        assertEquals("orders", instance.getProjectCode());
        assertEquals("dev-1", instance.getInstanceId());
        assertEquals("http://orders.default", instance.getBaseUrl());
        assertEquals("ONLINE", instance.getStatus());
        assertEquals(Boolean.FALSE, response.policy().disabled());
        assertEquals("ONLINE", response.policy().status());
        assertEquals(Boolean.TRUE, response.policy().allowEmbeddedExecution());
        assertEquals(Boolean.TRUE, response.policy().allowHybridExecution());
    }

    @Test
    void returnsSdkDescriptionSettingsWithoutSourceOnlyJavadocEntries() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        project.setScanSettings("""
                {
                  "descriptionSourceOrder":["JAVADOC","SWAGGER_API_OPERATION","METHOD_NAME"],
                  "paramDescriptionSourceOrder":["JAVADOC_PARAM","SCHEMA_ANNO","FIELD_NAME"],
                  "descriptionSourceEnabled":{"SWAGGER_API_OPERATION":false,"METHOD_NAME":true},
                  "paramDescriptionSourceEnabled":{"SCHEMA_ANNO":true,"FIELD_NAME":false}
                }
                """);
        when(scanProjectMapper.selectOne(any())).thenReturn(project);

        SdkCapabilityDescriptionSettings settings = service.getSdkCapabilityDescriptionSettings("orders");

        assertEquals(List.of("SWAGGER_API_OPERATION", "METHOD_NAME"), settings.descriptionSourceOrder());
        assertEquals(List.of("SCHEMA_ANNO", "FIELD_NAME"), settings.paramDescriptionSourceOrder());
        assertEquals(Boolean.FALSE, settings.descriptionSourceEnabled().get("SWAGGER_API_OPERATION"));
        assertEquals(Boolean.TRUE, settings.descriptionSourceEnabled().get("METHOD_NAME"));
        assertEquals(Boolean.TRUE, settings.paramDescriptionSourceEnabled().get("SCHEMA_ANNO"));
        assertEquals(Boolean.FALSE, settings.paramDescriptionSourceEnabled().get("FIELD_NAME"));
    }

    @Test
    void marksInstanceOfflineWhenSdkReportsShutdown() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        ProjectInstanceEntity instance = new ProjectInstanceEntity();
        instance.setId(11L);
        instance.setProjectCode("orders");
        instance.setInstanceId("dev-1");
        instance.setStatus("ONLINE");
        when(scanProjectMapper.selectOne(any())).thenReturn(project);
        when(instanceMapper.selectOne(any())).thenReturn(instance);

        service.offline("orders", "dev-1");

        assertEquals("OFFLINE", instance.getStatus());
        assertNotNull(instance.getLastHeartbeatAt());
        verify(instanceMapper).updateById(instance);
    }

    @Test
    void updatesInstanceStatusWithNormalizedValue() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        ProjectInstanceEntity instance = new ProjectInstanceEntity();
        instance.setId(11L);
        instance.setProjectCode("orders");
        instance.setInstanceId("dev-1");
        instance.setStatus("ONLINE");
        when(scanProjectMapper.selectOne(any())).thenReturn(project);
        when(instanceMapper.selectOne(any())).thenReturn(instance);

        ProjectInstanceEntity updated = service.updateInstanceStatus("orders", "dev-1", "disabled");

        assertEquals(instance, updated);
        assertEquals("DISABLED", instance.getStatus());
        verify(instanceMapper).updateById(instance);
    }

    @Test
    void mergesInstanceGovernancePolicyAndReflectsDisabledStatus() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        ProjectInstanceEntity instance = new ProjectInstanceEntity();
        instance.setId(11L);
        instance.setProjectCode("orders");
        instance.setInstanceId("dev-1");
        instance.setStatus("ONLINE");
        when(scanProjectMapper.selectOne(any())).thenReturn(project);
        when(instanceMapper.selectOne(any())).thenReturn(instance);

        ProjectInstanceEntity updated = service.updateInstanceGovernancePolicy("orders",
                new RuntimeGovernancePolicyUpdateRequest("dev-1", true, "0.4.0", false, true, "paused"));

        assertEquals(instance, updated);
        assertEquals("DISABLED", instance.getStatus());
        assertTrue(instance.getGovernancePolicyJson().contains("\"disabled\":true"));
        assertTrue(instance.getGovernancePolicyJson().contains("\"minSdkVersion\":\"0.4.0\""));
        verify(instanceMapper).updateById(instance);
    }

    @Test
    void purgesOfflineAndStaleInstancesForProject() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        ProjectInstanceEntity offline = new ProjectInstanceEntity();
        offline.setId(11L);
        offline.setStatus("OFFLINE");
        ProjectInstanceEntity stale = new ProjectInstanceEntity();
        stale.setId(12L);
        stale.setStatus("STALE");
        when(scanProjectMapper.selectOne(any())).thenReturn(project);
        when(instanceMapper.selectList(any())).thenReturn(List.of(offline, stale));

        int removed = service.purgeOfflineInstances("orders", 10);

        assertEquals(2, removed);
        verify(instanceMapper).deleteById(11L);
        verify(instanceMapper).deleteById(12L);
    }

    @Test
    void createsCapabilitySnapshotAndDiffItemsWithoutApplyingCatalogChanges() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        when(scanProjectMapper.selectOne(any())).thenReturn(project);
        when(scanProjectToolMapper.selectOne(any())).thenReturn(null);
        when(scanProjectToolMapper.selectList(any())).thenReturn(List.of());
        ToolDefinitionEntity existing = new ToolDefinitionEntity();
        existing.setId(12L);
        existing.setName("orders_create_order");
        existing.setQualifiedName("orders:createOrder");
        existing.setDescription("Old description");
        existing.setHttpMethod("POST");
        existing.setEnabled(true);
        existing.setAgentVisible(true);
        when(toolDefinitionMapper.selectOne(any())).thenReturn(existing);
        AtomicReference<CapabilitySnapshotEntity> insertedSnapshot = new AtomicReference<>();
        AtomicReference<CapabilityDiffItemEntity> insertedDiffItem = new AtomicReference<>();
        AtomicReference<CapabilitySyncLogEntity> insertedLog = new AtomicReference<>();
        when(snapshotMapper.insert(any())).thenAnswer(invocation -> {
            CapabilitySnapshotEntity entity = invocation.getArgument(0);
            entity.setId(21L);
            insertedSnapshot.set(entity);
            return 1;
        });
        when(diffItemMapper.insert(any())).thenAnswer(invocation -> {
            CapabilityDiffItemEntity entity = invocation.getArgument(0);
            entity.setId(31L);
            insertedDiffItem.set(entity);
            return 1;
        });
        when(syncLogMapper.insert(any())).thenAnswer(invocation -> {
            insertedLog.set(invocation.getArgument(0));
            return 1;
        });

        CapabilitySyncResponse response = service.diff("orders", new CapabilitySyncRequest(
                "sync-1",
                "SDK",
                true,
                List.of(new CapabilityRegistration(
                        "createOrder",
                        "Create order",
                        "New description",
                        "POST",
                        "http://orders.local",
                        "/orders",
                        "/create",
                        "JSON",
                        "JSON",
                        "WRITE",
                        true,
                        true,
                        true,
                        "PROJECT",
                        List.of(),
                        Map.of("source", "sdk")
                ))
        ));

        assertEquals("sync-1", response.syncId());
        assertEquals(7L, response.projectId());
        assertEquals("orders", response.projectCode());
        assertEquals(1, response.received());
        assertEquals(0, response.added());
        assertEquals(1, response.changed());
        assertEquals(0, response.unchanged());
        assertEquals(0, response.applied());
        assertEquals(1, response.items().size());
        assertEquals("CHANGED", response.items().get(0).changeType());
        assertEquals(12L, response.items().get(0).existingToolId());
        assertEquals("description", response.items().get(0).fieldDiffs().get(0).field());

        CapabilitySnapshotEntity snapshot = insertedSnapshot.get();
        assertNotNull(snapshot);
        assertEquals("PENDING", snapshot.getStatus());
        assertEquals(1, snapshot.getReceived());
        CapabilityDiffItemEntity diffItem = insertedDiffItem.get();
        assertNotNull(diffItem);
        assertEquals(21L, diffItem.getSnapshotId());
        assertEquals("orders:createOrder", diffItem.getQualifiedName());
        assertEquals("CHANGED", diffItem.getChangeType());
        assertEquals("PENDING", diffItem.getReviewStatus());
        CapabilitySyncLogEntity log = insertedLog.get();
        assertNotNull(log);
        assertEquals("DIFFED", log.getStatus());
        assertEquals("sync-1", log.getSyncId());
    }

    @Test
    void syncAppliesSdkCapabilitiesIntoApiCatalogRows() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        project.setBaseUrl("http://orders.default");
        project.setContextPath("/orders");
        project.setVisibility("PROJECT");
        when(scanProjectMapper.selectOne(any())).thenReturn(project);
        when(scanProjectToolMapper.selectOne(any())).thenReturn(null);
        when(scanProjectToolMapper.selectList(any())).thenReturn(List.of());
        when(toolDefinitionMapper.selectOne(any())).thenReturn(null);
        AtomicReference<ScanProjectToolEntity> insertedTool = new AtomicReference<>();
        AtomicReference<CapabilityDiffItemEntity> updatedDiffItem = new AtomicReference<>();
        when(scanProjectToolMapper.insert(any())).thenAnswer(invocation -> {
            ScanProjectToolEntity entity = invocation.getArgument(0);
            entity.setId(51L);
            insertedTool.set(entity);
            return 1;
        });
        when(snapshotMapper.insert(any())).thenAnswer(invocation -> {
            CapabilitySnapshotEntity entity = invocation.getArgument(0);
            entity.setId(21L);
            return 1;
        });
        when(diffItemMapper.insert(any())).thenAnswer(invocation -> {
            CapabilityDiffItemEntity entity = invocation.getArgument(0);
            entity.setId(31L);
            return 1;
        });
        when(diffItemMapper.updateById(any())).thenAnswer(invocation -> {
            updatedDiffItem.set(invocation.getArgument(0));
            return 1;
        });

        CapabilitySyncResponse response = service.sync("orders", new CapabilitySyncRequest(
                "sync-2",
                "SDK",
                null,
                List.of(newCapabilityRegistration())
        ));

        assertEquals(1, response.added());
        assertEquals(1, response.applied());
        ScanProjectToolEntity tool = insertedTool.get();
        assertNotNull(tool);
        assertEquals(7L, tool.getProjectId());
        assertEquals("orders_createOrder", tool.getName());
        assertEquals("sdk:orders:createOrder", tool.getSourceLocation());
        assertEquals("POST", tool.getHttpMethod());
        assertEquals("http://orders.local", tool.getBaseUrl());
        assertEquals(Boolean.FALSE, tool.getRemovedFromSource());
        assertEquals("APPLIED", updatedDiffItem.get().getReviewStatus());
    }

    @Test
    void syncMarksCatalogRowsMissingFromSdkReportAsRemoved() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        when(scanProjectMapper.selectOne(any())).thenReturn(project);
        ScanProjectToolEntity staleTool = new ScanProjectToolEntity();
        staleTool.setId(51L);
        staleTool.setProjectId(7L);
        staleTool.setName("orders_oldCapability");
        staleTool.setSourceLocation("sdk:orders:oldCapability");
        staleTool.setGlobalToolDefinitionId(12L);
        staleTool.setRemovedFromSource(false);
        when(scanProjectToolMapper.selectList(any())).thenReturn(List.of(staleTool));
        when(snapshotMapper.insert(any())).thenAnswer(invocation -> {
            CapabilitySnapshotEntity entity = invocation.getArgument(0);
            entity.setId(21L);
            return 1;
        });
        when(diffItemMapper.insert(any())).thenAnswer(invocation -> {
            CapabilityDiffItemEntity entity = invocation.getArgument(0);
            entity.setId(31L);
            return 1;
        });
        AtomicReference<ScanProjectToolEntity> removed = new AtomicReference<>();
        when(scanProjectToolMapper.updateById(any())).thenAnswer(invocation -> {
            removed.set(invocation.getArgument(0));
            return 1;
        });

        CapabilitySyncResponse response = service.sync("orders", new CapabilitySyncRequest(
                "sync-3",
                "SDK",
                true,
                List.of()
        ));

        assertEquals(0, response.received());
        assertEquals(1, response.items().size());
        assertEquals("DELETED", response.items().get(0).changeType());
        assertEquals(1, response.applied());
        ScanProjectToolEntity removedTool = removed.get();
        assertNotNull(removedTool);
        assertEquals(Boolean.TRUE, removedTool.getRemovedFromSource());
        assertNotNull(removedTool.getRemovedAt());
    }

    @Test
    void listsCapabilitySnapshotsFromCapabilityOwnedTables() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        when(scanProjectMapper.selectOne(any())).thenReturn(project);
        CapabilitySnapshotEntity snapshot = new CapabilitySnapshotEntity();
        snapshot.setId(21L);
        snapshot.setProjectId(7L);
        snapshot.setProjectCode("orders");
        snapshot.setSyncId("sync-1");
        snapshot.setSource("SDK");
        snapshot.setStatus("PENDING");
        snapshot.setReceived(3);
        snapshot.setAdded(1);
        snapshot.setChanged(1);
        snapshot.setUnchanged(1);
        snapshot.setDeleted(0);
        snapshot.setCreatedAt(LocalDateTime.of(2026, 6, 29, 10, 0));
        snapshot.setUpdatedAt(LocalDateTime.of(2026, 6, 29, 10, 5));
        when(snapshotMapper.selectList(any())).thenReturn(List.of(snapshot));

        List<CapabilitySnapshotDTO> snapshots = service.listSnapshots("orders");

        assertEquals(1, snapshots.size());
        CapabilitySnapshotDTO dto = snapshots.get(0);
        assertEquals(21L, dto.id());
        assertEquals("orders", dto.projectCode());
        assertEquals("sync-1", dto.syncId());
        assertEquals("PENDING", dto.status());
        assertEquals(3, dto.received());
    }

    @Test
    void listsCapabilityDiffItemsFromCapabilityOwnedTables() {
        CapabilityDiffItemEntity item = newDiffItem();
        when(diffItemMapper.selectList(any())).thenReturn(List.of(item));

        List<CapabilityDiffItemDTO> items = service.listDiffItems(21L);

        assertEquals(1, items.size());
        CapabilityDiffItemDTO dto = items.get(0);
        assertEquals(31L, dto.id());
        assertEquals(21L, dto.snapshotId());
        assertEquals("orders:createOrder", dto.qualifiedName());
        assertEquals("ADDED", dto.changeType());
        assertEquals("PENDING", dto.reviewStatus());
    }

    @Test
    void ignoresCapabilityDiffItemAndRecordsReviewDecision() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        CapabilitySnapshotEntity snapshot = new CapabilitySnapshotEntity();
        snapshot.setId(21L);
        snapshot.setProjectId(7L);
        snapshot.setProjectCode("orders");
        CapabilityDiffItemEntity item = newDiffItem();
        AtomicReference<CapabilityDiffItemEntity> updated = new AtomicReference<>();
        AtomicReference<CapabilityApplyRecordEntity> insertedRecord = new AtomicReference<>();
        when(diffItemMapper.selectById(31L)).thenReturn(item);
        when(snapshotMapper.selectById(21L)).thenReturn(snapshot);
        when(scanProjectMapper.selectOne(any())).thenReturn(project);
        when(diffItemMapper.updateById(any())).thenAnswer(invocation -> {
            updated.set(invocation.getArgument(0));
            return 1;
        });
        when(applyRecordMapper.insert(any())).thenAnswer(invocation -> {
            insertedRecord.set(invocation.getArgument(0));
            return 1;
        });

        CapabilityDiffItemDTO dto = service.reviewDiffItem(
                31L,
                new CapabilityReviewRequest("IGNORE", "alice", "not ready")
        );

        assertEquals("IGNORED", dto.reviewStatus());
        assertEquals("not ready", dto.reviewNote());
        assertEquals("IGNORED", updated.get().getReviewStatus());
        CapabilityApplyRecordEntity record = insertedRecord.get();
        assertNotNull(record);
        assertEquals(21L, record.getSnapshotId());
        assertEquals(31L, record.getDiffItemId());
        assertEquals("IGNORE", record.getAction());
        assertEquals("SUCCESS", record.getStatus());
        assertEquals("alice", record.getOperator());
    }

    @Test
    void reviewApplyAppliesDiffItemIntoApiCatalogRows() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        CapabilitySnapshotEntity snapshot = new CapabilitySnapshotEntity();
        snapshot.setId(21L);
        snapshot.setProjectCode("orders");
        CapabilityDiffItemEntity item = newDiffItem();
        when(diffItemMapper.selectById(31L)).thenReturn(item);
        when(snapshotMapper.selectById(21L)).thenReturn(snapshot);
        when(scanProjectMapper.selectOne(any())).thenReturn(project);

        when(scanProjectToolMapper.selectOne(any())).thenReturn(null);
        when(scanProjectToolMapper.insert(any())).thenAnswer(invocation -> 1);
        snapshot.setPayloadJson("{\"syncId\":\"sync-1\",\"source\":\"SDK\",\"apply\":false,\"capabilities\":["
                + "{\"name\":\"createOrder\",\"description\":\"Create order\",\"httpMethod\":\"POST\","
                + "\"baseUrl\":\"http://orders.local\",\"contextPath\":\"/orders\",\"endpointPath\":\"/create\","
                + "\"requestBodyType\":\"JSON\",\"responseType\":\"JSON\",\"enabled\":true,"
                + "\"agentVisible\":true,\"lightweightEnabled\":true,\"visibility\":\"PROJECT\"}]}");

        CapabilityDiffItemDTO dto = service.reviewDiffItem(
                31L,
                new CapabilityReviewRequest("APPLY", "alice", "apply now")
        );

        assertEquals("APPLIED", dto.reviewStatus());
        verify(scanProjectToolMapper).insert(any());
        verify(diffItemMapper).updateById(any());
        verify(applyRecordMapper).insert(any());
    }

    @Test
    void reviewApplyMarksDeletedDiffItemAsRemovedFromApiCatalog() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        CapabilitySnapshotEntity snapshot = new CapabilitySnapshotEntity();
        snapshot.setId(21L);
        snapshot.setProjectCode("orders");
        snapshot.setPayloadJson("{\"syncId\":\"sync-1\",\"source\":\"SDK\",\"apply\":false,\"capabilities\":[]}");
        CapabilityDiffItemEntity item = newDiffItem();
        item.setChangeType("DELETED");
        item.setQualifiedName("orders:oldCapability");
        item.setName("oldCapability");
        item.setStorageName("orders_oldCapability");
        ScanProjectToolEntity staleTool = new ScanProjectToolEntity();
        staleTool.setId(51L);
        staleTool.setProjectId(7L);
        staleTool.setName("orders_oldCapability");
        staleTool.setSourceLocation("sdk:orders:oldCapability");
        when(diffItemMapper.selectById(31L)).thenReturn(item);
        when(snapshotMapper.selectById(21L)).thenReturn(snapshot);
        when(scanProjectMapper.selectOne(any())).thenReturn(project);
        when(scanProjectToolMapper.selectOne(any())).thenReturn(staleTool);
        AtomicReference<ScanProjectToolEntity> removed = new AtomicReference<>();
        when(scanProjectToolMapper.updateById(any())).thenAnswer(invocation -> {
            removed.set(invocation.getArgument(0));
            return 1;
        });

        CapabilityDiffItemDTO dto = service.reviewDiffItem(
                31L,
                new CapabilityReviewRequest("APPLY", "alice", "remove")
        );

        assertEquals("APPLIED", dto.reviewStatus());
        assertEquals(Boolean.TRUE, removed.get().getRemovedFromSource());
        assertNotNull(removed.get().getRemovedAt());
        verify(applyRecordMapper).insert(any());
    }

    private CapabilityDiffItemEntity newDiffItem() {
        CapabilityDiffItemEntity item = new CapabilityDiffItemEntity();
        item.setId(31L);
        item.setSnapshotId(21L);
        item.setSyncId("sync-1");
        item.setProjectId(7L);
        item.setProjectCode("orders");
        item.setQualifiedName("orders:createOrder");
        item.setName("createOrder");
        item.setStorageName("orders_create_order");
        item.setChangeType("ADDED");
        item.setFieldDiffJson("[]");
        item.setImpactJson("{}");
        item.setReviewStatus("PENDING");
        return item;
    }

    private CapabilityRegistration newCapabilityRegistration() {
        return new CapabilityRegistration(
                "createOrder",
                "Create order",
                "Create order",
                "POST",
                "http://orders.local",
                "/orders",
                "/create",
                "JSON",
                "JSON",
                "WRITE",
                true,
                true,
                true,
                "PROJECT",
                List.of(),
                Map.of("source", "sdk")
        );
    }
}
