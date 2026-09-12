package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionCancellation;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupervisorWorkflowExecutionScopeTest {
    @Test
    void expiredCallableCannotReleaseTheNewPlanReservation() throws Exception {
        SupervisorExecutionPlanState plan = new SupervisorExecutionPlanState(Set.of("query"), 1, 4, 3);
        Map<String, Object> candidate = Map.of("steps", List.of("query"), "workflowToolNames", List.of("query"));
        assertNull(plan.record(candidate, 0).error());
        assertNull(plan.reserve("query"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch returnLate = new CountDownLatch(1);
        CountDownLatch unwound = new CountDownLatch(1);
        AtomicInteger releases = new AtomicInteger();
        SupervisorWorkflowExecutionScope expired = new SupervisorWorkflowExecutionScope(
                new RuntimeAgentExecutionCancellation(), () -> {
                    releases.incrementAndGet();
                    plan.release("query");
                });
        try {
            String outcome = expired.execute(new ReentrantLock(), Duration.ofMillis(500), () -> {
                entered.countDown();
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (returnLate.getCount() != 0 && System.nanoTime() < deadline) {
                        try { returnLate.await(10, TimeUnit.MILLISECONDS); }
                        catch (InterruptedException ignored) { /* Deliberately non-cooperative dependency. */ }
                    }
                    expired.throwIfCancelled();
                    return "late";
                } finally {
                    unwound.countDown();
                }
            }, failure -> {
                assertInstanceOf(TimeoutException.class, failure);
                plan.fail(null);
                return Mono.just("timeout");
            }).block(Duration.ofSeconds(5));
            assertEquals("timeout", outcome);
            assertEquals(0, entered.getCount(), "the deadline must expire an executing invocation");
            assertNull(plan.record(candidate, 1).error(), "timeout releases before the next plan is admitted");
            assertNull(plan.reserve("query"));
            returnLate.countDown();
            assertTrue(unwound.await(5, TimeUnit.SECONDS));
            assertEquals(1, releases.get());
            assertTrue(plan.record(candidate, 2).error().contains("running"), "retry still owns the reservation");
            assertTrue(plan.reserve("query").contains("already running"));
            assertFalse(plan.failed(), "late completion must not fail the replacement plan");
        } finally {
            returnLate.countDown();
            assertTrue(unwound.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellationWhileWaitingForTheExecutionLockReleasesWithoutStartingWorkflow() throws Exception {
        ReentrantLock heldLock = new ReentrantLock();
        Lock observedLock = mock(Lock.class);
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        AtomicBoolean invoked = new AtomicBoolean();
        doAnswer(invocation -> {
            waiting.countDown();
            try { heldLock.lockInterruptibly(); }
            catch (InterruptedException cancelled) {
                interrupted.countDown();
                throw cancelled;
            }
            return null;
        }).when(observedLock).lockInterruptibly();
        RuntimeAgentExecutionCancellation request = new RuntimeAgentExecutionCancellation();
        SupervisorWorkflowExecutionScope scope = new SupervisorWorkflowExecutionScope(request, released::countDown);
        heldLock.lock();
        Disposable subscription = null;
        try {
            subscription = scope.execute(observedLock, Duration.ofSeconds(5), () -> {
                invoked.set(true);
                return "started";
            }, Mono::error).subscribe();
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            subscription.dispose();
            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "a cancelled waiter must leave the lock queue");
            assertTrue(released.await(5, TimeUnit.SECONDS));
            assertTrue(scope.cancellation().isCancelled());
            assertFalse(request.isCancelled());
            assertFalse(invoked.get());
            verify(observedLock, never()).unlock();
        } finally {
            if (subscription != null) subscription.dispose();
            heldLock.unlock();
        }
    }
}
