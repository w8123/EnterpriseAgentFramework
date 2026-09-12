package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;
import java.util.function.Supplier;

/** One Workflow invocation owns its cancellation and can release its plan reservation only once. */
final class SupervisorWorkflowExecutionScope {
    private final RuntimeAgentExecutionCancellation requestCancellation;
    private final RuntimeGraphSpecExecutionCancellation cancellation = new RuntimeGraphSpecExecutionCancellation();
    private final AtomicBoolean reservationReleased = new AtomicBoolean();
    private final Runnable releaseReservation;

    SupervisorWorkflowExecutionScope(RuntimeAgentExecutionCancellation requestCancellation,
                                     Runnable releaseReservation) {
        this.requestCancellation = requestCancellation;
        this.releaseReservation = releaseReservation;
    }

    RuntimeGraphSpecExecutionCancellation cancellation() { return cancellation; }

    void throwIfCancelled() {
        requestCancellation.throwIfCancelled();
        if (cancellation.isCancelled()) throw new RuntimeAgentExecutionCancellation.CancellationSignal();
    }

    <T> Mono<T> execute(Lock lock, Duration timeout, Supplier<T> workflow,
                        Function<Throwable, Mono<T>> recover) {
        Mono<T> execution = Mono.<T>fromCallable(() -> {
                    try {
                        return workflow.get();
                    } catch (RuntimeAgentExecutionCancellation.CancellationSignal cancelled) {
                        // The local deadline has already delivered its error. Discard its late signal
                        // without logging an expected onErrorDropped or masking request cancellation.
                        if (cancellation.isCancelled() && !requestCancellation.isCancelled()) return null;
                        throw cancelled;
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                // Reactor interrupts its worker, but GraphSpec also needs its cooperative signal.
                .doOnCancel(cancellation::cancel)
                .timeout(timeout)
                .onErrorResume(recover)
                // Release after failure handling and before AgentScope can submit a revised plan.
                .doOnTerminate(this::releaseOnce);
        return Mono.fromCallable(() -> {
                    throwIfCancelled();
                    try {
                        lock.lockInterruptibly();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        if (cancellation.isCancelled() && !requestCancellation.isCancelled()) return null;
                        throw interrupted;
                    }
                    try {
                        throwIfCancelled();
                        return execution.block();
                    } finally {
                        lock.unlock();
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnCancel(cancellation::cancel)
                .doOnTerminate(this::releaseOnce)
                .doFinally(signal -> releaseOnce());
    }

    private void releaseOnce() {
        // A cancelled callable may still unwind after a newer attempt reserves the same tool.
        if (reservationReleased.compareAndSet(false, true)) releaseReservation.run();
    }
}
