package com.enterprise.ai.runtime.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class RuntimeSessionRetentionServiceTest {

    @Test
    void scheduledCleanupRecoversInterruptedClearBeforePurgingDueSessions() {
        RuntimeSessionRetentionStore store = mock(RuntimeSessionRetentionStore.class);
        RuntimeSessionStateEraser eraser = mock(RuntimeSessionStateEraser.class);
        RuntimeConversationSessionEntity clearing = session(1L, "CLEARING");
        RuntimeConversationSessionEntity active = session(2L, "ACTIVE");
        when(store.dueCandidates(any())).thenReturn(List.of(clearing, active));
        when(store.claimClearRecovery(any(), anyString(), any())).thenReturn(true);
        when(store.claimRetentionPurge(any(), anyString(), any())).thenReturn(true);
        RuntimeSessionRetentionService service = service(store, eraser);

        RuntimeSessionRetentionService.CleanupResult result = service.cleanupDue();

        assertEquals(1, result.recoveredClears());
        assertEquals(1, result.purged());
        assertEquals(0, result.notCompleted());
        verify(eraser).clearTransient(clearing);
        verify(eraser).purgeAll(active);
        verify(store).completeClear(any(), anyString(), anyString(), any(), any());
        verify(store).finalizePurge(any(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void failedExternalEraseLeavesClaimedRowForRetryAndRecordsOnlyFailureType() {
        RuntimeSessionRetentionStore store = mock(RuntimeSessionRetentionStore.class);
        RuntimeSessionStateEraser eraser = mock(RuntimeSessionStateEraser.class);
        RuntimeConversationSessionEntity active = session(2L, "ACTIVE");
        when(store.dueCandidates(any())).thenReturn(List.of(active));
        when(store.claimRetentionPurge(any(), anyString(), any())).thenReturn(true);
        doThrow(new IllegalStateException("secret conversation must not be audited"))
                .when(eraser).purgeAll(active);
        RuntimeSessionRetentionService service = service(store, eraser);

        RuntimeSessionRetentionService.CleanupResult result = service.cleanupDue();

        assertEquals(1, result.notCompleted());
        verify(store, never()).finalizePurge(any(), anyString(), anyString(), any(), any(), any());
        verify(store).recordFailure(any(), anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void manualEraseReturnsOnlyPseudonymousSessionIdentity() {
        RuntimeSessionRetentionStore store = mock(RuntimeSessionRetentionStore.class);
        RuntimeSessionStateEraser eraser = mock(RuntimeSessionStateEraser.class);
        RuntimeConversationSessionEntity claimed = session(3L, "PURGING");
        claimed.setLifecycleReasonCode("PRIVACY_REQUEST");
        when(store.claimManualPurge(anyString(), anyString(), anyString(), any(), anyString(),
                anyString(), any())).thenReturn(claimed);
        RuntimeSessionRetentionService service = service(store, eraser);

        RuntimeSessionRetentionService.EraseResult result = service.erase(
                "tenant-a", "visible-session", "privacy_request", "REQ-1", "42");

        assertEquals("ERASED", result.status());
        assertEquals(64, result.sessionIdHash().length());
        assertFalse(result.sessionIdHash().contains("visible-session"));
        verify(eraser).purgeAll(claimed);
        verify(store).finalizePurge(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void ownerEraseIsBoundedPseudonymousAndReportsEveryIncompleteFence() {
        RuntimeSessionRetentionStore store = mock(RuntimeSessionRetentionStore.class);
        RuntimeSessionStateEraser eraser = mock(RuntimeSessionStateEraser.class);
        RuntimeConversationSessionEntity active = session(1L, "ACTIVE");
        RuntimeConversationSessionEntity held = session(2L, "ACTIVE");
        held.setLegalHold(true);
        RuntimeConversationSessionEntity busy = session(3L, "ACTIVE");
        when(store.ownerEraseCandidates("tenant-a", "runtime-user-secret", 25))
                .thenReturn(List.of(active, held, busy));
        when(store.claimManualPurge(eq("tenant-a"), eq("session-1"),
                eq("PRIVACY_REQUEST"), eq("REQ-7"), anyString(), anyString(), any()))
                .thenReturn(active);
        when(store.claimManualPurge(eq("tenant-a"), eq("session-3"),
                eq("PRIVACY_REQUEST"), eq("REQ-7"), anyString(), anyString(), any()))
                .thenThrow(RuntimeSessionRetentionException.activeTurn());
        when(store.countOwnerSessions("tenant-a", "runtime-user-secret")).thenReturn(2L);
        RuntimeSessionRetentionService service = service(store, eraser);

        RuntimeSessionRetentionService.OwnerEraseResult result = service.eraseOwner(
                "tenant-a", "runtime-user-secret", "privacy_request", "REQ-7", 25, "42");

        assertEquals("RETRY_REQUIRED", result.status());
        assertEquals(3, result.selected());
        assertEquals(1, result.erased());
        assertEquals(1, result.legalHoldBlocked());
        assertEquals(1, result.retryable());
        assertEquals(0, result.failed());
        assertEquals(2, result.remaining());
        assertFalse(result.complete());
        assertEquals(64, result.runtimeUserHash().length());
        assertFalse(result.runtimeUserHash().contains("runtime-user-secret"));
        verify(eraser).purgeAll(active);
        verify(store, org.mockito.Mockito.times(2)).recordOwnerEraseNotCompleted(
                any(), anyString(), eq("PRIVACY_REQUEST"), eq("REQ-7"), anyString(), any());
    }

    @Test
    void ownerEraseIsIdempotentlyCompleteWhenNoSessionsRemain() {
        RuntimeSessionRetentionStore store = mock(RuntimeSessionRetentionStore.class);
        when(store.ownerEraseCandidates("tenant-a", "user-a", 100)).thenReturn(List.of());
        when(store.countOwnerSessions("tenant-a", "user-a")).thenReturn(0L);
        RuntimeSessionRetentionService service = service(store, mock(RuntimeSessionStateEraser.class));

        RuntimeSessionRetentionService.OwnerEraseResult result = service.eraseOwner(
                "tenant-a", "user-a", "PRIVACY_REQUEST", null, null, "42");

        assertEquals("ERASED", result.status());
        assertTrue(result.complete());
        assertEquals(0, result.selected());
        assertEquals(0, result.remaining());
    }

    @Test
    void ownerEraseRejectsUnboundedBatchBeforeScanningSessions() {
        RuntimeSessionRetentionStore store = mock(RuntimeSessionRetentionStore.class);
        RuntimeSessionRetentionService service = service(store, mock(RuntimeSessionStateEraser.class));

        assertThrows(IllegalArgumentException.class, () -> service.eraseOwner(
                "tenant-a", "user-a", "PRIVACY_REQUEST", null, 501, "42"));

        verify(store, never()).ownerEraseCandidates(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    private static RuntimeSessionRetentionService service(
            RuntimeSessionRetentionStore store,
            RuntimeSessionStateEraser eraser) {
        return new RuntimeSessionRetentionService(
                store, eraser, new RuntimeSessionRetentionProperties(true, 30, 24, 100, 300));
    }

    private static RuntimeConversationSessionEntity session(Long id, String status) {
        RuntimeConversationSessionEntity session = new RuntimeConversationSessionEntity();
        session.setId(id);
        session.setTenantId("tenant-a");
        session.setSessionId("session-" + id);
        session.setAgentId("agent-a");
        session.setStateUserKey("user-" + id);
        session.setStateSessionKey("state-" + id);
        session.setStatus(status);
        session.setLegalHold(false);
        return session;
    }
}
