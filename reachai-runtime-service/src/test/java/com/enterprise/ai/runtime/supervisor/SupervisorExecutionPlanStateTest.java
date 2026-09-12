package com.enterprise.ai.runtime.supervisor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SupervisorExecutionPlanStateTest {
    private final SupervisorExecutionPlanState state = new SupervisorExecutionPlanState(Set.of("a", "b", "c"), 1, 3, 3);

    @Test
    void approvalCheckpointKeepsCompletedPrefixRemainingPlanAndNonRetryableFailures() {
        assertNull(state.record(plan("a"), 0).error());
        state.fail("a");
        assertNull(state.record(plan("b", "c"), 1).error());
        assertNull(state.reserve("b"));
        state.complete("b");
        state.release("b");
        var input = state.approvalInput(Map.of("message", "original", "__supervisorContinuation", "forged"),
                Map.of("workflow", 2, "a2a", 0, "managed", 0));
        var restored = new SupervisorExecutionPlanState(Set.of("a", "b", "c"), 1, 3, 3);
        restored.restore(input);
        assertEquals(state.snapshot(), restored.snapshot());
        assertTrue(restored.isBlocked("a"));
        assertFalse(restored.isCompleted("a"));
        assertNull(restored.approvedStepError("c"));
        assertNotNull(restored.approvedStepError("b"));
        assertNotNull(restored.reserve("b"));
        assertNull(restored.reserve("c"));
        restored.complete("c");
        assertNull(restored.finalAnswerBlockReason());
        assertEquals(1, state.snapshot().cursor(), "Restoring and advancing cannot mutate the saved plan");
    }

    @Test
    void overlappingCandidateValidationCannotCommitTwoInitialPlans() throws Exception {
        var validating = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        List<String> slowSteps = new java.util.AbstractList<>() {
            @Override public String get(int index) { return "step"; }
            @Override public int size() {
                validating.countDown();
                try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                return 1;
            }
        };
        Map<String, Object> first = new LinkedHashMap<>(plan("a"));
        first.put("steps", slowSteps);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> state.record(first, 0));
            assertTrue(validating.await(2, TimeUnit.SECONDS));
            var competing = new CountDownLatch(1);
            var two = pool.submit(() -> { competing.countDown(); return state.record(plan("b"), 0); });
            assertTrue(competing.await(2, TimeUnit.SECONDS));
            try { two.get(200, TimeUnit.MILLISECONDS); }
            catch (java.util.concurrent.TimeoutException pendingAdmission) { /* It may wait for the first commit. */ }
            release.countDown();
            int accepted = (one.get(2, TimeUnit.SECONDS).error() == null ? 1 : 0)
                    + (two.get(2, TimeUnit.SECONDS).error() == null ? 1 : 0);
            assertEquals(1, accepted);
            assertEquals(1, state.planCount());
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void rejectingPlanLeavesCountsAndRetryStateUnchanged() {
        assertNull(state.record(plan("a"), 0).error());
        state.fail(null);
        var before = state.snapshot();
        Map<String, Object> invalid = new LinkedHashMap<>(plan("a"));
        invalid.put("reason", null);
        assertNotNull(state.record(invalid, 1).error());
        assertEquals(before, state.snapshot());
        assertTrue(state.failed());
        assertNotNull(state.record(plan("a", "a"), 1).error());
        assertNotNull(state.record(plan("unknown"), 1).error());
        assertNotNull(state.record(plan("a", "b", "c"), 1).error());
        assertEquals(before, state.snapshot());
        assertNull(state.record(plan("a"), 1).error());
        assertEquals(2, state.planCount());
        assertFalse(state.failed());
        state.fail(null);
        assertNotNull(state.record(plan("b"), 2).error());
        assertEquals(2, state.planCount());
    }

    @Test
    void reservationsFenceReplanningUntilEveryInFlightToolHasReleased() {
        assertNull(state.record(plan("a", "b"), 0).error());
        assertNull(state.reserve("a"));
        assertNull(state.reserve("b"));
        state.fail(null);
        state.release("a");
        assertNotNull(state.record(plan("c"), 2).error());
        assertEquals(1, state.planCount());
        state.release("b");
        assertNull(state.record(plan("c"), 2).error());
        assertEquals(List.of("c"), state.snapshot().plannedToolNames());
    }

    @Test
    void parallelCompletionKeepsCursorAtFirstUnfinishedStepAndCannotRepeatCompletedTool() {
        assertNull(state.record(plan("a", "b"), 0).error());
        assertNotNull(state.reserve("b"));
        assertNull(state.reserve("a"));
        assertNull(state.reserve("b"));
        state.complete("b");
        state.release("b");
        var intermediate = state.snapshot();
        assertEquals(0, intermediate.cursor());
        assertNotNull(state.reserve("b"));
        assertTrue(state.finalAnswerBlockReason().endsWith("a"));
        state.complete("a");
        state.release("a");
        assertEquals(2, state.snapshot().cursor());
        assertFalse(state.hasUnfinished());
        assertNull(state.finalAnswerBlockReason());
        assertEquals(List.of("b"), intermediate.completedToolNames());
        assertEquals(0, intermediate.cursor());
        assertThrows(IllegalStateException.class, () -> state.complete("b"));
    }

    @Test
    void nonRetryableFailureCannotBeReintroducedAndCanBeAbandoned() {
        assertNull(state.record(plan("a"), 0).error());
        assertNull(state.reserve("a"));
        state.fail("a");
        state.release("a");
        assertTrue(state.isBlocked("a"));
        assertFalse(state.isCompleted("a"));
        assertNotNull(state.record(plan("a"), 1).error());
        var abandon = state.record(plan(), 1);
        assertNull(abandon.error());
        assertTrue(abandon.abandonedFailedPath());
        assertFalse(state.hasUnfinished());
        assertNull(state.finalAnswerBlockReason());
    }

    @Test
    void retryableFailureRequiresNewPlanBeforeAnotherReservation() {
        assertNull(state.record(plan("a", "b"), 0).error());
        assertNull(state.reserve("a"));
        state.fail(null);
        state.release("a");
        assertNotNull(state.reserve("a"));
        assertNotNull(state.reserve("b"));
        assertNull(state.record(plan("a", "b"), 1).error());
        assertNull(state.reserve("a"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void planSnapshotsCannotBeMutatedByInputOwners() {
        List<String> steps = new ArrayList<>(List.of("first step"));
        Map<String, Object> input = new LinkedHashMap<>(plan("a"));
        input.put("steps", steps);
        var accepted = state.record(input, 0);
        assertNull(accepted.error());
        steps.clear();
        input.put("summary", "changed");
        assertEquals(List.of("first step"), accepted.plan().get("steps"));
        assertEquals("execute", accepted.plan().get("summary"));
        assertThrows(UnsupportedOperationException.class, () -> accepted.plan().put("summary", "changed"));
        assertThrows(UnsupportedOperationException.class, () -> ((List<String>) accepted.plan().get("steps")).clear());
        assertThrows(UnsupportedOperationException.class, () -> state.snapshot().plannedToolNames().clear());
    }

    @Test
    void restoredContinuationSkipsCompletedToolsAndRetainsPlanLimit() {
        List<String> steps = new ArrayList<>(List.of("resume"));
        Map<String, Object> recorded = new LinkedHashMap<>(plan("a", "b"));
        recorded.put("steps", steps);
        recorded.put("planNo", 2);
        state.restore(Map.of("__blockedWorkflowTools", List.of("c"), "__supervisorContinuation", Map.of(
                "planNo", 2, "recordedPlan", recorded, "plannedWorkflowToolNames", List.of("a", "b"),
                "plannedWorkflowCursor", 1, "completedWorkflowToolNames", List.of("a"))));
        steps.clear();
        assertEquals(List.of("resume"), state.snapshot().recordedPlan().get("steps"));
        assertTrue(state.isCompleted("a"));
        assertTrue(state.isBlocked("c"));
        assertNotNull(state.reserve("a"));
        assertNull(state.reserve("b"));
        state.fail(null);
        state.release("b");
        assertNotNull(state.record(plan("b"), 2).error());
        assertEquals(2, state.planCount());
        assertEquals(1, state.snapshot().cursor());
    }

    @Test
    void racingReservationAndReplacementCannotReserveFromTheFailedPlan() throws Exception {
        assertNull(state.record(plan("a"), 0).error());
        state.fail(null);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var reservation = pool.submit(() -> { start.await(); return state.reserve("a"); });
            var replacement = pool.submit(() -> { start.await(); return state.record(plan("b"), 1); });
            start.countDown();
            assertNotNull(reservation.get(2, TimeUnit.SECONDS));
            assertNull(replacement.get(2, TimeUnit.SECONDS).error());
            assertNull(state.reserve("b"));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private Map<String, Object> plan(String... names) {
        return Map.of("summary", "execute", "steps", List.of("execute or explain"), "workflowToolNames", List.of(names));
    }
}
