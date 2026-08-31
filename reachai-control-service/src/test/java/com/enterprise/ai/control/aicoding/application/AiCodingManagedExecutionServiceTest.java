package com.enterprise.ai.control.aicoding.application;

import com.enterprise.ai.control.aicoding.application.AiCodingManagedExecutionService.ManagedExecutionStartCommand;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskMapper;
import com.enterprise.ai.control.managed.ControlManagedExecutionRuntimeClient;
import com.enterprise.ai.control.managed.ControlManagedExecutionRuntimeClient.CreateRequest;
import com.enterprise.ai.control.managed.ControlManagedExecutionRuntimeClient.CreatedView;
import com.enterprise.ai.control.managed.ControlManagedExecutionRuntimeClient.ExecutionView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiCodingManagedExecutionServiceTest {

    private final AiCodingTaskMapper taskMapper = mock(AiCodingTaskMapper.class);
    private final AiCodingTaskApplicationService taskService =
            mock(AiCodingTaskApplicationService.class);
    private final AiCodingManagedExecutionProjectionService projectionService =
            mock(AiCodingManagedExecutionProjectionService.class);
    private final ControlManagedExecutionRuntimeClient runtimeClient =
            mock(ControlManagedExecutionRuntimeClient.class);
    private AiCodingManagedExecutionService service;

    @BeforeEach
    void setUp() {
        service = new AiCodingManagedExecutionService(
                taskMapper, taskService, projectionService, runtimeClient);
    }

    @Test
    void explicitlyStartsRuntimeWithTaskBoundSourceAndOperatorPolicy() {
        AiCodingTaskEntity unbound = managedTask(null, "READY");
        AiCodingTaskEntity bound = managedTask("mex_1", "RUNNING");
        ExecutionView execution = execution("QUEUED");
        when(taskMapper.selectById("ait_1")).thenReturn(unbound, bound);
        when(runtimeClient.create(any(), eq("default"), eq("42")))
                .thenReturn(new CreatedView(execution));
        when(runtimeClient.get("mex_1", "default", "42")).thenReturn(execution);
        when(runtimeClient.artifacts("mex_1", "default", "42"))
                .thenReturn(List.of());

        var detail = service.start(
                "ait_1",
                new ManagedExecutionStartCommand(
                        "codex-model", "PROJECT_TESTS", 10, 900, 120),
                "42");

        assertThat(detail.execution().executionId()).isEqualTo("mex_1");
        ArgumentCaptor<CreateRequest> request = ArgumentCaptor.forClass(CreateRequest.class);
        verify(runtimeClient).create(request.capture(), eq("default"), eq("42"));
        assertThat(request.getValue().sourceType()).isEqualTo("AI_CODING_TASK");
        assertThat(request.getValue().sourceRef()).isEqualTo("ait_1");
        assertThat(request.getValue().projectCode()).isEqualTo("PROJECT_A");
        assertThat(request.getValue().sandboxProfile()).isEqualTo("WORKSPACE_PATCH");
        assertThat(request.getValue().acceptanceProfile()).isEqualTo("PROJECT_TESTS");
        assertThat(request.getValue().maxWallTimeSeconds()).isEqualTo(900);
        verify(projectionService).bindStarted("ait_1", execution, "42");
    }

    @Test
    void boundTaskReusesRuntimeExecutionInsteadOfCreatingAnotherOne() {
        AiCodingTaskEntity bound = managedTask("mex_1", "RUNNING");
        ExecutionView execution = execution("RUNNING");
        when(taskMapper.selectById("ait_1")).thenReturn(bound);
        when(runtimeClient.get("mex_1", "default", "42")).thenReturn(execution);
        when(runtimeClient.artifacts("mex_1", "default", "42"))
                .thenReturn(List.of());

        assertThat(service.start("ait_1", null, "42")
                .execution().executionId()).isEqualTo("mex_1");

        verify(runtimeClient, never()).create(any(), any(), any());
    }

    @Test
    void externalTaskCanNeverAcquireManagedExecutionAuthority() {
        AiCodingTaskEntity external = managedTask(null, "READY");
        external.setExecutionMode("EXTERNAL_CLIENT");
        when(taskMapper.selectById("ait_1")).thenReturn(external);

        assertThatThrownBy(() -> service.start("ait_1", null, "42"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not use MANAGED_SANDBOX");
        verify(runtimeClient, never()).create(any(), any(), any());
    }

    @Test
    void unstartedManagedCancellationNeverCallsRuntime() {
        AiCodingTaskEntity task = managedTask(null, "READY");
        when(taskMapper.selectById("ait_1")).thenReturn(task);

        service.cancel("ait_1", "42");

        verify(taskService).cancel("ait_1", "42");
        verify(runtimeClient, never()).cancel(any(), any(), any(), any());
    }

    private AiCodingTaskEntity managedTask(String executionId, String status) {
        AiCodingTaskEntity task = new AiCodingTaskEntity();
        task.setTaskId("ait_1");
        task.setProjectCode("PROJECT_A");
        task.setExecutorProvider("CODEX");
        task.setExecutionMode("MANAGED_SANDBOX");
        task.setSandboxProfile("WORKSPACE_PATCH");
        task.setManagedExecutionId(executionId);
        task.setExecutionStatus(status);
        task.setObjective("Implement the bounded task");
        return task;
    }

    private ExecutionView execution(String status) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 24, 10, 0);
        return new ExecutionView(
                "mex_1", "default", "PROJECT_A", "42",
                "AI_CODING_TASK", "ait_1", "CODEX", "WORKSPACE_PATCH",
                null, "PROJECT_DEFAULT", "0".repeat(64), status, "PENDING",
                null, null, 0, 0, 1_800, 600, 0, false,
                null, null, now, now, null, null, null);
    }
}
