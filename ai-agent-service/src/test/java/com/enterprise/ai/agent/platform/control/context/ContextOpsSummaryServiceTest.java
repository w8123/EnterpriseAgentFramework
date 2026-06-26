package com.enterprise.ai.agent.platform.control.context;

import com.enterprise.ai.agent.platform.control.context.memory.ContextMemoryCandidateEntity;
import com.enterprise.ai.agent.platform.control.context.memory.ContextMemoryCandidateMapper;
import com.enterprise.ai.agent.platform.control.context.memory.ContextMemoryCandidateStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ContextOpsSummaryServiceTest {

    private ContextNamespaceService namespaceService;
    private ContextItemMapper itemMapper;
    private ContextMemoryCandidateMapper candidateMapper;
    private ContextAuditEventMapper auditEventMapper;
    private ContextAccessPolicyService accessPolicyService;
    private ContextOpsSummaryService service;

    @BeforeEach
    void setUp() {
        namespaceService = mock(ContextNamespaceService.class);
        itemMapper = mock(ContextItemMapper.class);
        candidateMapper = mock(ContextMemoryCandidateMapper.class);
        auditEventMapper = mock(ContextAuditEventMapper.class);
        accessPolicyService = mock(ContextAccessPolicyService.class);
        when(accessPolicyService.requireMemoryLane(anyString())).thenAnswer(invocation ->
                MemoryLane.valueOf(invocation.getArgument(0, String.class).trim().toUpperCase(Locale.ROOT)));
        service = new ContextOpsSummaryService(
                accessPolicyService, namespaceService, itemMapper, candidateMapper, auditEventMapper);
    }

    @Test
    void opsSummaryRejectsMissingTenantId() {
        assertThrows(IllegalArgumentException.class,
                () -> service.summarize(null, null, null, null, false));
        assertThrows(IllegalArgumentException.class,
                () -> service.summarize("  ", null, null, null, false));
    }

    @Test
    void opsSummaryDefaultsToProjectDev() {
        ContextNamespaceEntity ns = namespace(1L);
        when(namespaceService.listActiveNamespaceEntities(any(), any(), any(), any()))
                .thenReturn(List.of(ns));
        stubItemCounts(1L, 1L, 0L, 0L, 0L, 0L, 0L, 1L);
        stubCandidateCounts(0L, 0L);
        when(auditEventMapper.selectCount(any())).thenReturn(0L);

        ContextOpsSummaryResponse summary = service.summarize("tenant-a", "demo-project", null, null, false);

        assertEquals(MemoryLane.PROJECT_DEV.name(), summary.getMemoryLane());
        assertEquals(1, summary.getItemCount());
        assertEquals(1, summary.getActiveItemCount());
        assertEquals(1, summary.getRuntimeUserExcludedCount());
    }

    @Test
    void opsSummaryUsesSqlCountsInsteadOfLoadingAllItemsAndCandidates() {
        when(namespaceService.listActiveNamespaceEntities(any(), any(), any(), any()))
                .thenReturn(List.of(namespace(1L)));
        when(itemMapper.selectList(any())).thenReturn(List.of(
                item(1L, MemoryLane.PROJECT_DEV, ContextStatus.ACTIVE),
                item(2L, MemoryLane.RUNTIME_USER, ContextStatus.ACTIVE)));
        when(candidateMapper.selectList(any())).thenReturn(List.of(candidate(ContextMemoryCandidateStatus.PENDING)));
        when(itemMapper.selectCount(any())).thenReturn(1L);
        when(candidateMapper.selectCount(any())).thenReturn(1L);
        when(auditEventMapper.selectCount(any())).thenReturn(0L);

        ContextOpsSummaryResponse summary = service.summarize("tenant-a", "demo-project", null, "PROJECT_DEV", false);

        assertEquals(1, summary.getItemCount());
        verify(itemMapper, never()).selectList(any());
        verify(candidateMapper, never()).selectList(any());
        verify(itemMapper, atLeastOnce()).selectCount(any());
        verify(candidateMapper, atLeastOnce()).selectCount(any());
    }

    @Test
    void opsSummaryCountsProjectDevItemsByStatus() {
        when(namespaceService.listActiveNamespaceEntities(any(), any(), any(), any()))
                .thenReturn(List.of(namespace(1L)));
        stubItemCounts(4L, 1L, 1L, 1L, 1L, 0L, 0L, 0L);
        stubCandidateCounts(0L, 0L);
        when(auditEventMapper.selectCount(any())).thenReturn(2L);

        ContextOpsSummaryResponse summary = service.summarize("tenant-a", null, null, "PROJECT_DEV", false);

        assertEquals(4, summary.getItemCount());
        assertEquals(1, summary.getActiveItemCount());
        assertEquals(1, summary.getStaleItemCount());
        assertEquals(1, summary.getRevokedItemCount());
        assertEquals(1, summary.getDeletedItemCount());
        assertEquals(2, summary.getAuditEventCountRecent());
    }

    @Test
    void opsSummaryCountsPendingAndExpiredCandidates() {
        when(namespaceService.listActiveNamespaceEntities(any(), any(), any(), any()))
                .thenReturn(List.of(namespace(1L)));
        stubItemCounts(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        when(auditEventMapper.selectCount(any())).thenReturn(0L);
        stubCandidateCounts(1L, 1L);

        ContextOpsSummaryResponse summary = service.summarize("tenant-a", "demo-project", null, "PROJECT_DEV", false);

        assertEquals(1, summary.getPendingCandidateCount());
        assertEquals(1, summary.getExpiredCandidateCount());
    }

    @Test
    void opsSummaryDoesNotExposeRuntimeUserByDefault() {
        when(namespaceService.listActiveNamespaceEntities(any(), any(), any(), any()))
                .thenReturn(List.of(namespace(1L)));
        stubItemCounts(0L, 0L, 0L, 0L, 0L, 0L, 0L, 1L);
        stubCandidateCounts(0L, 0L);
        when(auditEventMapper.selectCount(any())).thenReturn(0L);

        ContextOpsSummaryResponse summary = service.summarize("tenant-a", null, null, "PROJECT_DEV", false);

        assertEquals(0, summary.getItemCount());
        assertEquals(1, summary.getRuntimeUserExcludedCount());
        assertFalse(summary.getWarnings().isEmpty());
    }

    @Test
    void opsSummaryRejectsRuntimeUserWhenIncludeRuntimeUserFalse() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.summarize("tenant-a", null, null, "RUNTIME_USER", false));
        assertTrue(ex.getMessage().contains("includeRuntimeUser=true"));
    }

    @Test
    void opsSummaryAllowsRuntimeUserAggregateWhenExplicitlyIncluded() {
        when(namespaceService.listActiveNamespaceEntities(any(), any(), any(), any()))
                .thenReturn(List.of(namespace(1L)));
        stubItemCounts(1L, 1L, 0L, 0L, 0L, 0L, 0L);
        stubCandidateCounts(0L, 0L);
        when(auditEventMapper.selectCount(any())).thenReturn(0L);

        ContextOpsSummaryResponse summary = service.summarize("tenant-a", null, null, "RUNTIME_USER", true);

        assertEquals(MemoryLane.RUNTIME_USER.name(), summary.getMemoryLane());
        assertEquals(1, summary.getItemCount());
        assertEquals(1, summary.getActiveItemCount());
        assertTrue(summary.getWarnings().stream()
                .anyMatch(w -> w.contains("aggregate-only") || w.contains("private item details")));
    }

    @Test
    void opsSummaryRecentAuditFiltersByProjectId() {
        when(namespaceService.listActiveNamespaceEntities(any(), any(), any(), any()))
                .thenReturn(List.of(namespace(1L)));
        stubItemCounts(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        stubCandidateCounts(0L, 0L);
        when(auditEventMapper.selectCount(any())).thenReturn(3L);

        ContextOpsSummaryResponse summary = service.summarize("tenant-a", null, 42L, "PROJECT_DEV", false);

        assertEquals(3, summary.getAuditEventCountRecent());
        assertEquals(42L, summary.getProjectId());
        verify(auditEventMapper).selectCount(any());
    }

    private ContextNamespaceEntity namespace(Long id) {
        ContextNamespaceEntity entity = new ContextNamespaceEntity();
        entity.setId(id);
        entity.setTenantId("tenant-a");
        entity.setProjectCode("demo-project");
        entity.setStatus(ContextStatus.ACTIVE.name());
        return entity;
    }

    private ContextItemEntity item(Long id, MemoryLane lane, ContextStatus status) {
        ContextItemEntity entity = new ContextItemEntity();
        entity.setId(id);
        entity.setNamespaceId(1L);
        entity.setMemoryLane(lane.name());
        entity.setStatus(status.name());
        entity.setContent("content-" + id);
        return entity;
    }

    private ContextMemoryCandidateEntity candidate(ContextMemoryCandidateStatus status) {
        ContextMemoryCandidateEntity entity = new ContextMemoryCandidateEntity();
        entity.setTenantId("tenant-a");
        entity.setProjectCode("demo-project");
        entity.setStatus(status.name());
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }

    private void stubItemCounts(Long... counts) {
        when(itemMapper.selectCount(any())).thenReturn(counts[0], tail(counts));
    }

    private void stubCandidateCounts(Long pendingCount, Long expiredCount) {
        when(candidateMapper.selectCount(any())).thenReturn(pendingCount, expiredCount);
    }

    private Long[] tail(Long[] values) {
        return Arrays.copyOfRange(values, 1, values.length);
    }
}
