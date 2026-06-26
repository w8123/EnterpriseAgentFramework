package com.enterprise.ai.agent.platform.control.context;

import com.enterprise.ai.agent.platform.control.context.memory.ContextMemoryCandidateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContextLifecycleServiceTest {

    private ContextMemoryCandidateService candidateService;
    private ContextItemService itemService;
    private ContextAuditService auditService;
    private ContextAccessPolicyService accessPolicyService;
    private ContextLifecycleService lifecycleService;

    private final Map<Long, ContextNamespaceEntity> namespaces = new HashMap<>();

    @BeforeEach
    void setUp() {
        candidateService = mock(ContextMemoryCandidateService.class);
        itemService = mock(ContextItemService.class);
        auditService = mock(ContextAuditService.class);
        accessPolicyService = mock(ContextAccessPolicyService.class);
        lifecycleService = new ContextLifecycleService(
                accessPolicyService, candidateService, itemService, auditService);
    }

    @Test
    void lifecycleRejectsMissingTenantId() {
        assertThrows(IllegalArgumentException.class,
                () -> lifecycleService.run(new ContextLifecycleRunRequest()));
        assertThrows(IllegalArgumentException.class,
                () -> lifecycleService.run(null));
    }

    @Test
    void lifecycleDryRunDoesNotMutateCandidatesOrItems() {
        ContextNamespaceEntity namespace = storedNamespace(1L, "demo-project", null);
        namespaces.put(1L, namespace);
        ContextItemEntity projectDevStale = staleItem(10L, MemoryLane.PROJECT_DEV);
        when(candidateService.markExpiredCandidates("tenant-a", true, 500)).thenReturn(2);
        when(itemService.loadNamespacesByTenant("tenant-a")).thenReturn(namespaces);
        when(itemService.listStaleCandidatesForLifecycle(anyCollection(), eq(500)))
                .thenReturn(List.of(projectDevStale));

        ContextLifecycleRunRequest request = lifecycleRequest("tenant-a");
        request.setDryRun(true);
        ContextLifecycleRunResponse response = lifecycleService.run(request);

        assertTrue(response.isDryRun());
        assertEquals(2, response.getExpiredCandidateCount());
        assertEquals(1, response.getStaleItemCount());
        assertEquals(1, response.getScannedItemCount());
        verify(itemService, never()).markStaleByLifecycle(any(), any(), any(), any(), any());
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void lifecycleExpiresPendingCandidates() {
        when(candidateService.markExpiredCandidates("tenant-a", false, 500)).thenReturn(3);
        when(itemService.loadNamespacesByTenant("tenant-a")).thenReturn(namespaces);
        when(itemService.listStaleCandidatesForLifecycle(anyCollection(), eq(500))).thenReturn(List.of());

        ContextLifecycleRunResponse response = lifecycleService.run(lifecycleRequest("tenant-a"));

        assertEquals(3, response.getExpiredCandidateCount());
        verify(candidateService).markExpiredCandidates("tenant-a", false, 500);
        verify(auditService).record(eq(ContextAuditEventType.LIFECYCLE_RUN), any(), any(), any(), isNull(), isNull());
    }

    @Test
    void lifecycleMarksProjectDevItemsStaleWhenStaleAfterPassed() {
        ContextNamespaceEntity namespace = storedNamespace(1L, "demo-project", null);
        namespaces.put(1L, namespace);
        ContextItemEntity staleItem = staleItem(20L, MemoryLane.PROJECT_DEV);

        when(candidateService.markExpiredCandidates(anyString(), eq(false), anyInt())).thenReturn(0);
        when(itemService.loadNamespacesByTenant("tenant-a")).thenReturn(namespaces);
        when(itemService.listStaleCandidatesForLifecycle(anyCollection(), eq(500))).thenReturn(List.of(staleItem));

        ContextLifecycleRunResponse response = lifecycleService.run(lifecycleRequest("tenant-a"));

        assertEquals(1, response.getStaleItemCount());
        verify(itemService).markStaleByLifecycle(staleItem, namespace, "tenant-a", null, null);
    }

    @Test
    void lifecycleDoesNotStaleRuntimeUserItemsByDefault() {
        ContextNamespaceEntity namespace = storedNamespace(1L, "demo-project", null);
        namespaces.put(1L, namespace);
        ContextItemEntity runtimeStale = staleItem(30L, MemoryLane.RUNTIME_USER);

        when(candidateService.markExpiredCandidates(anyString(), eq(false), anyInt())).thenReturn(0);
        when(itemService.loadNamespacesByTenant("tenant-a")).thenReturn(namespaces);
        when(itemService.listStaleCandidatesForLifecycle(anyCollection(), eq(500))).thenReturn(List.of(runtimeStale));

        ContextLifecycleRunResponse response = lifecycleService.run(lifecycleRequest("tenant-a"));

        assertEquals(0, response.getStaleItemCount());
        assertEquals(1, response.getSkippedRuntimeUserItemCount());
        verify(itemService, never()).markStaleByLifecycle(any(), any(), any(), any(), any());
    }

    @Test
    void lifecycleFiltersItemsByProjectIdentity() {
        ContextNamespaceEntity namespace42 = storedNamespace(1L, null, 42L);
        ContextNamespaceEntity namespace99 = storedNamespace(2L, null, 99L);
        namespaces.put(1L, namespace42);
        namespaces.put(2L, namespace99);

        ContextItemEntity item42 = staleItem(40L, MemoryLane.PROJECT_DEV);
        item42.setNamespaceId(1L);
        ContextItemEntity item99 = staleItem(41L, MemoryLane.PROJECT_DEV);
        item99.setNamespaceId(2L);

        when(candidateService.markExpiredCandidates(anyString(), eq(false), anyInt())).thenReturn(0);
        when(itemService.loadNamespacesByTenant("tenant-a")).thenReturn(namespaces);
        when(itemService.listStaleCandidatesForLifecycle(anyCollection(), eq(500)))
                .thenReturn(List.of(item42, item99));

        ContextLifecycleRunRequest request = lifecycleRequest("tenant-a");
        request.setProjectId(42L);
        ContextLifecycleRunResponse response = lifecycleService.run(request);

        assertEquals(1, response.getStaleItemCount());
        assertEquals(2, response.getScannedItemCount());
        verify(itemService).markStaleByLifecycle(eq(item42), eq(namespace42), eq("tenant-a"), isNull(), eq(42L));
        verify(itemService, never()).markStaleByLifecycle(eq(item99), any(), any(), any(), any());
    }

    private ContextLifecycleRunRequest lifecycleRequest(String tenantId) {
        ContextLifecycleRunRequest request = new ContextLifecycleRunRequest();
        request.setTenantId(tenantId);
        return request;
    }

    private ContextNamespaceEntity storedNamespace(Long id, String projectCode, Long projectId) {
        ContextNamespaceEntity entity = new ContextNamespaceEntity();
        entity.setId(id);
        entity.setTenantId("tenant-a");
        entity.setProjectCode(projectCode);
        entity.setProjectId(projectId);
        entity.setStatus(ContextStatus.ACTIVE.name());
        return entity;
    }

    private ContextItemEntity staleItem(Long id, MemoryLane lane) {
        ContextItemEntity entity = new ContextItemEntity();
        entity.setId(id);
        entity.setNamespaceId(1L);
        entity.setMemoryLane(lane.name());
        entity.setStatus(ContextStatus.ACTIVE.name());
        entity.setStaleAfter(LocalDateTime.now().minusHours(1));
        entity.setContent("stale content");
        return entity;
    }
}
