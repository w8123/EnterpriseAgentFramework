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
import com.enterprise.ai.agent.registry.RegistryContracts.SdkCapabilityDescriptionSettings;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import com.enterprise.ai.agent.registry.RegistryEnrollmentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class CapabilityRegistryServiceTest {

    @org.junit.jupiter.api.BeforeAll
    static void initializeMapperMetadata() {
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "test");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, ToolDefinitionEntity.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, ScanProjectToolEntity.class);
    }

    private final ScanProjectMapper scanProjectMapper = mock(ScanProjectMapper.class);
    private final ScanProjectToolMapper scanProjectToolMapper = mock(ScanProjectToolMapper.class);
    private final ToolDefinitionMapper toolDefinitionMapper = mock(ToolDefinitionMapper.class);
    private final ProjectInstanceMapper instanceMapper = mock(ProjectInstanceMapper.class);
    private final CapabilitySyncLogMapper syncLogMapper = mock(CapabilitySyncLogMapper.class);
    private final CapabilitySnapshotMapper snapshotMapper = mock(CapabilitySnapshotMapper.class);
    private final CapabilityDiffItemMapper diffItemMapper = mock(CapabilityDiffItemMapper.class);
    private final CapabilityApplyRecordMapper applyRecordMapper = mock(CapabilityApplyRecordMapper.class);
    private final RegistrySecurityService registrySecurityService = mock(RegistrySecurityService.class);
    private final RegistryEnrollmentService registryEnrollmentService = mock(RegistryEnrollmentService.class);
    private final ObjectMapper registryJson = new ObjectMapper();
    private final CapabilityChangePolicy policy = new CapabilityChangePolicy(registryJson);
    private final CapabilityChangeLifecycle lifecycle = mock(CapabilityChangeLifecycle.class);
    private final com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodAssetStore methodAssets =
            mock(com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodAssetStore.class);
    private final java.util.Map<String, com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodAssetEntity> methodOwners =
            new java.util.LinkedHashMap<>();
    private final java.util.Map<String, CapabilityRegistration> methodDeclarations = new java.util.LinkedHashMap<>();
    private final CapabilityCatalogProjectionStore projections = new CapabilityCatalogProjectionStore(
            scanProjectToolMapper, toolDefinitionMapper, registryJson, policy,
            methodAssets);
    private final CapabilityReviewEvidenceStore evidence = new CapabilityReviewEvidenceStore(
            snapshotMapper, diffItemMapper, applyRecordMapper);
    private final StarterMvcHttpApiIntakeService httpApiIntake = mock(StarterMvcHttpApiIntakeService.class);
    private final CapabilitySourceIntakeService intake = new CapabilitySourceIntakeService(
            scanProjectMapper, scanProjectToolMapper, toolDefinitionMapper, syncLogMapper, snapshotMapper,
            diffItemMapper, registryJson, policy, lifecycle, projections, evidence, httpApiIntake,
            methodAssets);
    private final CapabilityRegistryService service = new CapabilityRegistryService(
            scanProjectMapper, snapshotMapper, diffItemMapper, registrySecurityService, new RegistryProjectRegistrationService(scanProjectMapper, registrySecurityService, registryEnrollmentService),
            registryJson, policy, lifecycle, projections,
            new RegistryInstanceLifecycleService(instanceMapper, registryJson), intake, evidence);

    @org.junit.jupiter.api.BeforeEach
    void defaultHttpApiInventoryPlanAndExplicitMethodOwner() {
        when(httpApiIntake.prepare(any(), any(), any())).thenReturn(
                new StarterMvcHttpApiIntakeService.Plan(false, null, List.of()));
        when(methodAssets.find(any())).thenAnswer(invocation -> java.util.Optional.ofNullable(
                methodOwners.get(invocation.<String>getArgument(0))));
        when(methodAssets.inventory(any())).thenAnswer(invocation -> methodOwners.values().stream()
                .filter(asset -> java.util.Objects.equals(asset.getProjectId(), invocation.getArgument(0))
                        && "ACCEPTED".equals(asset.getStatus())).toList());
        when(methodAssets.accept(any(), any(), any(), any())).thenAnswer(invocation ->
                acceptedMethodFixture(invocation.getArgument(0), invocation.getArgument(1)));
        when(methodAssets.acceptedDeclaration(any())).thenAnswer(invocation -> methodDeclarations.get(
                invocation.<com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodAssetEntity>getArgument(0).getQualifiedName()));
        when(methodAssets.acceptedRevision(any())).thenAnswer(invocation -> {
            var asset = invocation.<com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodAssetEntity>getArgument(0);
            var revision = new com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodRevisionEntity();
            revision.setId(asset.getAcceptedRevisionId()); revision.setAssetId(asset.getId());
            revision.setInvocationHash(policy.contractHash(methodDeclarations.get(asset.getQualifiedName())));
            return revision;
        });
        when(methodAssets.captureState(any())).thenAnswer(invocation -> registryJson.valueToTree(
                methodOwners.get(invocation.<String>getArgument(0))));
    }

    // These unit tests supply an explicit owner dependency; the H2 suite verifies its persisted proof.
    private com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodAssetEntity acceptedMethodFixture(
            ScanProjectEntity project, CapabilityRegistration declaration) {
        String name = project.getProjectCode() + ":" + declaration.name();
        var asset = methodOwners.computeIfAbsent(name, ignored -> {
            var created = new com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodAssetEntity();
            created.setId(200L + methodOwners.size());
            created.setProjectId(project.getId()); created.setProjectCode(project.getProjectCode());
            created.setQualifiedName(name); created.setMethodCode(declaration.name());
            created.setInvocationName(com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodAssetStore
                    .invocationName(project.getProjectCode(), declaration.name()));
            return created;
        });
        asset.setStatus("ACCEPTED"); asset.setAcceptedRevisionId(300L + methodOwners.size());
        asset.setEnabled(!Boolean.FALSE.equals(declaration.enabled()));
        methodDeclarations.put(name, declaration);
        return asset;
    }

    @Test
    void sourceObservationEntryPointsDeclareReadCommittedOnTheirOuterTransaction() throws Exception {
        assertReadCommitted("sync", String.class, CapabilitySyncRequest.class);
        assertReadCommitted("syncFromProject", String.class, CapabilitySyncRequest.class,
                RegistrySecurityService.RegistrySignatureHeaders.class);
        assertReadCommitted("apply", String.class, CapabilitySyncRequest.class);
    }

    private static void assertReadCommitted(String methodName, Class<?>... parameterTypes) throws Exception {
        Transactional transaction = CapabilityRegistryService.class.getMethod(methodName, parameterTypes)
                .getAnnotation(Transactional.class);
        assertNotNull(transaction, methodName + " must declare its transaction boundary");
        assertEquals(Isolation.READ_COMMITTED, transaction.isolation(),
                methodName + " must begin HTTP source observation at READ_COMMITTED");
    }

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
        when(registryEnrollmentService.issueCredential(any(), any()))
                .thenReturn(new RegistryEnrollmentService.RegistryCredential("rak_generated", "ras_generated"));
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
        ), "ren_once", new RegistrySecurityService.RegistrySignatureHeaders(null, null, null, null));

        assertEquals(42L, response.projectId());
        assertEquals("orders-api", response.projectCode());
        assertEquals("Orders", response.name());
        ScanProjectEntity project = inserted.get();
        assertNotNull(project);
        assertEquals("orders-api", project.getProjectCode());
        assertEquals("REGISTERED", project.getProjectKind());
        assertEquals("auto", project.getScanType());
        assertEquals(true, project.getAiCodingAccessEnabled());
        assertNotNull(project.getAiCodingAccessKey());
        assertTrue(project.getAiCodingAccessKey().matches("aic_[0-9a-f]{48}"));
        verify(registryEnrollmentService).consume("ren_once", "orders-api");
        verify(registrySecurityService).savePrimaryCredential(42L, "orders-api", "rak_generated", "ras_generated");
        verify(registrySecurityService).updateEmbedPolicy(
                "orders-api",
                "rak_generated",
                List.of("http://localhost:5173"),
                List.of("agent-1"),
                300
        );
    }

    @Test
    void doesNotRewriteProjectForIdempotentStarterRegistration() {
        LocalDateTime previousUpdateTime = LocalDateTime.of(2026, 7, 30, 10, 0);
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(42L);
        project.setName("Orders");
        project.setProjectCode("orders-api");
        project.setProjectKind("REGISTERED");
        project.setEnvironment("dev");
        project.setOwner("platform");
        project.setVisibility("SHARED");
        project.setBaseUrl("http://orders.local");
        project.setContextPath("/orders");
        project.setScanPath("");
        project.setScanType("auto");
        project.setUpdateTime(previousUpdateTime);
        when(scanProjectMapper.selectOne(any())).thenReturn(project);

        RegistrySecurityService.RegistrySignatureHeaders signatureHeaders =
                new RegistrySecurityService.RegistrySignatureHeaders("rak_existing", "1", "n", "sig");
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
        ), null, signatureHeaders);

        assertEquals(42L, response.projectId());
        assertEquals(previousUpdateTime, project.getUpdateTime());
        verify(registrySecurityService).verifyRequired("orders-api", signatureHeaders);
        verify(scanProjectMapper, never()).updateById(any());
        verify(scanProjectMapper, never()).update(any(), any());
    }

    @Test
    void recordsHeartbeatIntoProjectInstanceTable() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        project.setBaseUrl("http://orders.default");
        when(scanProjectMapper.selectOne(any())).thenReturn(project);
        AtomicReference<ProjectInstanceEntity> persisted = new AtomicReference<>();
        when(instanceMapper.selectOne(any())).thenAnswer(invocation -> persisted.get());
        when(instanceMapper.upsertHeartbeat(any())).thenAnswer(invocation -> {
            ProjectInstanceEntity entity = invocation.getArgument(0);
            entity.setId(11L);
            entity.setStatus("ONLINE");
            persisted.set(entity);
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
        when(scanProjectMapper.selectOne(any())).thenReturn(project);

        service.offline("orders", "dev-1");

        verify(instanceMapper).markOffline(eq("orders"), eq("dev-1"), any());
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
        instance.setStatus("DISABLED");
        when(scanProjectMapper.selectOne(any())).thenReturn(project);
        when(instanceMapper.selectOne(any())).thenReturn(instance);

        ProjectInstanceEntity updated = service.updateInstanceStatus("orders", "dev-1", "disabled");

        assertEquals(instance, updated);
        assertEquals("DISABLED", instance.getStatus());
        verify(instanceMapper).updateStatus(eq("orders"), eq("dev-1"), eq("DISABLED"), any());
    }

    @Test
    void purgesOfflineAndStaleInstancesForProject() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        when(scanProjectMapper.selectOne(any())).thenReturn(project);
        when(instanceMapper.delete(any())).thenReturn(2);

        int removed = service.purgeOfflineInstances("orders", 10);

        assertEquals(2, removed);
        verify(instanceMapper).delete(any());
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
        existing.setTitle("Create order");
        existing.setQualifiedName("orders:createOrder");
        existing.setDescription("Old description");
        existing.setHttpMethod("POST");
        existing.setEnabled(true);
        acceptedMethodFixture(project, new CapabilityRegistration("createOrder", "Create order", "Old description",
                "POST", "http://orders.local", "/orders", "/create", "JSON", "JSON", "WRITE", true, List.of(),
                Map.of("source", "sdk", "assetType", "BUSINESS_METHOD")));
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
                        List.of(),
                        Map.of("source", "sdk", "assetType", "BUSINESS_METHOD")
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
        assertEquals("DIAGNOSTIC", snapshot.getStatus());
        assertEquals(1, snapshot.getReceived());
        CapabilityDiffItemEntity diffItem = insertedDiffItem.get();
        assertNotNull(diffItem);
        assertEquals(21L, diffItem.getSnapshotId());
        assertEquals("orders:createOrder", diffItem.getQualifiedName());
        assertEquals("CHANGED", diffItem.getChangeType());
        assertEquals("PENDING", diffItem.getReviewStatus());
        CapabilitySyncLogEntity log = insertedLog.get();
        assertNotNull(log);
        assertEquals("DIAGNOSTIC", log.getStatus());
        assertEquals("sync-1", log.getSyncId());
    }

    @Test
    void syncIgnoresLegacyApplyFlagAndKeepsSnapshotPending() {
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
        AtomicReference<ToolDefinitionEntity> insertedGlobalTool = new AtomicReference<>();
        when(toolDefinitionMapper.insert(any())).thenAnswer(invocation -> {
            ToolDefinitionEntity entity = invocation.getArgument(0);
            entity.setId(61L);
            insertedGlobalTool.set(entity);
            return 1;
        });
        AtomicReference<CapabilitySnapshotEntity> insertedSnapshot = new AtomicReference<>();
        when(snapshotMapper.insert(any())).thenAnswer(invocation -> {
            CapabilitySnapshotEntity entity = invocation.getArgument(0);
            entity.setId(21L);
            insertedSnapshot.set(entity);
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

        RegistrySecurityService.RegistrySignatureHeaders signatureHeaders =
                new RegistrySecurityService.RegistrySignatureHeaders("key", "123", "nonce", "signature");
        CapabilitySyncResponse response = service.syncFromProject("orders", new CapabilitySyncRequest(
                "sync-2",
                "SDK",
                true,
                List.of(newCapabilityRegistration())
        ), signatureHeaders);

        assertEquals(1, response.added());
        assertEquals(0, response.applied());
        assertEquals("PENDING", insertedSnapshot.get().getStatus());
        assertNull(insertedTool.get());
        assertNull(insertedGlobalTool.get());
        assertNull(updatedDiffItem.get());
        verify(registrySecurityService).verifyRequired("orders", signatureHeaders);
        verify(scanProjectToolMapper, never()).insert(any());
        verify(toolDefinitionMapper, never()).insert(any());
        verify(diffItemMapper, never()).updateById(any());
    }

    @Test
    void doesNotActivateAnUnlinkedWriteCapabilityWithoutReview() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        project.setBaseUrl("http://orders.default");
        project.setContextPath("/orders");
        ScanProjectToolEntity existing = new ScanProjectToolEntity();
        existing.setId(51L);
        existing.setProjectId(7L);
        existing.setName("orders_createOrder");
        existing.setTitle("创建订单");
        existing.setDescription("Create order");
        existing.setParametersJson("[]");
        existing.setSourceLocation("sdk:orders:createOrder");
        existing.setHttpMethod("POST");
        existing.setBaseUrl("http://orders.local");
        existing.setContextPath("/orders");
        existing.setEndpointPath("/create");
        existing.setRequestBodyType("JSON");
        existing.setResponseType("JSON");
        existing.setEnabled(true);
        existing.setCapabilityMetadataJson("{\"source\":\"sdk\",\"sideEffect\":\"WRITE\"}");
        existing.setRemovedFromSource(false);
        when(scanProjectMapper.selectOne(any())).thenReturn(project);
        when(scanProjectToolMapper.selectOne(any())).thenReturn(existing);
        when(scanProjectToolMapper.selectList(any())).thenReturn(List.of(existing));
        when(toolDefinitionMapper.selectOne(any())).thenReturn(null);
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
        AtomicReference<ToolDefinitionEntity> insertedGlobalTool = new AtomicReference<>();
        when(toolDefinitionMapper.insert(any())).thenAnswer(invocation -> {
            ToolDefinitionEntity entity = invocation.getArgument(0);
            entity.setId(61L);
            insertedGlobalTool.set(entity);
            return 1;
        });

        CapabilitySyncResponse response = service.apply("orders", new CapabilitySyncRequest(
                "sync-unchanged",
                "SDK",
                true,
                List.of(newCapabilityRegistration())
        ));

        assertEquals(1, response.added());
        assertEquals(0, response.applied());
        assertEquals("ADDED", response.items().get(0).changeType());
        assertNull(insertedGlobalTool.get());
        assertNull(existing.getGlobalToolDefinitionId());
        verify(toolDefinitionMapper, never()).insert(any());
    }

    @Test
    void policyAutomaticallyImportsCompleteReadOnlyCapabilities() {
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

        CapabilitySyncResponse response = service.apply("orders", new CapabilitySyncRequest(
                "sync-2",
                "SDK",
                null,
                List.of(new CapabilityRegistration("queryOrder", "查询订单", "Query order", "POST",
                        "http://orders.local", "/orders", "/query", "JSON", "JSON", "READ_ONLY",
                        true, List.of(), Map.of("source", "sdk", "assetType", "BUSINESS_METHOD")))
        ));

        assertEquals(1, response.added());
        assertEquals(1, response.applied());
        ScanProjectToolEntity tool = insertedTool.get();
        assertNotNull(tool);
        assertEquals(7L, tool.getProjectId());
        assertEquals("orders_queryOrder", tool.getName());
        assertEquals("查询订单", tool.getTitle());
        assertEquals("sdk:orders:queryOrder", tool.getSourceLocation());
        assertEquals("POST", tool.getHttpMethod());
        assertEquals("http://orders.local", tool.getBaseUrl());
        assertEquals(Boolean.FALSE, tool.getRemovedFromSource());
        assertEquals("AUTO_APPLIED", updatedDiffItem.get().getReviewStatus());
    }

    @Test
    void disappearanceRequiresDecisionInsteadOfAutomaticallyDeletingCatalog() {
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
        staleTool.setEnabled(true);
        staleTool.setRemovedFromSource(false);
        ToolDefinitionEntity staleGlobalTool = new ToolDefinitionEntity();
        staleGlobalTool.setId(12L);
        staleGlobalTool.setQualifiedName("orders:oldCapability");
        staleGlobalTool.setEnabled(true);
        acceptedMethodFixture(project, new CapabilityRegistration("oldCapability", "Old capability", "Old capability",
                "POST", "http://orders.local", null, "/old", "JSON", "JSON", "READ_ONLY", true, List.of(),
                Map.of("assetType", "BUSINESS_METHOD")));
        when(scanProjectToolMapper.selectOne(any())).thenReturn(staleTool);
        when(scanProjectToolMapper.selectList(any())).thenReturn(List.of(staleTool));
        when(toolDefinitionMapper.selectById(12L)).thenReturn(staleGlobalTool);
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

        CapabilitySyncResponse response = service.apply("orders", new CapabilitySyncRequest(
                "sync-3",
                "SDK",
                true,
                List.of()
        ));

        assertEquals(0, response.received());
        assertEquals(1, response.items().size());
        assertEquals("DELETED", response.items().get(0).changeType());
        assertEquals(0, response.applied());
        assertNull(removed.get());
        assertEquals(Boolean.TRUE, staleGlobalTool.getEnabled());
        verify(toolDefinitionMapper, never()).updateById(staleGlobalTool);
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
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        CapabilitySnapshotEntity snapshot = new CapabilitySnapshotEntity();
        snapshot.setId(21L);
        snapshot.setProjectId(7L);
        snapshot.setProjectCode("orders");
        CapabilityDiffItemEntity item = newDiffItem();
        when(scanProjectMapper.selectOne(any())).thenReturn(project);
        when(snapshotMapper.selectById(21L)).thenReturn(snapshot);
        when(diffItemMapper.selectList(any())).thenReturn(List.of(item));

        List<CapabilityDiffItemDTO> items = service.listDiffItems("orders", 21L);

        assertEquals(1, items.size());
        CapabilityDiffItemDTO dto = items.get(0);
        assertEquals(31L, dto.id());
        assertEquals(21L, dto.snapshotId());
        assertEquals("orders:createOrder", dto.qualifiedName());
        assertEquals("ADDED", dto.changeType());
        assertEquals("PENDING", dto.reviewStatus());
    }

    @Test
    void rejectsDiffItemListingWhenSnapshotBelongsToDifferentProject() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        CapabilitySnapshotEntity snapshot = new CapabilitySnapshotEntity();
        snapshot.setId(21L);
        snapshot.setProjectId(8L);
        snapshot.setProjectCode("billing");
        when(scanProjectMapper.selectOne(any())).thenReturn(project);
        when(snapshotMapper.selectById(21L)).thenReturn(snapshot);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.listDiffItems("orders", 21L));

        assertEquals("快照不属于项目 orders: 21", error.getMessage());
        verify(diffItemMapper, never()).selectList(any());
    }

    @Test
    void rejectsReviewWhenDiffItemBelongsToDifferentProject() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        CapabilityDiffItemEntity item = newDiffItem();
        item.setProjectId(8L);
        item.setProjectCode("billing");
        when(scanProjectMapper.selectOne(any())).thenReturn(project);
        when(diffItemMapper.selectById(31L)).thenReturn(item);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service.reviewDiffItem("orders", 31L,
                        new CapabilityReviewRequest("IGNORE", "alice", null)));

        assertEquals("评审项不属于项目 orders: 31", error.getMessage());
        verify(snapshotMapper, never()).selectById(any());
        verify(diffItemMapper, never()).updateById(any());
        verify(applyRecordMapper, never()).insert(any());
    }

    @Test
    void rejectsReviewWhenDiffItemSnapshotBelongsToDifferentProject() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        CapabilityDiffItemEntity item = newDiffItem();
        CapabilitySnapshotEntity snapshot = new CapabilitySnapshotEntity();
        snapshot.setId(21L);
        snapshot.setProjectId(8L);
        snapshot.setProjectCode("billing");
        when(scanProjectMapper.selectOne(any())).thenReturn(project);
        when(diffItemMapper.selectById(31L)).thenReturn(item);
        when(snapshotMapper.selectById(21L)).thenReturn(snapshot);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service.reviewDiffItem("orders", 31L,
                        new CapabilityReviewRequest("IGNORE", "alice", null)));

        assertEquals("快照不属于项目 orders: 21", error.getMessage());
        verify(diffItemMapper, never()).updateById(any());
        verify(applyRecordMapper, never()).insert(any());
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
        when(diffItemMapper.selectList(any())).thenReturn(List.of(item));
        when(diffItemMapper.updateById(any())).thenAnswer(invocation -> {
            updated.set(invocation.getArgument(0));
            return 1;
        });
        when(applyRecordMapper.insert(any())).thenAnswer(invocation -> {
            insertedRecord.set(invocation.getArgument(0));
            return 1;
        });

        CapabilityDiffItemDTO dto = service.reviewDiffItem(
                "orders",
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
        assertEquals("IGNORED", snapshot.getStatus());
        verify(snapshotMapper).updateById(snapshot);
    }

    @Test
    void reviewApplyAcceptsMethodOwnerAndDerivesInvocationProjection() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        CapabilitySnapshotEntity snapshot = new CapabilitySnapshotEntity();
        snapshot.setId(21L);
        snapshot.setProjectId(7L);
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
                + "\"sideEffect\":\"WRITE\",\"metadata\":{\"assetType\":\"BUSINESS_METHOD\"}}]}");

        CapabilityDiffItemDTO dto = service.reviewDiffItem(
                "orders",
                31L,
                new CapabilityReviewRequest("APPLY", "alice", "apply now")
        );

        assertEquals("APPLIED", dto.reviewStatus());
        verify(methodAssets).accept(eq(project), any(), eq(21L), eq(31L));
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
        snapshot.setProjectId(7L);
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
        staleTool.setGlobalToolDefinitionId(12L);
        staleTool.setEnabled(true);
        ToolDefinitionEntity staleGlobalTool = new ToolDefinitionEntity();
        staleGlobalTool.setId(12L);
        staleGlobalTool.setQualifiedName("orders:oldCapability");
        staleGlobalTool.setEnabled(true);
        when(diffItemMapper.selectById(31L)).thenReturn(item);
        when(snapshotMapper.selectById(21L)).thenReturn(snapshot);
        when(scanProjectMapper.selectOne(any())).thenReturn(project);
        when(scanProjectToolMapper.selectOne(any())).thenReturn(staleTool);
        when(toolDefinitionMapper.selectById(12L)).thenReturn(staleGlobalTool);
        AtomicReference<ScanProjectToolEntity> removed = new AtomicReference<>();
        when(scanProjectToolMapper.updateById(any())).thenAnswer(invocation -> {
            removed.set(invocation.getArgument(0));
            return 1;
        });

        CapabilityDiffItemDTO dto = service.reviewDiffItem(
                "orders",
                31L,
                new CapabilityReviewRequest("APPLY", "alice", "remove")
        );

        assertEquals("APPLIED", dto.reviewStatus());
        assertEquals(Boolean.TRUE, removed.get().getRemovedFromSource());
        assertEquals(Boolean.FALSE, removed.get().getEnabled());
        assertNotNull(removed.get().getRemovedAt());
        assertEquals(Boolean.FALSE, staleGlobalTool.getEnabled());
        verify(applyRecordMapper).insert(any());
    }

    @Test
    void rejectsReviewWithoutExplicitAction() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        CapabilityDiffItemEntity item = newDiffItem();
        CapabilitySnapshotEntity snapshot = new CapabilitySnapshotEntity();
        snapshot.setId(21L);
        snapshot.setProjectId(7L);
        snapshot.setProjectCode("orders");
        when(diffItemMapper.selectById(31L)).thenReturn(item);
        when(snapshotMapper.selectById(21L)).thenReturn(snapshot);
        when(scanProjectMapper.selectOne(any())).thenReturn(project);

        IllegalArgumentException missingRequest = assertThrows(
                IllegalArgumentException.class,
                () -> service.reviewDiffItem("orders", 31L, null));
        IllegalArgumentException missingAction = assertThrows(
                IllegalArgumentException.class,
                () -> service.reviewDiffItem("orders", 31L,
                        new CapabilityReviewRequest(" ", "alice", null)));

        assertEquals("评审动作不能为空", missingRequest.getMessage());
        assertEquals("评审动作不能为空", missingAction.getMessage());
        verify(diffItemMapper, never()).updateById(any());
        verify(applyRecordMapper, never()).insert(any());
    }

    @Test
    void rollbackRestoresCatalogAndExecutableToolState() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        CapabilitySnapshotEntity snapshot = new CapabilitySnapshotEntity();
        snapshot.setId(21L);
        snapshot.setProjectId(7L);
        snapshot.setProjectCode("orders");
        CapabilityDiffItemEntity item = newDiffItem();
        item.setChangeType("CHANGED");
        item.setExistingToolId(12L);
        item.setReviewStatus("APPLIED");
        item.setBeforeStateJson("""
                {
                  "businessMethodAsset": {"id":200,"qualifiedName":"orders:createOrder","acceptedRevisionId":301,"status":"ACCEPTED","enabled":true},
                  "scanTool": {
                    "id": 51,
                    "projectId": 7,
                    "name": "orders_create_order",
                    "title": "Stable title",
                    "description": "Stable description",
                    "source": "scanner",
                    "sourceLocation": "sdk:orders:createOrder",
                    "enabled": true,
                    "globalToolDefinitionId": 12,
                    "removedFromSource": false
                  },
                  "globalTool": {
                    "id": 12,
                    "name": "orders_create_order",
                    "title": "Stable title",
                    "kind": "TOOL",
                    "description": "Stable description",
                    "source": "scanner",
                    "sourceLocation": "sdk:orders:createOrder",
                    "projectId": 7,
                    "projectCode": "orders",
                    "qualifiedName": "orders:createOrder",
                    "enabled": true,
                    "sideEffect": "READ_ONLY",
                    "draft": false
                  }
                }
                """);

        ScanProjectToolEntity currentScan = new ScanProjectToolEntity();
        currentScan.setId(51L);
        currentScan.setProjectId(7L);
        currentScan.setName("orders_create_order");
        currentScan.setTitle("Risky title");
        currentScan.setDescription("Risky description");
        currentScan.setSourceLocation("sdk:orders:createOrder");
        currentScan.setGlobalToolDefinitionId(12L);
        currentScan.setEnabled(true);
        ToolDefinitionEntity currentGlobal = new ToolDefinitionEntity();
        currentGlobal.setId(12L);
        currentGlobal.setQualifiedName("orders:createOrder");
        currentGlobal.setProjectId(7L);
        currentGlobal.setTitle("Risky title");
        currentGlobal.setDescription("Risky description");
        currentGlobal.setEnabled(true);
        currentGlobal.setSideEffect("WRITE");
        acceptedMethodFixture(project, new CapabilityRegistration("createOrder", "Stable title", "Stable description",
                "POST", "http://orders.local", "/orders", "/create", "JSON", "JSON", "READ_ONLY", true, List.of(),
                Map.of("assetType", "BUSINESS_METHOD")));

        when(diffItemMapper.selectById(31L)).thenReturn(item);
        when(diffItemMapper.selectOne(any())).thenReturn(item);
        when(snapshotMapper.selectById(21L)).thenReturn(snapshot);
        when(scanProjectMapper.selectOne(any())).thenReturn(project);
        when(scanProjectToolMapper.selectOne(any())).thenReturn(currentScan);
        when(toolDefinitionMapper.selectById(12L)).thenReturn(currentGlobal);
        AtomicReference<CapabilityApplyRecordEntity> insertedRecord = new AtomicReference<>();
        when(applyRecordMapper.insert(any())).thenAnswer(invocation -> {
            insertedRecord.set(invocation.getArgument(0));
            return 1;
        });

        CapabilityDiffItemDTO dto = service.rollbackDiffItem(
                "orders",
                31L,
                new CapabilityReviewRequest("ROLLBACK", "alice", "production regression")
        );

        assertEquals("ROLLED_BACK", dto.reviewStatus());
        assertEquals("Stable title", currentScan.getTitle());
        assertEquals("Stable description", currentScan.getDescription());
        assertEquals(Boolean.TRUE, currentScan.getEnabled());
        assertEquals(Boolean.FALSE, currentScan.getRemovedFromSource());
        assertEquals("Stable title", currentGlobal.getTitle());
        assertEquals("Stable description", currentGlobal.getDescription());
        assertEquals("READ_ONLY", currentGlobal.getSideEffect());
        assertEquals(Boolean.TRUE, currentGlobal.getEnabled());
        assertEquals("ROLLBACK", insertedRecord.get().getAction());
        assertEquals("alice", insertedRecord.get().getOperator());
        verify(scanProjectToolMapper).updateById(currentScan);
        verify(toolDefinitionMapper).updateById(currentGlobal);
    }

    @Test
    void refusesReviewApplyWhenCatalogChangedAfterSnapshot() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        CapabilitySnapshotEntity snapshot = new CapabilitySnapshotEntity();
        snapshot.setId(21L);
        snapshot.setProjectId(7L);
        snapshot.setProjectCode("orders");
        snapshot.setPayloadJson("{\"syncId\":\"sync-1\",\"source\":\"SDK\",\"apply\":false,\"capabilities\":["
                + "{\"name\":\"createOrder\",\"description\":\"SDK description\",\"httpMethod\":\"POST\","
                + "\"enabled\":true,\"sideEffect\":\"WRITE\"}]}");
        CapabilityDiffItemEntity item = newDiffItem();
        item.setChangeType("CHANGED");
        item.setBeforeStateJson("""
                {
                  "businessMethodAsset": null,
                  "scanTool": {
                    "id": 51,
                    "projectId": 7,
                    "name": "orders_create_order",
                    "title": "Snapshot title",
                    "sourceLocation": "sdk:orders:createOrder",
                    "enabled": true,
                    "removedFromSource": false
                  },
                  "globalTool": null
                }
                """);
        ScanProjectToolEntity current = new ScanProjectToolEntity();
        current.setId(51L);
        current.setProjectId(7L);
        current.setName("orders_create_order");
        current.setTitle("Changed after snapshot");
        current.setSourceLocation("sdk:orders:createOrder");
        current.setEnabled(true);
        current.setRemovedFromSource(false);
        when(diffItemMapper.selectById(31L)).thenReturn(item);
        when(snapshotMapper.selectById(21L)).thenReturn(snapshot);
        when(scanProjectMapper.selectOne(any())).thenReturn(project);
        when(scanProjectToolMapper.selectOne(any())).thenReturn(current);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service.reviewDiffItem("orders", 31L,
                        new CapabilityReviewRequest("APPLY", "alice", null)));

        assertEquals("方法资产或调用投影在生成差异后已变化，请刷新 SDK 快照后重新评审", error.getMessage());
        verify(scanProjectToolMapper, never()).updateById(any());
        verify(diffItemMapper, never()).updateById(any());
        verify(applyRecordMapper, never()).insert(any());
    }

    @Test
    void refusesRollbackWhenNewerAppliedChangeExists() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        CapabilitySnapshotEntity snapshot = new CapabilitySnapshotEntity();
        snapshot.setId(21L);
        snapshot.setProjectId(7L);
        snapshot.setProjectCode("orders");
        CapabilityDiffItemEntity item = newDiffItem();
        item.setReviewStatus("APPLIED");
        item.setBeforeStateJson("{\"scanTool\":null,\"globalTool\":null,\"businessMethodAsset\":null}");
        CapabilityDiffItemEntity newer = newDiffItem();
        newer.setId(32L);
        newer.setReviewStatus("APPLIED");
        when(scanProjectMapper.selectOne(any())).thenReturn(project);
        when(diffItemMapper.selectById(31L)).thenReturn(item);
        when(snapshotMapper.selectById(21L)).thenReturn(snapshot);
        when(diffItemMapper.selectOne(any())).thenReturn(newer);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service.rollbackDiffItem("orders", 31L,
                        new CapabilityReviewRequest("ROLLBACK", "alice", null)));

        assertEquals("该能力已有更新的已应用变更，不能覆盖式回滚旧快照", error.getMessage());
        verify(diffItemMapper, never()).updateById(any());
        verify(applyRecordMapper, never()).insert(any());
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
                "创建订单",
                "Create order",
                "POST",
                "http://orders.local",
                "/orders",
                "/create",
                "JSON",
                "JSON",
                "WRITE",
                true,
                List.of(),
                Map.of("source", "sdk", "assetType", "BUSINESS_METHOD")
        );
    }
}
