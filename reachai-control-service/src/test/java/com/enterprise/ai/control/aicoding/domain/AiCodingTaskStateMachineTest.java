package com.enterprise.ai.control.aicoding.domain;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ExecutionStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiCodingTaskStateMachineTest {

    @Test
    void acceptsCanonicalExecutionLifecycle() {
        assertDoesNotThrow(() -> AiCodingTaskStateMachine.requireTransition(
                ExecutionStatus.READY,
                ExecutionStatus.RUNNING));
        assertDoesNotThrow(() -> AiCodingTaskStateMachine.requireTransition(
                ExecutionStatus.RUNNING,
                ExecutionStatus.WAITING_USER));
        assertDoesNotThrow(() -> AiCodingTaskStateMachine.requireTransition(
                ExecutionStatus.WAITING_USER,
                ExecutionStatus.RUNNING));
        assertDoesNotThrow(() -> AiCodingTaskStateMachine.requireTransition(
                ExecutionStatus.RUNNING,
                ExecutionStatus.RESULT_SUBMITTED));
        assertDoesNotThrow(() -> AiCodingTaskStateMachine.requireTransition(
                ExecutionStatus.RESULT_SUBMITTED,
                ExecutionStatus.RESULT_APPLIED));
        assertDoesNotThrow(() -> AiCodingTaskStateMachine.requireTransition(
                ExecutionStatus.RESULT_APPLIED,
                ExecutionStatus.ACCEPTANCE_READY));
        assertDoesNotThrow(() -> AiCodingTaskStateMachine.requireTransition(
                ExecutionStatus.RESULT_APPLIED,
                ExecutionStatus.FAILED));
        assertDoesNotThrow(() -> AiCodingTaskStateMachine.requireTransition(
                ExecutionStatus.ACCEPTANCE_READY,
                ExecutionStatus.COMPLETED));
    }

    @Test
    void rejectsSkippedAndTerminalTransitions() {
        assertThrows(IllegalStateException.class, () ->
                AiCodingTaskStateMachine.requireTransition(
                        ExecutionStatus.READY,
                        ExecutionStatus.COMPLETED));
        assertThrows(IllegalStateException.class, () ->
                AiCodingTaskStateMachine.requireTransition(
                        ExecutionStatus.COMPLETED,
                        ExecutionStatus.RUNNING));
        assertThrows(IllegalStateException.class, () ->
                AiCodingTaskStateMachine.requireTransition(
                        ExecutionStatus.FAILED,
                        ExecutionStatus.READY));
    }

    @Test
    void closesClientAccessWhenDeliveryWaitsForHumanAcceptance() {
        assertDoesNotThrow(() ->
                AiCodingTaskStateMachine.requireTransition(
                        ExecutionStatus.RESULT_APPLIED,
                        ExecutionStatus.ACCEPTANCE_READY));
        org.junit.jupiter.api.Assertions.assertFalse(
                ExecutionStatus.ACCEPTANCE_READY.acceptsClientAccess());
        org.junit.jupiter.api.Assertions.assertTrue(
                ExecutionStatus.RESULT_APPLIED.acceptsClientAccess());
    }
}
