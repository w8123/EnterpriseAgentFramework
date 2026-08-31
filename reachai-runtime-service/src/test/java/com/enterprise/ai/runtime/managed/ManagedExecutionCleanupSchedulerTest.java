package com.enterprise.ai.runtime.managed;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManagedExecutionCleanupSchedulerTest {

    private final ManagedExecutionService executionService = mock(ManagedExecutionService.class);
    private final ManagedSandboxProvisioner provisioner = mock(ManagedSandboxProvisioner.class);
    private final ManagedExecutionCleanupScheduler scheduler =
            new ManagedExecutionCleanupScheduler(executionService, provisioner);

    @Test
    void doesNothingWhenTheSandboxBackendIsUnavailable() {
        when(provisioner.available()).thenReturn(false);

        scheduler.cleanup();

        verify(executionService, never()).reserveCleanup();
    }

    @Test
    void completesTerminalRowsThatNeverDispatchedASandbox() throws Exception {
        ManagedExecutionService.CleanupReservation reservation =
                new ManagedExecutionService.CleanupReservation(11L, "mex_cleanup_1", null, 1);
        when(provisioner.available()).thenReturn(true);
        when(executionService.reserveCleanup()).thenReturn(reservation).thenReturn(null);

        scheduler.cleanup();

        verify(provisioner, never()).cleanup(org.mockito.ArgumentMatchers.any());
        verify(executionService).completeCleanup(reservation);
    }

    @Test
    void deletesTheExactSandboxHandleBeforeCompletingTheCleanupLease() throws Exception {
        ManagedExecutionService.CleanupReservation reservation =
                new ManagedExecutionService.CleanupReservation(
                        12L, "mex_cleanup_2", "kubernetes:reachai-managed/mex-cleanup-2", 1);
        when(provisioner.available()).thenReturn(true);
        when(executionService.reserveCleanup()).thenReturn(reservation).thenReturn(null);

        scheduler.cleanup();

        verify(provisioner).cleanup(new ManagedSandboxProvisioner.CleanupRequest(
                reservation.executionId(), reservation.sandboxRef()));
        verify(executionService).completeCleanup(reservation);
        verify(executionService, never()).failCleanup(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void releasesCleanupForRetryWithoutReflectingBackendErrors() throws Exception {
        ManagedExecutionService.CleanupReservation reservation =
                new ManagedExecutionService.CleanupReservation(
                        13L, "mex_cleanup_3", "kubernetes:reachai-managed/mex-cleanup-3", 2);
        ManagedSandboxProvisioner.CleanupRequest request =
                new ManagedSandboxProvisioner.CleanupRequest(
                        reservation.executionId(), reservation.sandboxRef());
        when(provisioner.available()).thenReturn(true);
        when(executionService.reserveCleanup()).thenReturn(reservation).thenReturn(null);
        doThrow(new IllegalStateException("Bearer secret-should-not-leak"))
                .when(provisioner).cleanup(request);

        scheduler.cleanup();

        verify(executionService, never()).completeCleanup(reservation);
        verify(executionService).failCleanup(reservation, "Managed Sandbox cleanup failed");
    }
}
