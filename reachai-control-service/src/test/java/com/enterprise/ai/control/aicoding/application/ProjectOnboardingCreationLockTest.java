package com.enterprise.ai.control.aicoding.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectOnboardingCreationLockTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ProjectOnboardingCreationLock lock = new ProjectOnboardingCreationLock(jdbc);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void reentrantAdmissionReleasesOnlyOnceAfterTransactionCompletion() {
        TransactionSynchronizationManager.initSynchronization();
        when(jdbc.queryForObject(eq("SELECT GET_LOCK(?, 0)"), eq(Integer.class), any(Object.class))).thenReturn(1);
        lock.acquire("My-Project");
        lock.acquire("my-project");
        verify(jdbc, times(1)).queryForObject(eq("SELECT GET_LOCK(?, 0)"), eq(Integer.class), any(Object.class));
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        verify(jdbc, times(1)).queryForObject(eq("SELECT RELEASE_LOCK(?)"), eq(Integer.class), any(Object.class));
    }

    @Test
    void competingAdmissionReturnsConflict() {
        TransactionSynchronizationManager.initSynchronization();
        when(jdbc.queryForObject(eq("SELECT GET_LOCK(?, 0)"), eq(Integer.class), any(Object.class))).thenReturn(0);
        ResponseStatusException failure = assertThrows(ResponseStatusException.class, () -> lock.acquire("project"));
        assertEquals(409, failure.getStatusCode().value());
    }
}
