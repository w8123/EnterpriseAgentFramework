package com.enterprise.ai.control.a2a.domain;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2aTaskLifecycleTest {

    @Test
    void exposesOnlyOfficialPersistableA2a10States() {
        assertFalse(A2aTaskState.TASK_STATE_UNSPECIFIED.persistable());
        assertTrue(A2aTaskState.TASK_STATE_COMPLETED.terminal());
        assertTrue(A2aTaskState.TASK_STATE_FAILED.terminal());
        assertTrue(A2aTaskState.TASK_STATE_CANCELED.terminal());
        assertTrue(A2aTaskState.TASK_STATE_REJECTED.terminal());
        assertTrue(A2aTaskState.TASK_STATE_INPUT_REQUIRED.interrupted());
        assertTrue(A2aTaskState.TASK_STATE_AUTH_REQUIRED.interrupted());
    }

    @Test
    void acceptsWorkingInterruptResumeAndTerminalTransitions() {
        assertDoesNotThrow(() -> A2aTaskLifecycle.requireTransition(
                A2aTaskState.TASK_STATE_SUBMITTED,
                A2aTaskState.TASK_STATE_COMPLETED));
        assertDoesNotThrow(() -> A2aTaskLifecycle.requireTransition(
                A2aTaskState.TASK_STATE_SUBMITTED,
                A2aTaskState.TASK_STATE_INPUT_REQUIRED));
        assertDoesNotThrow(() -> A2aTaskLifecycle.requireTransition(
                A2aTaskState.TASK_STATE_SUBMITTED,
                A2aTaskState.TASK_STATE_WORKING));
        assertDoesNotThrow(() -> A2aTaskLifecycle.requireTransition(
                A2aTaskState.TASK_STATE_WORKING,
                A2aTaskState.TASK_STATE_INPUT_REQUIRED));
        assertDoesNotThrow(() -> A2aTaskLifecycle.requireTransition(
                A2aTaskState.TASK_STATE_INPUT_REQUIRED,
                A2aTaskState.TASK_STATE_WORKING));
        assertDoesNotThrow(() -> A2aTaskLifecycle.requireTransition(
                A2aTaskState.TASK_STATE_WORKING,
                A2aTaskState.TASK_STATE_COMPLETED));
    }

    @Test
    void treatsSameStateAsIdempotentButRejectsTerminalEscape() {
        assertTrue(A2aTaskLifecycle.canTransition(
                A2aTaskState.TASK_STATE_WORKING,
                A2aTaskState.TASK_STATE_WORKING));

        A2aDomainException exception = assertThrows(A2aDomainException.class,
                () -> A2aTaskLifecycle.requireTransition(
                        A2aTaskState.TASK_STATE_COMPLETED,
                        A2aTaskState.TASK_STATE_WORKING));

        assertEquals("A2A_TASK_STATE_TRANSITION_INVALID", exception.code());
    }

    @Test
    void neverAllowsUnspecifiedStateToEnterPersistenceLifecycle() {
        A2aDomainException exception = assertThrows(A2aDomainException.class,
                () -> A2aTaskLifecycle.requireTransition(
                        A2aTaskState.TASK_STATE_UNSPECIFIED,
                        A2aTaskState.TASK_STATE_SUBMITTED));

        assertEquals("A2A_TASK_STATE_INVALID", exception.code());
    }

    @Test
    void returnsDefensiveAllowedTransitionSet() {
        Set<A2aTaskState> allowed = A2aTaskLifecycle.allowedNext(A2aTaskState.TASK_STATE_WORKING);

        assertTrue(allowed.contains(A2aTaskState.TASK_STATE_COMPLETED));
        assertThrows(UnsupportedOperationException.class,
                () -> allowed.add(A2aTaskState.TASK_STATE_SUBMITTED));
    }
}
