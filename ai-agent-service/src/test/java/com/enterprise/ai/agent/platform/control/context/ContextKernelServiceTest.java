package com.enterprise.ai.agent.platform.control.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class ContextKernelServiceTest {

    private ContextNamespaceMapper namespaceMapper;
    private ContextItemMapper itemMapper;
    private ContextBindingMapper bindingMapper;
    private ContextEvidenceMapper evidenceMapper;
    private ContextAuditEventMapper auditEventMapper;

    private ContextNamespaceService namespaceService;
    private ContextBindingService bindingService;
    private ContextEvidenceService evidenceService;
    private ContextAuditService auditService;
    private ContextAccessPolicyService accessPolicyService;
    private ContextItemService itemService;
    private ContextRetrievalService retrievalService;
    private ContextComposerService composerService;

    private final AtomicLong idSeq = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        namespaceMapper = mock(ContextNamespaceMapper.class);
        itemMapper = mock(ContextItemMapper.class);
        bindingMapper = mock(ContextBindingMapper.class);
        evidenceMapper = mock(ContextEvidenceMapper.class);
        auditEventMapper = mock(ContextAuditEventMapper.class);

        accessPolicyService = new ContextAccessPolicyService(
                mock(com.enterprise.ai.agent.platform.control.auth.PlatformAuthorizationService.class),
                bindingMapper,
                itemMapper,
                namespaceMapper);
        auditService = new ContextAuditService(auditEventMapper);
        namespaceService = new ContextNamespaceService(namespaceMapper, accessPolicyService);
        bindingService = new ContextBindingService(bindingMapper, accessPolicyService);
        evidenceService = new ContextEvidenceService(evidenceMapper, accessPolicyService);
        retrievalService = new ContextRetrievalService(itemMapper, namespaceMapper, bindingService, accessPolicyService, auditService);
        itemService = new ContextItemService(itemMapper, namespaceService, bindingService, evidenceService,
                accessPolicyService, auditService, retrievalService);
        composerService = new ContextComposerService(retrievalService, evidenceService, auditService);

        when(namespaceMapper.insert(any(ContextNamespaceEntity.class))).thenAnswer(invocation -> {
            ContextNamespaceEntity entity = invocation.getArgument(0);
            entity.setId(idSeq.getAndIncrement());
            return 1;
        });
        when(itemMapper.insert(any(ContextItemEntity.class))).thenAnswer(invocation -> {
            ContextItemEntity entity = invocation.getArgument(0);
            entity.setId(idSeq.getAndIncrement());
            return 1;
        });
        when(bindingMapper.insert(any(ContextBindingEntity.class))).thenAnswer(invocation -> {
            ContextBindingEntity entity = invocation.getArgument(0);
            entity.setId(idSeq.getAndIncrement());
            return 1;
        });
        when(evidenceMapper.insert(any(ContextEvidenceEntity.class))).thenAnswer(invocation -> {
            ContextEvidenceEntity entity = invocation.getArgument(0);
            entity.setId(idSeq.getAndIncrement());
            return 1;
        });
        when(auditEventMapper.insert(any(ContextAuditEventEntity.class))).thenReturn(1);
    }

    @Test
    void createNamespaceCreateItemAndGetItem() {
        stubNamespaceLookup(null);
        when(namespaceMapper.selectOne(any())).thenReturn(null);
        when(itemMapper.selectById(2L)).thenAnswer(invocation -> storedItem(2L, MemoryLane.PROJECT_DEV, ContextStatus.ACTIVE, null));
        when(namespaceMapper.selectById(1L)).thenReturn(storedNamespace(1L));

        ContextNamespaceResponse namespace = namespaceService.createOrGetNamespace(namespaceRequest());
        ContextItemCreateRequest createRequest = itemCreateRequest(namespace.getId(), MemoryLane.PROJECT_DEV, "orders module uses soft delete");
        ContextItemResponse created = itemService.createItem(createRequest);

        assertNotNull(created.getId());
        assertEquals(MemoryLane.PROJECT_DEV.name(), created.getMemoryLane());
        verify(itemMapper).insert(any(ContextItemEntity.class));

        ContextQueryRequest scope = queryScope(MemoryLane.PROJECT_DEV);
        ContextItemResponse fetched = itemService.getItem(created.getId(), scope);
        assertEquals("orders module uses soft delete", fetched.getContent());
    }

    @Test
    void bindingTargetsCanBeQueried() {
        stubNamespaceLookup(storedNamespace(1L));
        when(itemMapper.selectList(any())).thenReturn(List.of(
                storedItem(10L, MemoryLane.PROJECT_DEV, ContextStatus.ACTIVE, null)));
        when(bindingMapper.selectList(any())).thenReturn(List.of(storedBinding(10L, ContextBindType.AGENT, "agent-1")));

        ContextQueryRequest query = queryScope(MemoryLane.PROJECT_DEV);
        query.setAgentId("agent-1");
        query.setQuery("orders");

        List<ContextSearchResult> hits = retrievalService.search(query);
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).getHitReason().contains("binding"));
    }

    @Test
    void projectDevAndRuntimeUserDoNotCrossSearch() {
        stubNamespaceLookup(storedNamespace(1L));
        when(itemMapper.selectList(any()))
                .thenReturn(List.of(storedItem(11L, MemoryLane.PROJECT_DEV, ContextStatus.ACTIVE, null)))
                .thenReturn(List.of(storedItem(12L, MemoryLane.RUNTIME_USER, ContextStatus.ACTIVE, null)));

        ContextQueryRequest devQuery = queryScope(MemoryLane.PROJECT_DEV);
        List<ContextSearchResult> devHits = retrievalService.search(devQuery);
        assertEquals(1, devHits.size());
        assertEquals(MemoryLane.PROJECT_DEV.name(), devHits.get(0).getItem().getMemoryLane());

        ContextQueryRequest runtimeQuery = queryScope(MemoryLane.RUNTIME_USER);
        List<ContextSearchResult> runtimeHits = retrievalService.search(runtimeQuery);
        assertEquals(1, runtimeHits.size());
        assertEquals(MemoryLane.RUNTIME_USER.name(), runtimeHits.get(0).getItem().getMemoryLane());
    }

    @Test
    void expiredItemDoesNotEnterPackage() {
        stubNamespaceLookup(storedNamespace(1L));
        ContextItemEntity expired = storedItem(20L, MemoryLane.PROJECT_DEV, ContextStatus.ACTIVE, null);
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(5));
        when(itemMapper.selectList(any())).thenReturn(List.of(expired));

        ContextPackageComposeRequest request = new ContextPackageComposeRequest();
        request.setQuery(queryScope(MemoryLane.PROJECT_DEV));
        request.setMaxItems(10);
        ContextPackageResponse pkg = composerService.compose(request);

        assertTrue(pkg.getProjectMemory().isEmpty());
        assertEquals(0, pkg.getTotalItems());
    }

    @Test
    void revokedDeletedAndStaleItemsAreNotReadableOrSearchable() {
        when(itemMapper.selectById(30L)).thenReturn(storedItem(30L, MemoryLane.PROJECT_DEV, ContextStatus.REVOKED, null));
        ContextQueryRequest scope = queryScope(MemoryLane.PROJECT_DEV);
        assertThrows(IllegalArgumentException.class, () -> itemService.getItem(30L, scope));

        stubNamespaceLookup(storedNamespace(1L));
        when(itemMapper.selectList(any())).thenReturn(List.of());
        List<ContextSearchResult> hits = retrievalService.search(scope);
        assertTrue(hits.isEmpty());
    }

    @Test
    void evidenceCanBeAddedAndListed() {
        ContextItemEntity item = storedItem(50L, MemoryLane.PROJECT_DEV, ContextStatus.ACTIVE, null);
        stubItemAccess(item);
        when(evidenceMapper.selectList(any())).thenReturn(List.of(storedEvidence(40L, 50L)));

        ContextQueryRequest scope = queryScope(MemoryLane.PROJECT_DEV);
        ContextEvidenceResponse evidence = evidenceService.addEvidence(50L, evidenceRequest(), scope);
        assertNotNull(evidence.getId());

        List<ContextEvidenceResponse> listed = evidenceService.listEvidence(50L, scope);
        assertEquals(1, listed.size());
        assertEquals("MANUAL_NOTE", listed.get(0).getEvidenceType());
    }

    @Test
    void auditEventsRecordedForCreateSearchPackageDeleteRevoke() {
        stubNamespaceLookup(null);
        when(namespaceMapper.selectOne(any())).thenReturn(null);
        when(namespaceMapper.selectById(1L)).thenReturn(storedNamespace(1L));
        when(itemMapper.selectById(60L)).thenReturn(storedItem(60L, MemoryLane.PROJECT_DEV, ContextStatus.ACTIVE, null));
        when(itemMapper.selectById(61L)).thenReturn(storedItem(61L, MemoryLane.PROJECT_DEV, ContextStatus.ACTIVE, null));

        itemService.createItem(itemCreateRequest(1L, MemoryLane.PROJECT_DEV, "audit create"));

        stubNamespaceLookup(storedNamespace(1L));
        when(itemMapper.selectList(any())).thenReturn(List.of(storedItem(60L, MemoryLane.PROJECT_DEV, ContextStatus.ACTIVE, null)));
        retrievalService.search(queryScope(MemoryLane.PROJECT_DEV));

        ContextPackageComposeRequest composeRequest = new ContextPackageComposeRequest();
        composeRequest.setQuery(queryScope(MemoryLane.PROJECT_DEV));
        composeRequest.setMaxItems(5);
        composerService.compose(composeRequest);

        ContextQueryRequest scope = queryScope(MemoryLane.PROJECT_DEV);
        itemService.deleteItem(60L, scope);
        itemService.revokeItem(61L, scope);

        ArgumentCaptor<ContextAuditEventEntity> captor = ArgumentCaptor.forClass(ContextAuditEventEntity.class);
        verify(auditEventMapper, atLeast(4)).insert(captor.capture());
        List<String> types = captor.getAllValues().stream().map(ContextAuditEventEntity::getEventType).toList();
        assertTrue(types.contains(ContextAuditEventType.CREATE.name()));
        assertTrue(types.contains(ContextAuditEventType.SEARCH.name()));
        assertTrue(types.contains(ContextAuditEventType.INJECT.name()));
        assertTrue(types.contains(ContextAuditEventType.DELETE.name()));
        assertTrue(types.contains(ContextAuditEventType.REVOKE.name()));
    }

    @Test
    void composerTrimsByMaxItemsAndTokenBudget() {
        stubNamespaceLookup(storedNamespace(1L));
        List<ContextItemEntity> items = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ContextItemEntity item = storedItem(100L + i, MemoryLane.PROJECT_DEV, ContextStatus.ACTIVE, null);
            item.setItemType(ContextItemType.FACT.name());
            item.setContent("x".repeat(400));
            items.add(item);
        }
        when(itemMapper.selectList(any())).thenReturn(items);
        when(itemMapper.selectById(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return items.stream().filter(item -> item.getId().equals(id)).findFirst()
                    .orElse(storedItem(id, MemoryLane.PROJECT_DEV, ContextStatus.ACTIVE, null));
        });
        when(namespaceMapper.selectById(1L)).thenReturn(storedNamespace(1L));

        ContextPackageComposeRequest request = new ContextPackageComposeRequest();
        request.setQuery(queryScope(MemoryLane.PROJECT_DEV));
        request.setMaxItems(2);
        request.setTokenBudget(null);
        ContextPackageResponse pkg = composerService.compose(request);

        assertEquals(5, pkg.getTotalItems());
        assertEquals(3, pkg.getTruncatedCount());
        assertEquals(2, pkg.getProjectMemory().size());
    }

    @Test
    void deleteItemIsLogicalNotPhysical() {
        ContextItemEntity entity = storedItem(70L, MemoryLane.PROJECT_DEV, ContextStatus.ACTIVE, null);
        stubItemAccess(entity);

        itemService.deleteItem(70L, queryScope(MemoryLane.PROJECT_DEV));

        assertEquals(ContextStatus.DELETED.name(), entity.getStatus());
        assertNotNull(entity.getDeletedAt());
        verify(itemMapper).updateById(entity);
        verify(itemMapper, never()).deleteById(anyLong());
    }

    @Test
    void tenantVisibilityIsReadableAcrossSiblingProjects() {
        ContextNamespaceEntity siblingNamespace = storedNamespace(2L);
        siblingNamespace.setProjectCode("other-project");
        siblingNamespace.setNamespaceKey("tenant-a:other-project:project");
        when(namespaceMapper.selectList(any())).thenReturn(List.of(storedNamespace(1L), siblingNamespace));

        ContextItemEntity tenantItem = storedItem(80L, MemoryLane.PROJECT_DEV, ContextStatus.ACTIVE, null);
        tenantItem.setNamespaceId(2L);
        tenantItem.setVisibility(ContextVisibility.TENANT.name());
        ContextItemEntity privateSibling = storedItem(81L, MemoryLane.PROJECT_DEV, ContextStatus.ACTIVE, null);
        privateSibling.setNamespaceId(2L);
        privateSibling.setVisibility(ContextVisibility.PRIVATE.name());
        when(itemMapper.selectList(any())).thenReturn(List.of(tenantItem, privateSibling));

        List<ContextSearchResult> hits = retrievalService.search(queryScope(MemoryLane.PROJECT_DEV));

        assertEquals(1, hits.size());
        assertEquals(80L, hits.get(0).getItem().getId());
    }

    @Test
    void bindingTargetDoesNotDropNamespaceScopedItems() {
        stubNamespaceLookup(storedNamespace(1L));
        when(itemMapper.selectList(any())).thenReturn(List.of(
                storedItem(90L, MemoryLane.PROJECT_DEV, ContextStatus.ACTIVE, null)));
        when(bindingMapper.selectList(any())).thenReturn(List.of());

        ContextQueryRequest query = queryScope(MemoryLane.PROJECT_DEV);
        query.setUserId("user-1");

        List<ContextSearchResult> hits = retrievalService.search(query);

        assertEquals(1, hits.size());
        assertEquals("namespace scope", hits.get(0).getHitReason());
    }

    @Test
    void composerBucketsUnlistedTypesByLane() {
        stubNamespaceLookup(storedNamespace(1L));
        ContextItemEntity traceLearning = storedItem(95L, MemoryLane.PROJECT_DEV, ContextStatus.ACTIVE, null);
        traceLearning.setItemType(ContextItemType.TRACE_LEARNING.name());
        when(itemMapper.selectList(any())).thenReturn(List.of(traceLearning));
        stubItemAccess(traceLearning);

        ContextPackageComposeRequest request = new ContextPackageComposeRequest();
        request.setQuery(queryScope(MemoryLane.PROJECT_DEV));
        request.setMaxItems(10);
        ContextPackageResponse pkg = composerService.compose(request);

        assertEquals(1, pkg.getTotalItems());
        assertEquals(1, pkg.getProjectMemory().size());
        assertEquals(0, pkg.getTruncatedCount());
    }

    @Test
    void runtimeUserPrivateMemoryOfUserAIsNotVisibleToUserB() {
        stubNamespaceLookup(storedNamespace(1L));
        ContextItemEntity userAItem = storedItem(200L, MemoryLane.RUNTIME_USER, ContextStatus.ACTIVE, null);
        userAItem.setVisibility(ContextVisibility.PRIVATE.name());
        when(itemMapper.selectList(any())).thenReturn(List.of(userAItem));
        when(bindingMapper.selectList(any())).thenReturn(List.of(storedBinding(200L, ContextBindType.USER, "user-a")));

        List<ContextSearchResult> userBHits = retrievalService.search(runtimeScope("user-b"));
        assertTrue(userBHits.isEmpty());

        List<ContextSearchResult> userAHits = retrievalService.search(runtimeScope("user-a"));
        assertEquals(1, userAHits.size());
        assertEquals(200L, userAHits.get(0).getItem().getId());
    }

    @Test
    void projectDevProjectMemoryDoesNotExposeRuntimeUserPrivateMemory() {
        stubNamespaceLookup(storedNamespace(1L));
        ContextItemEntity projectDevItem = storedItem(300L, MemoryLane.PROJECT_DEV, ContextStatus.ACTIVE, null);
        projectDevItem.setVisibility(ContextVisibility.PROJECT.name());
        ContextItemEntity runtimePrivate = storedItem(301L, MemoryLane.RUNTIME_USER, ContextStatus.ACTIVE, null);
        runtimePrivate.setVisibility(ContextVisibility.PRIVATE.name());
        when(itemMapper.selectList(any()))
                .thenReturn(List.of(projectDevItem))
                .thenReturn(List.of(runtimePrivate));
        when(bindingMapper.selectList(any())).thenReturn(List.of(storedBinding(301L, ContextBindType.USER, "user-a")));

        List<ContextSearchResult> devHits = retrievalService.search(queryScope(MemoryLane.PROJECT_DEV));
        assertEquals(1, devHits.size());
        assertEquals(300L, devHits.get(0).getItem().getId());

        List<ContextSearchResult> runtimeHits = retrievalService.search(runtimeScope("user-b"));
        assertTrue(runtimeHits.isEmpty());
    }

    @Test
    void mutateOperationsFailWhenScopeDoesNotMatchItemOwnership() {
        ContextItemEntity item = storedItem(70L, MemoryLane.RUNTIME_USER, ContextStatus.ACTIVE, null);
        item.setVisibility(ContextVisibility.PRIVATE.name());
        stubItemAccess(item);
        when(bindingMapper.selectList(any())).thenReturn(List.of(storedBinding(70L, ContextBindType.USER, "user-a")));

        ContextQueryRequest wrongUserScope = runtimeScope("user-b");
        assertThrows(IllegalArgumentException.class, () -> itemService.updateItem(70L, new ContextItemUpdateRequest(), wrongUserScope));
        assertThrows(IllegalArgumentException.class, () -> itemService.deleteItem(70L, wrongUserScope));
        assertThrows(IllegalArgumentException.class, () -> itemService.revokeItem(70L, wrongUserScope));
        assertThrows(IllegalArgumentException.class, () -> itemService.verifyItem(70L, null, null, wrongUserScope));
    }

    @Test
    void getItemRequiresScopeWithTenantAndMemoryLane() {
        ContextItemEntity item = storedItem(2L, MemoryLane.PROJECT_DEV, ContextStatus.ACTIVE, null);
        stubItemAccess(item);

        assertThrows(IllegalArgumentException.class, () -> itemService.getItem(2L, null));

        ContextQueryRequest missingTenant = new ContextQueryRequest();
        missingTenant.setMemoryLane(MemoryLane.PROJECT_DEV.name());
        assertThrows(IllegalArgumentException.class, () -> itemService.getItem(2L, missingTenant));

        ContextQueryRequest missingLane = new ContextQueryRequest();
        missingLane.setTenantId("tenant-a");
        assertThrows(IllegalArgumentException.class, () -> itemService.getItem(2L, missingLane));
    }

    @Test
    void createRuntimeUserPrivateWithUserBindingThenQueryIsolation() {
        stubNamespaceLookup(storedNamespace(1L));
        when(namespaceMapper.selectById(1L)).thenReturn(storedNamespace(1L));

        ContextItemCreateRequest create = runtimeUserPrivateCreate("user-a prefers dark mode", "user-a");
        ContextItemResponse created = itemService.createItem(create);

        assertNotNull(created.getId());
        assertEquals(MemoryLane.RUNTIME_USER.name(), created.getMemoryLane());
        assertEquals(ContextVisibility.PRIVATE.name(), created.getVisibility());
        verify(bindingMapper).insert(argThat(binding ->
                ContextBindType.USER.name().equals(binding.getBindType())
                        && "user-a".equals(binding.getBindId())));

        ContextItemEntity stored = storedItem(created.getId(), MemoryLane.RUNTIME_USER, ContextStatus.ACTIVE, null);
        stored.setVisibility(ContextVisibility.PRIVATE.name());
        stored.setContent("user-a prefers dark mode");
        when(itemMapper.selectList(any())).thenReturn(List.of(stored));
        when(bindingMapper.selectList(any())).thenReturn(
                List.of(storedBinding(created.getId(), ContextBindType.USER, "user-a")));

        assertEquals(1, retrievalService.search(runtimeScope("user-a")).size());
        assertTrue(retrievalService.search(runtimeScope("user-b")).isEmpty());
    }

    @Test
    void createItemRejectsCrossTenantNamespaceId() {
        ContextNamespaceEntity tenantBNamespace = storedNamespace(99L);
        tenantBNamespace.setTenantId("tenant-b");
        tenantBNamespace.setProjectCode("other-project");
        when(namespaceMapper.selectById(99L)).thenReturn(tenantBNamespace);

        ContextItemCreateRequest request = itemCreateRequest(99L, MemoryLane.PROJECT_DEV, "cross tenant attempt");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> itemService.createItem(request));
        assertTrue(ex.getMessage().contains("tenant mismatch"));
        verify(itemMapper, never()).insert(any());
    }

    @Test
    void createItemWithProjectIdOnlyMatchesNamespaceProjectId() {
        ContextNamespaceEntity namespace = storedNamespace(1L);
        namespace.setProjectCode(null);
        namespace.setProjectId(42L);
        when(namespaceMapper.selectById(1L)).thenReturn(namespace);

        ContextItemCreateRequest request = itemCreateRequest(1L, MemoryLane.PROJECT_DEV, "project id only create");
        request.setProjectCode(null);
        request.setProjectId(42L);

        ContextItemResponse created = itemService.createItem(request);
        assertNotNull(created.getId());
        verify(itemMapper).insert(any(ContextItemEntity.class));
    }

    @Test
    void createItemRejectsProjectIdOnlyWhenNamespaceOnlyHasProjectCode() {
        ContextNamespaceEntity namespace = storedNamespace(1L);
        namespace.setProjectCode("demo-project");
        namespace.setProjectId(null);
        when(namespaceMapper.selectById(1L)).thenReturn(namespace);

        ContextItemCreateRequest request = itemCreateRequest(1L, MemoryLane.PROJECT_DEV, "mismatched project identity");
        request.setProjectCode(null);
        request.setProjectId(42L);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> itemService.createItem(request));
        assertTrue(ex.getMessage().contains("namespace project mismatch"));
        verify(itemMapper, never()).insert(any());
    }

    @Test
    void createItemRejectsProjectCodeOnlyWhenNamespaceOnlyHasProjectId() {
        ContextNamespaceEntity namespace = storedNamespace(1L);
        namespace.setProjectCode(null);
        namespace.setProjectId(42L);
        when(namespaceMapper.selectById(1L)).thenReturn(namespace);

        ContextItemCreateRequest request = itemCreateRequest(1L, MemoryLane.PROJECT_DEV, "mismatched project identity");
        request.setProjectCode("demo-project");
        request.setProjectId(null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> itemService.createItem(request));
        assertTrue(ex.getMessage().contains("namespace project mismatch"));
        verify(itemMapper, never()).insert(any());
    }

    @Test
    void createItemRejectsWhenProjectCodeMatchesButProjectIdConflicts() {
        ContextNamespaceEntity namespace = storedNamespace(1L);
        namespace.setProjectCode("demo-project");
        namespace.setProjectId(99L);
        when(namespaceMapper.selectById(1L)).thenReturn(namespace);

        ContextItemCreateRequest request = itemCreateRequest(1L, MemoryLane.PROJECT_DEV, "conflicting project id");
        request.setProjectCode("demo-project");
        request.setProjectId(42L);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> itemService.createItem(request));
        assertTrue(ex.getMessage().contains("namespace project mismatch"));
        verify(itemMapper, never()).insert(any());
    }

    @Test
    void createItemRejectsWhenProjectIdMatchesButProjectCodeConflicts() {
        ContextNamespaceEntity namespace = storedNamespace(1L);
        namespace.setProjectCode("other-project");
        namespace.setProjectId(42L);
        when(namespaceMapper.selectById(1L)).thenReturn(namespace);

        ContextItemCreateRequest request = itemCreateRequest(1L, MemoryLane.PROJECT_DEV, "conflicting project code");
        request.setProjectCode("demo-project");
        request.setProjectId(42L);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> itemService.createItem(request));
        assertTrue(ex.getMessage().contains("namespace project mismatch"));
        verify(itemMapper, never()).insert(any());
    }

    @Test
    void createItemAllowsNoProjectContextWhenBothSidesBlank() {
        ContextNamespaceEntity namespace = storedNamespace(1L);
        namespace.setProjectCode(null);
        namespace.setProjectId(null);
        when(namespaceMapper.selectById(1L)).thenReturn(namespace);

        ContextItemCreateRequest request = itemCreateRequest(1L, MemoryLane.PROJECT_DEV, "tenant-wide context");
        request.setProjectCode(null);
        request.setProjectId(null);

        ContextItemResponse created = itemService.createItem(request);
        assertNotNull(created.getId());
        verify(itemMapper).insert(any(ContextItemEntity.class));
    }

    @Test
    void listItemsIncludesRevokedAndStaleStatuses() {
        when(namespaceMapper.selectList(any())).thenReturn(List.of(storedNamespace(1L)));
        ContextItemEntity active = storedItem(400L, MemoryLane.PROJECT_DEV, ContextStatus.ACTIVE, null);
        ContextItemEntity revoked = storedItem(401L, MemoryLane.PROJECT_DEV, ContextStatus.REVOKED, null);
        ContextItemEntity stale = storedItem(402L, MemoryLane.PROJECT_DEV, ContextStatus.STALE, null);
        when(itemMapper.selectList(any())).thenReturn(List.of(active, revoked, stale));

        ContextItemListRequest request = listRequest(MemoryLane.PROJECT_DEV);
        List<ContextItemResponse> all = itemService.listItems(request);
        assertEquals(3, all.size());

        request.setStatus(ContextStatus.REVOKED.name());
        List<ContextItemResponse> revokedOnly = itemService.listItems(request);
        assertEquals(1, revokedOnly.size());
        assertEquals(ContextStatus.REVOKED.name(), revokedOnly.get(0).getStatus());
    }

    @Test
    void listItemsDoesNotLeakRuntimeUserPrivateMemoryForProjectDevLane() {
        when(namespaceMapper.selectList(any())).thenReturn(List.of(storedNamespace(1L)));
        ContextItemEntity projectDev = storedItem(410L, MemoryLane.PROJECT_DEV, ContextStatus.ACTIVE, null);
        projectDev.setVisibility(ContextVisibility.PROJECT.name());
        ContextItemEntity runtimePrivate = storedItem(411L, MemoryLane.RUNTIME_USER, ContextStatus.ACTIVE, null);
        runtimePrivate.setVisibility(ContextVisibility.PRIVATE.name());
        when(itemMapper.selectList(any())).thenReturn(List.of(projectDev));

        ContextItemListRequest request = listRequest(MemoryLane.PROJECT_DEV);
        List<ContextItemResponse> listed = itemService.listItems(request);

        assertEquals(1, listed.size());
        assertEquals(MemoryLane.PROJECT_DEV.name(), listed.get(0).getMemoryLane());
    }

    @Test
    void listItemsFiltersByKeyword() {
        when(namespaceMapper.selectList(any())).thenReturn(List.of(storedNamespace(1L)));
        ContextItemEntity match = storedItem(420L, MemoryLane.PROJECT_DEV, ContextStatus.ACTIVE, null);
        match.setTitle("orders module background");
        ContextItemEntity other = storedItem(421L, MemoryLane.PROJECT_DEV, ContextStatus.ACTIVE, null);
        other.setTitle("unrelated");
        other.setContent("payment flow documentation");
        other.setSummary("payment");
        when(itemMapper.selectList(any())).thenReturn(List.of(match, other));

        ContextItemListRequest request = listRequest(MemoryLane.PROJECT_DEV);
        request.setKeyword("orders");
        List<ContextItemResponse> listed = itemService.listItems(request);

        assertEquals(1, listed.size());
        assertEquals(420L, listed.get(0).getId());
    }

    @Test
    void listItemsWithProjectIdOnlyMatchesNamespaceProjectId() {
        ContextNamespaceEntity namespace = storedNamespace(1L);
        namespace.setProjectCode(null);
        namespace.setProjectId(42L);
        when(namespaceMapper.selectList(any())).thenReturn(List.of(namespace));

        ContextItemEntity item = storedItem(430L, MemoryLane.PROJECT_DEV, ContextStatus.ACTIVE, null);
        item.setVisibility(ContextVisibility.PROJECT.name());
        when(itemMapper.selectList(any())).thenReturn(List.of(item));

        ContextItemListRequest request = listRequest(MemoryLane.PROJECT_DEV);
        request.setProjectCode(null);
        request.setProjectId(42L);

        List<ContextItemResponse> listed = itemService.listItems(request);
        assertEquals(1, listed.size());
        assertEquals(430L, listed.get(0).getId());
    }

    @Test
    void listItemsWithProjectIdOnlyRejectsOtherProjectNamespace() {
        ContextNamespaceEntity otherProject = storedNamespace(2L);
        otherProject.setProjectCode(null);
        otherProject.setProjectId(99L);
        when(namespaceMapper.selectList(any())).thenReturn(List.of(otherProject));

        ContextItemEntity item = storedItem(431L, MemoryLane.PROJECT_DEV, ContextStatus.ACTIVE, null);
        item.setNamespaceId(2L);
        item.setVisibility(ContextVisibility.PROJECT.name());
        when(itemMapper.selectList(any())).thenReturn(List.of(item));

        ContextItemListRequest request = listRequest(MemoryLane.PROJECT_DEV);
        request.setProjectCode(null);
        request.setProjectId(42L);

        List<ContextItemResponse> listed = itemService.listItems(request);
        assertTrue(listed.isEmpty());
    }

    @Test
    void listItemsRejectsProjectCodeOnlyWhenNamespaceOnlyHasProjectId() {
        ContextNamespaceEntity namespace = storedNamespace(1L);
        namespace.setProjectCode(null);
        namespace.setProjectId(42L);
        when(namespaceMapper.selectList(any())).thenReturn(List.of(namespace));

        ContextItemEntity item = storedItem(432L, MemoryLane.PROJECT_DEV, ContextStatus.ACTIVE, null);
        item.setVisibility(ContextVisibility.PROJECT.name());
        when(itemMapper.selectList(any())).thenReturn(List.of(item));

        ContextItemListRequest request = listRequest(MemoryLane.PROJECT_DEV);
        request.setProjectCode("demo-project");
        request.setProjectId(null);

        List<ContextItemResponse> listed = itemService.listItems(request);
        assertTrue(listed.isEmpty());
    }

    @Test
    void listItemsRejectsWhenProjectCodeMatchesButProjectIdConflicts() {
        ContextNamespaceEntity namespace = storedNamespace(1L);
        namespace.setProjectCode("demo-project");
        namespace.setProjectId(99L);
        when(namespaceMapper.selectList(any())).thenReturn(List.of(namespace));

        ContextItemEntity item = storedItem(433L, MemoryLane.PROJECT_DEV, ContextStatus.ACTIVE, null);
        item.setVisibility(ContextVisibility.PROJECT.name());
        when(itemMapper.selectList(any())).thenReturn(List.of(item));

        ContextItemListRequest request = listRequest(MemoryLane.PROJECT_DEV);
        request.setProjectCode("demo-project");
        request.setProjectId(42L);

        List<ContextItemResponse> listed = itemService.listItems(request);
        assertTrue(listed.isEmpty());
    }

    @Test
    void buildNamespaceKeyUsesProjectCodeWhenPresentKeepsExistingShape() {
        ContextNamespaceRequest request = new ContextNamespaceRequest();
        request.setTenantId("tenant-a");
        request.setProjectCode("Demo-Project");
        request.setProjectId(42L);
        request.setNamespaceType(ContextNamespaceType.USER.name());
        request.setOwnerType("USER");
        request.setOwnerId("user-a");

        String key = ContextKeyFactory.buildNamespaceKey(request);
        assertEquals("tenant-a:demo-project:user:user:user-a", key);
    }

    @Test
    void buildNamespaceKeyUsesProjectIdWhenProjectCodeMissing() {
        ContextNamespaceRequest request = new ContextNamespaceRequest();
        request.setTenantId("tenant-a");
        request.setProjectId(42L);
        request.setNamespaceType(ContextNamespaceType.USER.name());
        request.setOwnerType("USER");
        request.setOwnerId("user-a");

        String key = ContextKeyFactory.buildNamespaceKey(request);
        assertEquals("tenant-a:pid-42:user:user:user-a", key);
    }

    @Test
    void buildNamespaceKeyForTenantScopeStillOmitsProjectToken() {
        ContextNamespaceRequest request = new ContextNamespaceRequest();
        request.setTenantId("tenant-a");
        request.setNamespaceType(ContextNamespaceType.TENANT.name());

        String key = ContextKeyFactory.buildNamespaceKey(request);
        assertEquals("tenant-a:tenant", key);
    }

    @Test
    void createOrGetNamespaceWithProjectIdOnlyDoesNotCollideAcrossProjectIds() {
        when(namespaceMapper.selectOne(any())).thenReturn(null);

        ContextNamespaceRequest request42 = projectIdOnlyNamespaceRequest(42L, "user-a");
        namespaceService.createOrGetNamespace(request42);

        ContextNamespaceRequest request99 = projectIdOnlyNamespaceRequest(99L, "user-b");
        namespaceService.createOrGetNamespace(request99);

        ArgumentCaptor<ContextNamespaceEntity> captor = ArgumentCaptor.forClass(ContextNamespaceEntity.class);
        verify(namespaceMapper, times(2)).insert(captor.capture());
        List<String> keys = captor.getAllValues().stream().map(ContextNamespaceEntity::getNamespaceKey).toList();
        assertEquals(2, keys.size());
        assertNotEquals(keys.get(0), keys.get(1));
        assertTrue(keys.stream().anyMatch(k -> k.contains("pid-42")));
        assertTrue(keys.stream().anyMatch(k -> k.contains("pid-99")));
    }

    private ContextNamespaceRequest projectIdOnlyNamespaceRequest(Long projectId, String ownerId) {
        ContextNamespaceRequest request = new ContextNamespaceRequest();
        request.setNamespaceType(ContextNamespaceType.USER.name());
        request.setTenantId("tenant-a");
        request.setProjectId(projectId);
        request.setOwnerType("USER");
        request.setOwnerId(ownerId);
        return request;
    }

    private ContextItemListRequest listRequest(MemoryLane lane) {
        ContextItemListRequest request = new ContextItemListRequest();
        request.setTenantId("tenant-a");
        request.setProjectCode("demo-project");
        request.setMemoryLane(lane.name());
        request.setLimit(100);
        return request;
    }

    private ContextItemCreateRequest runtimeUserPrivateCreate(String content, String userId) {
        ContextItemCreateRequest request = itemCreateRequest(1L, MemoryLane.RUNTIME_USER, content);
        request.setVisibility(ContextVisibility.PRIVATE.name());
        request.setUserId(userId);
        return request;
    }

    private void stubItemAccess(ContextItemEntity item) {
        when(itemMapper.selectById(item.getId())).thenReturn(item);
        when(namespaceMapper.selectById(item.getNamespaceId())).thenReturn(storedNamespace(item.getNamespaceId()));
    }

    private ContextQueryRequest runtimeScope(String userId) {
        ContextQueryRequest query = queryScope(MemoryLane.RUNTIME_USER);
        query.setUserId(userId);
        return query;
    }

    private void stubNamespaceLookup(ContextNamespaceEntity existing) {
        when(namespaceMapper.selectOne(any())).thenReturn(existing);
        when(namespaceMapper.selectList(any())).thenReturn(List.of(existing != null ? existing : storedNamespace(1L)));
        if (existing != null) {
            when(namespaceMapper.selectById(existing.getId())).thenReturn(existing);
        }
    }

    private ContextNamespaceRequest namespaceRequest() {
        ContextNamespaceRequest request = new ContextNamespaceRequest();
        request.setNamespaceType(ContextNamespaceType.PROJECT.name());
        request.setTenantId("tenant-a");
        request.setProjectCode("demo-project");
        request.setDisplayName("Demo Project Memory");
        return request;
    }

    private ContextItemCreateRequest itemCreateRequest(Long namespaceId, MemoryLane lane, String content) {
        ContextItemCreateRequest request = new ContextItemCreateRequest();
        request.setNamespaceId(namespaceId);
        request.setItemType(ContextItemType.FACT.name());
        request.setMemoryLane(lane.name());
        request.setContent(content);
        request.setSourceType(ContextSourceType.MANUAL.name());
        request.setTenantId("tenant-a");
        request.setProjectCode("demo-project");
        request.setTitle("test fact");
        return request;
    }

    private ContextQueryRequest queryScope(MemoryLane lane) {
        ContextQueryRequest query = new ContextQueryRequest();
        query.setTenantId("tenant-a");
        query.setProjectCode("demo-project");
        query.setMemoryLane(lane.name());
        return query;
    }

    private ContextEvidenceRequest evidenceRequest() {
        ContextEvidenceRequest request = new ContextEvidenceRequest();
        request.setEvidenceType("MANUAL_NOTE");
        request.setEvidenceExcerpt("verified by reviewer");
        return request;
    }

    private ContextNamespaceEntity storedNamespace(Long id) {
        ContextNamespaceEntity entity = new ContextNamespaceEntity();
        entity.setId(id);
        entity.setNamespaceKey("tenant-a:demo-project:project");
        entity.setNamespaceType(ContextNamespaceType.PROJECT.name());
        entity.setTenantId("tenant-a");
        entity.setProjectCode("demo-project");
        entity.setStatus(ContextStatus.ACTIVE.name());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }

    private ContextItemEntity storedItem(Long id, MemoryLane lane, ContextStatus status, LocalDateTime expiresAt) {
        ContextItemEntity entity = new ContextItemEntity();
        entity.setId(id);
        entity.setItemKey("ctx-item-" + id);
        entity.setNamespaceId(1L);
        entity.setItemType(ContextItemType.FACT.name());
        entity.setMemoryLane(lane.name());
        entity.setTitle("orders");
        entity.setContent("orders module uses soft delete");
        entity.setSummary("orders summary");
        entity.setSourceType(ContextSourceType.MANUAL.name());
        entity.setConfidence(new BigDecimal("0.8000"));
        entity.setTrustLevel(ContextTrustLevel.MEDIUM.name());
        entity.setVisibility(ContextVisibility.PROJECT.name());
        entity.setStatus(status.name());
        entity.setExpiresAt(expiresAt);
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }

    private ContextBindingEntity storedBinding(Long itemId, ContextBindType bindType, String bindId) {
        ContextBindingEntity entity = new ContextBindingEntity();
        entity.setId(idSeq.getAndIncrement());
        entity.setItemId(itemId);
        entity.setBindType(bindType.name());
        entity.setBindId(bindId);
        entity.setStatus(ContextStatus.ACTIVE.name());
        return entity;
    }

    private ContextEvidenceEntity storedEvidence(Long id, Long itemId) {
        ContextEvidenceEntity entity = new ContextEvidenceEntity();
        entity.setId(id);
        entity.setItemId(itemId);
        entity.setEvidenceType("MANUAL_NOTE");
        entity.setEvidenceExcerpt("verified by reviewer");
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }
}
