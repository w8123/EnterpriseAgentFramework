package com.enterprise.ai.runtime.managed;

import com.enterprise.ai.runtime.runops.RuntimeRunEntity;
import com.enterprise.ai.runtime.runops.RuntimeRunMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManagedExecutionRunProjectorTest {

    private final RuntimeRunMapper mapper = mock(RuntimeRunMapper.class);
    private final ManagedExecutionRunProjector projector =
            new ManagedExecutionRunProjector(mapper, new ObjectMapper());

    @Test
    void createsACodexHarnessRootRunWithoutPersistingTheObjective() {
        ManagedExecutionEntity execution = execution("QUEUED");
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any())).thenReturn(1);

        projector.sync(execution);

        ArgumentCaptor<RuntimeRunEntity> run = ArgumentCaptor.forClass(RuntimeRunEntity.class);
        verify(mapper).insert(run.capture());
        assertThat(run.getValue().getRunType()).isEqualTo("MANAGED_EXECUTION");
        assertThat(run.getValue().getRuntimeType()).isEqualTo("CODEX_HARNESS");
        assertThat(run.getValue().getStatus()).isEqualTo("RUNNING");
        assertThat(run.getValue().getSnapshotJson())
                .contains("WORKSPACE_PATCH", "objectiveSha256")
                .doesNotContain(execution.getObjectiveText());
        assertThat(run.getValue().getInputSummary()).doesNotContain(execution.getObjectiveText());
    }

    @Test
    void mapsApprovalToSuspendedAndVerifiedSuccessToCompleted() {
        ManagedExecutionEntity execution = execution("WAITING_APPROVAL");
        when(mapper.selectOne(any())).thenReturn(new RuntimeRunEntity());
        when(mapper.updateManagedExecutionProjection(
                any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any(), any())).thenReturn(1);

        projector.sync(execution);
        verify(mapper).updateManagedExecutionProjection(
                any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any(), any());

        execution.setStatus("SUCCEEDED");
        execution.setCompletedAt(LocalDateTime.now());
        projector.sync(execution);
        verify(mapper, org.mockito.Mockito.times(2)).updateManagedExecutionProjection(
                any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any(), any());
    }

    private ManagedExecutionEntity execution(String status) {
        ManagedExecutionEntity execution = new ManagedExecutionEntity();
        execution.setExecutionId("mex_runops_1");
        execution.setTenantId("tenant-a");
        execution.setProjectCode("PROJECT_A");
        execution.setRequestedByUserId("user-a");
        execution.setSourceType("AI_CODING_TASK");
        execution.setSourceRef("ait_1");
        execution.setExecutorProvider("CODEX");
        execution.setSandboxProfile("WORKSPACE_PATCH");
        execution.setAcceptanceProfile("PROJECT_DEFAULT");
        execution.setObjectiveText("secret objective body");
        execution.setObjectiveSha256("a".repeat(64));
        execution.setStatus(status);
        execution.setCleanupStatus("PENDING");
        execution.setApprovalCount(1);
        execution.setLastEventSequence(2);
        execution.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        execution.setStartedAt(LocalDateTime.now().minusSeconds(30));
        execution.setUpdatedAt(LocalDateTime.now());
        return execution;
    }
}
