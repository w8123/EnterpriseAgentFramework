package com.enterprise.ai.runtime.supervisor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SupervisorExecutionPhasesTest {
    @Test
    @SuppressWarnings("unchecked")
    void eventDecorationAndReturnedSnapshotsCannotChangeOwnedState() {
        SupervisorExecutionPhases phases = new SupervisorExecutionPhases((event, data) -> {
            Map<String, Object> payload = (Map<String, Object>) data;
            payload.put("state", "transport-decoration");
            payload.put("sequence", 99);
        });
        phases.emit("workflow-1", "workflow", "started", "workflow_runtime", "Workflow", "Starting");
        List<Map<String, Object>> before = phases.snapshot();
        assertEquals("started", before.get(0).get("state"));
        assertEquals(1, before.get(0).get("sequence"));
        before.get(0).put("state", "snapshot-decoration");
        assertEquals("started", phases.snapshot().get(0).get("state"));
        phases.complete("workflow-1", "workflow", "workflow_runtime", "Workflow", "Done");
        assertEquals("snapshot-decoration", before.get(0).get("state"));
        assertEquals("completed", phases.snapshot().get(0).get("state"));
        assertEquals(1, phases.snapshot().get(0).get("sequence"));
    }

    @Test
    void reentrantUpdatesAreDeliveredAfterTheCurrentEventWithoutRecursiveCallbacks() {
        AtomicReference<SupervisorExecutionPhases> owner = new AtomicReference<>();
        AtomicInteger depth = new AtomicInteger();
        AtomicInteger maxDepth = new AtomicInteger();
        List<Object> states = new ArrayList<>();
        SupervisorExecutionPhases phases = new SupervisorExecutionPhases((event, data) -> {
            maxDepth.accumulateAndGet(depth.incrementAndGet(), Math::max);
            Object state = ((Map<?, ?>) data).get("state");
            states.add(state);
            if ("started".equals(state)) owner.get().complete("a", "workflow", "runtime", "A", "Done");
            depth.decrementAndGet();
        });
        owner.set(phases);
        phases.emit("a", "workflow", "started", "runtime", "A", null);
        assertEquals(List.of("started", "completed"), states);
        assertEquals(1, maxDepth.get());
        assertEquals("completed", phases.snapshot().get(0).get("state"));
    }

    @Test
    void completionCreatesOnlyTheExistingFinalAnswerFallback() {
        List<Object> events = new ArrayList<>();
        SupervisorExecutionPhases phases = new SupervisorExecutionPhases((event, data) -> events.add(data));
        phases.complete("unknown", "workflow", "runtime", "Unknown", null);
        assertTrue(events.isEmpty());
        assertTrue(phases.snapshot().isEmpty());
        phases.complete("final-answer", "final_answer", "runtime_fallback", "Answer", "Done");
        assertEquals(1, events.size());
        assertEquals("completed", phases.snapshot().get(0).get("state"));
        assertEquals(1, phases.snapshot().get(0).get("sequence"));
    }

    @Test
    void terminationPreservesCompletedPhasesAndStableSequenceAndIsIdempotent() {
        List<Object> events = new ArrayList<>();
        SupervisorExecutionPhases phases = new SupervisorExecutionPhases((event, data) -> events.add(data));
        for (String state : List.of("completed", "started", "waiting", "active", "failed")) {
            phases.emit(state, "workflow", state, "runtime", state, null);
        }
        phases.terminateActive("cancelled", "Stopped");
        phases.terminateActive("cancelled", "Stopped again");
        assertEquals(List.of("completed", "cancelled", "cancelled", "cancelled", "failed"),
                phases.snapshot().stream().map(phase -> phase.get("state")).toList());
        assertEquals(List.of(1, 2, 3, 4, 5), phases.snapshot().stream().map(phase -> phase.get("sequence")).toList());
        assertEquals(8, events.size());
    }

    @Test
    void queuedEventsAreAttemptedAfterSinkFailureAndTheOriginalFailurePropagates() {
        AtomicReference<SupervisorExecutionPhases> owner = new AtomicReference<>();
        IllegalStateException failure = new IllegalStateException("sink failure");
        List<String> attempted = new ArrayList<>();
        SupervisorExecutionPhases phases = new SupervisorExecutionPhases((event, data) -> {
            Map<?, ?> phase = (Map<?, ?>) data;
            attempted.add(phase.get("stepId") + ":" + phase.get("state"));
            if ("a".equals(phase.get("stepId")) && "started".equals(phase.get("state"))) {
                owner.get().complete("a", "workflow", "runtime", "A", "Done");
                throw failure;
            }
        });
        owner.set(phases);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> phases.emit("a", "workflow", "started", "runtime", "A", null)));
        phases.emit("b", "workflow", "started", "runtime", "B", null);
        assertEquals(List.of("a:started", "a:completed", "b:started"), attempted);
        assertEquals(List.of(1, 2), phases.snapshot().stream().map(phase -> phase.get("sequence")).toList());
    }

    @Test
    void aSlowConsumerDoesNotHoldTheStateLockDuringCancellation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Object> delivered = Collections.synchronizedList(new ArrayList<>());
        SupervisorExecutionPhases phases = new SupervisorExecutionPhases((event, data) -> {
            Object state = ((Map<?, ?>) data).get("state");
            if ("started".equals(state)) {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { throw new IllegalStateException(failure); }
            }
            delivered.add(state);
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var emitting = executor.submit(() -> phases.emit("a", "workflow", "started", "runtime", "A", null));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            executor.submit(() -> phases.terminateActive("cancelled", "Stopped")).get(2, TimeUnit.SECONDS);
            assertEquals("cancelled", phases.snapshot().get(0).get("state"));
            assertTrue(delivered.isEmpty(), "cancellation updates state before the slow consumer returns");
            release.countDown();
            emitting.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("started", "cancelled"), delivered);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
