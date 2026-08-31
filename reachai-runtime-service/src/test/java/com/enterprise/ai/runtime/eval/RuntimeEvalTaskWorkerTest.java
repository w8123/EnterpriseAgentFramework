package com.enterprise.ai.runtime.eval;

import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeEvalTaskWorkerTest {

    @Test
    void retriesAfterLeaseCollisionAndReturnsOnlyTheAtomicallyClaimedTask() {
        RuntimeEvalTaskMapper taskMapper = mock(RuntimeEvalTaskMapper.class);
        RuntimeEvalTaskEntity claimedTask = new RuntimeEvalTaskEntity();
        claimedTask.setId(2L);
        when(taskMapper.findLeaseCandidateId()).thenReturn(1L, 2L);
        when(taskMapper.claim(eq(1L), anyString(), anyString(), any(LocalDateTime.class))).thenReturn(0);
        when(taskMapper.claim(eq(2L), anyString(), anyString(), any(LocalDateTime.class)))
                .thenAnswer(invocation -> {
                    claimedTask.setLeaseOwner(invocation.getArgument(1));
                    claimedTask.setLeaseToken(invocation.getArgument(2));
                    claimedTask.setLeasedUntil(invocation.getArgument(3));
                    return 1;
                });
        when(taskMapper.selectById(2L)).thenReturn(claimedTask);

        RuntimeEvalTaskWorker worker = new RuntimeEvalTaskWorker(
                taskMapper,
                mock(RuntimeEvalExperimentMapper.class),
                mock(RuntimeEvalExperimentVariantMapper.class),
                mock(RuntimeEvalExperimentItemMapper.class),
                mock(RuntimeEvalEvaluatorSuiteVersionMapper.class),
                mock(RuntimeEvalDatasetService.class),
                mock(RuntimeEvalTargetSnapshotService.class),
                mock(RuntimeAgentExecutionService.class),
                mock(RuntimeEvalDeterministicJudge.class),
                mock(RuntimeEvalResultPersistenceService.class),
                mock(RuntimeEvalExperimentService.class),
                new RuntimeEvalJsonSupport(new ObjectMapper()));

        RuntimeEvalTaskWorker.ClaimedTask claimed = worker.claimNext();

        assertNotNull(claimed);
        assertEquals(2L, claimed.task().getId());
        assertEquals(claimedTask.getLeaseToken(), claimed.token());
        assertEquals(false, claimed.exhaustedLeaseRecovery());
        assertNotNull(claimedTask.getLeasedUntil());
        verify(taskMapper, times(2)).findLeaseCandidateId();
        verify(taskMapper).claim(eq(1L), anyString(), anyString(), any(LocalDateTime.class));
        verify(taskMapper).claim(eq(2L), anyString(), anyString(), any(LocalDateTime.class));
    }

    @Test
    void atomicallyTakesOverAnExpiredExhaustedLeaseForDeadLetterRecovery() {
        RuntimeEvalTaskMapper taskMapper = mock(RuntimeEvalTaskMapper.class);
        RuntimeEvalTaskEntity task = new RuntimeEvalTaskEntity();
        task.setId(7L);
        task.setAttemptCount(3);
        task.setMaxAttempts(3);
        when(taskMapper.findLeaseCandidateId()).thenReturn(7L);
        when(taskMapper.claim(eq(7L), anyString(), anyString(), any(LocalDateTime.class))).thenReturn(0);
        when(taskMapper.claimExpiredExhausted(eq(7L), anyString(), anyString(), any(LocalDateTime.class)))
                .thenAnswer(invocation -> {
                    task.setLeaseOwner(invocation.getArgument(1));
                    task.setLeaseToken(invocation.getArgument(2));
                    task.setLeasedUntil(invocation.getArgument(3));
                    return 1;
                });
        when(taskMapper.selectById(7L)).thenReturn(task);
        RuntimeEvalTaskWorker worker = new RuntimeEvalTaskWorker(
                taskMapper,
                mock(RuntimeEvalExperimentMapper.class),
                mock(RuntimeEvalExperimentVariantMapper.class),
                mock(RuntimeEvalExperimentItemMapper.class),
                mock(RuntimeEvalEvaluatorSuiteVersionMapper.class),
                mock(RuntimeEvalDatasetService.class),
                mock(RuntimeEvalTargetSnapshotService.class),
                mock(RuntimeAgentExecutionService.class),
                mock(RuntimeEvalDeterministicJudge.class),
                mock(RuntimeEvalResultPersistenceService.class),
                mock(RuntimeEvalExperimentService.class),
                new RuntimeEvalJsonSupport(new ObjectMapper()));

        RuntimeEvalTaskWorker.ClaimedTask claimed = worker.claimNext();

        assertNotNull(claimed);
        assertEquals(7L, claimed.task().getId());
        assertEquals(true, claimed.exhaustedLeaseRecovery());
        assertEquals(3, claimed.task().getAttemptCount());
        verify(taskMapper).claimExpiredExhausted(
                eq(7L), anyString(), anyString(), any(LocalDateTime.class));
    }

    @Test
    void closesAnExpiredFinalAttemptAsDeadWithoutExecutingItAgain() {
        RuntimeEvalTaskMapper taskMapper = mock(RuntimeEvalTaskMapper.class);
        RuntimeEvalExperimentMapper experimentMapper = mock(RuntimeEvalExperimentMapper.class);
        RuntimeEvalExperimentVariantMapper variantMapper = mock(RuntimeEvalExperimentVariantMapper.class);
        RuntimeEvalExperimentItemMapper itemMapper = mock(RuntimeEvalExperimentItemMapper.class);
        RuntimeAgentExecutionService executionService = mock(RuntimeAgentExecutionService.class);
        RuntimeEvalResultPersistenceService persistence = mock(RuntimeEvalResultPersistenceService.class);
        RuntimeEvalExperimentService experimentService = mock(RuntimeEvalExperimentService.class);
        RuntimeEvalTaskWorker worker = new RuntimeEvalTaskWorker(
                taskMapper,
                experimentMapper,
                variantMapper,
                itemMapper,
                mock(RuntimeEvalEvaluatorSuiteVersionMapper.class),
                mock(RuntimeEvalDatasetService.class),
                mock(RuntimeEvalTargetSnapshotService.class),
                executionService,
                mock(RuntimeEvalDeterministicJudge.class),
                persistence,
                experimentService,
                new RuntimeEvalJsonSupport(new ObjectMapper()));

        RuntimeEvalTaskEntity task = new RuntimeEvalTaskEntity();
        task.setId(5L);
        task.setTaskType("EXECUTE_ITEM");
        task.setExperimentId(10L);
        task.setExperimentItemId(20L);
        task.setAttemptCount(3);
        task.setMaxAttempts(3);
        RuntimeEvalExperimentEntity experiment = new RuntimeEvalExperimentEntity();
        experiment.setId(10L);
        experiment.setStatus("RUNNING");
        RuntimeEvalExperimentItemEntity item = new RuntimeEvalExperimentItemEntity();
        item.setId(20L);
        item.setExperimentId(10L);
        item.setVariantId(30L);
        item.setStatus("RUNNING");
        RuntimeEvalExperimentVariantEntity variant = new RuntimeEvalExperimentVariantEntity();
        variant.setId(30L);
        variant.setExperimentId(10L);
        when(experimentMapper.selectById(10L)).thenReturn(experiment);
        when(itemMapper.selectById(20L)).thenReturn(item);
        when(variantMapper.selectById(30L)).thenReturn(variant);
        when(taskMapper.renew(eq(5L), eq("lease-token"), any(LocalDateTime.class))).thenReturn(1);

        worker.process(new RuntimeEvalTaskWorker.ClaimedTask(task, "lease-token", true));

        verify(persistence).releaseFailure(
                eq(task),
                eq("lease-token"),
                eq(item),
                eq("EVAL_TASK_LEASE_EXPIRED"),
                eq("Eval task lease expired after the final allowed attempt"),
                any(LocalDateTime.class));
        verify(experimentService).refreshAndFinalize(10L);
        verify(executionService, never()).executeEvaluation(any(), any(), anyBoolean(), any());
    }
}
