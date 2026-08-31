package com.enterprise.ai.control.aicoding.application;

import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEventMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskMapper;
import com.enterprise.ai.control.managed.ControlManagedExecutionInboxProjector.ProjectionEvent;
import com.enterprise.ai.control.managed.ControlManagedExecutionRuntimeClient.ExecutionView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiCodingManagedExecutionProjectionServiceTest {

    private final AiCodingTaskMapper taskMapper = mock(AiCodingTaskMapper.class);
    private final AiCodingTaskEventMapper eventMapper = mock(AiCodingTaskEventMapper.class);
    private final AiCodingManagedExecutionProjectionService service =
            new AiCodingManagedExecutionProjectionService(
                    taskMapper, eventMapper, new ObjectMapper());
    private AiCodingTaskEntity task;

    @BeforeEach
    void setUp() {
        task = managedTask("RUNNING", "mex_1");
        when(taskMapper.selectById("ait_1")).thenReturn(task);
    }

    @Test
    void succeededStopsAtResultSubmittedUntilDomainArtifactsAreApplied() {
        when(taskMapper.projectManagedExecution(
                eq("ait_1"), eq("mex_1"), eq("SUCCEEDED"), isNull(),
                eq("RESULT_SUBMITTED"), any(), eq(7L), any()))
                .thenReturn(1);

        var result = service.projectSnapshot(
                "ait_1", execution("SUCCEEDED", null, 9));

        assertThat(result.status()).isEqualTo("APPLIED");
        verify(taskMapper).projectManagedExecution(
                eq("ait_1"), eq("mex_1"), eq("SUCCEEDED"), isNull(),
                eq("RESULT_SUBMITTED"), any(), eq(7L), any());
        verify(eventMapper).insertManagedEvent(
                eq("ait_1"), any(), eq("MANAGED_EXECUTION_SNAPSHOT"),
                eq("RESULT_SUBMITTED"), any(), any(), any());
    }

    @Test
    void waitingApprovalProjectsOneInteractionAndUserWaitState() {
        when(taskMapper.projectManagedExecution(
                eq("ait_1"), eq("mex_1"), eq("WAITING_APPROVAL"), eq("ix_1"),
                eq("WAITING_USER"), any(), eq(7L), any()))
                .thenReturn(1);

        var result = service.projectSnapshot(
                "ait_1", execution("WAITING_APPROVAL", "ix_1", 4));

        assertThat(result.status()).isEqualTo("APPLIED");
        verify(taskMapper).projectManagedExecution(
                eq("ait_1"), eq("mex_1"), eq("WAITING_APPROVAL"), eq("ix_1"),
                eq("WAITING_USER"), any(), eq(7L), any());
    }

    @Test
    void terminalDomainTaskNeverRegressesToRuntimeState() {
        task.setExecutionStatus("COMPLETED");
        when(taskMapper.projectManagedExecution(
                eq("ait_1"), eq("mex_1"), eq("RUNNING"), isNull(),
                eq("COMPLETED"), any(), eq(7L), any()))
                .thenReturn(1);

        assertThat(service.projectSnapshot(
                "ait_1", execution("RUNNING", null, 3)).status())
                .isEqualTo("APPLIED");

        verify(taskMapper).projectManagedExecution(
                eq("ait_1"), eq("mex_1"), eq("RUNNING"), isNull(),
                eq("COMPLETED"), any(), eq(7L), any());
    }

    @Test
    void rejectsCrossProjectOrExecutionSubstitutionWithoutMutation() {
        ExecutionView substituted = execution("RUNNING", null, 3,
                "OTHER_PROJECT", "mex_attacker");

        var result = service.projectSnapshot("ait_1", substituted);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.error()).contains("identity does not match");
        verify(taskMapper, never()).projectManagedExecution(
                any(), any(), any(), any(), any(), any(), any(), any());
        verify(eventMapper, never()).insertManagedEvent(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void bindingRaceIsDurablyRetriedInsteadOfMisprojected() {
        task.setManagedExecutionId(null);

        var result = service.projectSnapshot(
                "ait_1", execution("RUNNING", null, 1));

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.error()).contains("binding is not visible");
    }

    @Test
    void projectsAValidatedInboxContractWithoutImportingManagedPersistence() {
        when(taskMapper.projectManagedExecution(
                eq("ait_1"), eq("mex_1"), eq("RUNNING"), isNull(),
                eq("RUNNING"), any(), eq(7L), any()))
                .thenReturn(1);
        ProjectionEvent event = new ProjectionEvent(
                "mout_1",
                "mex_1",
                "MANAGED_EXECUTION_STATUS_CHANGED",
                "default",
                "PROJECT_A",
                "AI_CODING_TASK",
                "ait_1",
                "RUNNING",
                "{\"schema\":\"reachai.managed-execution.outbox.v1\","
                        + "\"executionId\":\"mex_1\",\"status\":\"RUNNING\"}");

        var result = service.project(event);

        assertThat(result.status()).isEqualTo("APPLIED");
        verify(taskMapper).projectManagedExecution(
                eq("ait_1"), eq("mex_1"), eq("RUNNING"), isNull(),
                eq("RUNNING"), any(), eq(7L), any());
        verify(eventMapper).insertManagedEvent(
                eq("ait_1"), eq("mout_1"),
                eq("MANAGED_EXECUTION_STATUS_CHANGED"),
                eq("RUNNING"), any(), any(), any());
    }

    private AiCodingTaskEntity managedTask(String status, String executionId) {
        AiCodingTaskEntity entity = new AiCodingTaskEntity();
        entity.setTaskId("ait_1");
        entity.setProjectCode("PROJECT_A");
        entity.setExecutionMode("MANAGED_SANDBOX");
        entity.setManagedExecutionId(executionId);
        entity.setExecutionStatus(status);
        entity.setLockVersion(7L);
        return entity;
    }

    private ExecutionView execution(
            String status,
            String interactionId,
            int sequence) {
        return execution(status, interactionId, sequence, "PROJECT_A", "mex_1");
    }

    private ExecutionView execution(
            String status,
            String interactionId,
            int sequence,
            String projectCode,
            String executionId) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 24, 10, 0);
        return new ExecutionView(
                executionId,
                "default",
                projectCode,
                "42",
                "AI_CODING_TASK",
                "ait_1",
                "CODEX",
                "WORKSPACE_PATCH",
                null,
                "PROJECT_DEFAULT",
                "0".repeat(64),
                status,
                "PENDING",
                interactionId,
                interactionId == null ? null : "approval_1",
                interactionId == null ? 0 : 1,
                0,
                1_800,
                600,
                sequence,
                false,
                null,
                null,
                now,
                now,
                now,
                null,
                null);
    }
}
