package com.enterprise.ai.runtime.managed;

import com.enterprise.ai.runtime.runops.RuntimeManagedRunProjectionWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManagedExecutionRunProjectorTest {

    private final ManagedExecutionMapper mapper = mock(ManagedExecutionMapper.class);
    private final RuntimeManagedRunProjectionWriter writer = mock(RuntimeManagedRunProjectionWriter.class);
    private final ManagedExecutionRunProjector projector =
            new ManagedExecutionRunProjector(mapper, writer, new ObjectMapper());

    @Test
    void projectsFrozenExecutionFactsWithoutPersistingTheObjective() {
        ManagedExecutionEntity execution = execution("QUEUED");
        when(mapper.selectForUpdate(execution.getExecutionId())).thenReturn(execution);

        projector.sync(execution.getExecutionId());

        ArgumentCaptor<RuntimeManagedRunProjectionWriter.Projection> run =
                ArgumentCaptor.forClass(RuntimeManagedRunProjectionWriter.Projection.class);
        verify(writer).sync(run.capture());
        assertThat(run.getValue().executionId()).isEqualTo(execution.getExecutionId());
        assertThat(run.getValue().sourceType()).isEqualTo("AI_CODING_TASK");
        assertThat(run.getValue().status()).isEqualTo("RUNNING");
        assertThat(run.getValue().snapshotJson())
                .contains("WORKSPACE_PATCH", "objectiveSha256")
                .doesNotContain(execution.getObjectiveText());
        assertThat(run.getValue().inputSummary()).doesNotContain(execution.getObjectiveText());
    }

    @Test
    void mapsApprovalToSuspendedAndVerifiedSuccessToCompleted() {
        ManagedExecutionEntity execution = execution("WAITING_APPROVAL");
        when(mapper.selectForUpdate(execution.getExecutionId())).thenReturn(execution);

        projector.sync(execution.getExecutionId());

        execution.setStatus("SUCCEEDED");
        execution.setCompletedAt(LocalDateTime.now());
        projector.sync(execution.getExecutionId());
        ArgumentCaptor<RuntimeManagedRunProjectionWriter.Projection> values =
                ArgumentCaptor.forClass(RuntimeManagedRunProjectionWriter.Projection.class);
        verify(writer, org.mockito.Mockito.times(2)).sync(values.capture());
        assertThat(values.getAllValues()).extracting(RuntimeManagedRunProjectionWriter.Projection::status)
                .containsExactly("SUSPENDED", "COMPLETED");
        assertThat(values.getAllValues().get(0).suspensionReason()).isEqualTo("APPROVAL");
        assertThat(values.getAllValues().get(0).endedAt()).isNull();
        assertThat(values.getAllValues().get(1).endedAt()).isEqualTo(execution.getCompletedAt());
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
