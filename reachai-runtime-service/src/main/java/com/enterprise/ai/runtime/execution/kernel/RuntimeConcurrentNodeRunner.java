package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionEventSink;
import com.enterprise.ai.runtime.execution.event.RuntimeExecutionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Bounded worker pool for PARALLEL branches, parallel LOOP iterations and timed node attempts.
 *
 * <p>Thread-confined authority (read-only trial draft scopes) is captured on the calling thread and installed on
 * the worker for the duration of the task, so concurrency never widens or drops the caller's limits. Workers never
 * submit nested work to this pool and the caller thread always does the waiting, so the pool cannot deadlock.</p>
 */
final class RuntimeConcurrentNodeRunner {

    /** Small server: keep the shared pool tight; excess tasks queue rather than spawn threads. */
    private static final int POOL_THREADS = 16;
    private static final String BRANCH_THREAD_PREFIX = "reachai-graph-branch-";
    private static final ExecutorService POOL = new ThreadPoolExecutor(
            POOL_THREADS, POOL_THREADS, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
            new NamedDaemonThreadFactory(BRANCH_THREAD_PREFIX));
    /**
     * Timed attempts get their own hand-off pool so a timed node inside a branch never waits on a queued task;
     * when it is exhausted the attempt runs inline without a timeout rather than queueing.
     */
    private static final ThreadPoolExecutor TIMEOUT_POOL = new ThreadPoolExecutor(
            0, 64, 60L, TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>(),
            new NamedDaemonThreadFactory("reachai-graph-timer-"));

    static {
        ((ThreadPoolExecutor) POOL).allowCoreThreadTimeOut(true);
    }

    private final List<ScopeCarrier> carriers;

    RuntimeConcurrentNodeRunner(List<ScopeCarrier> carriers) {
        this.carriers = carriers == null ? List.of() : carriers.stream().filter(Objects::nonNull).toList();
    }

    /** One source of thread-confined scope (capability gateway, HTTP API port). */
    interface ScopeCarrier {
        Object capture();

        void install(Object captured);

        void clear();
    }

    /** Runs every task with at most {@code concurrency} in flight; results keep task order. */
    <T> List<T> runAll(List<Callable<T>> tasks, int concurrency) {
        if (tasks.isEmpty()) return List.of();
        // Nested fan-out from a branch worker runs serially: the waiting worker must never queue behind itself.
        boolean nested = Thread.currentThread().getName().startsWith(BRANCH_THREAD_PREFIX);
        if (concurrency <= 1 || tasks.size() == 1 || nested) {
            List<T> results = new ArrayList<>(tasks.size());
            for (Callable<T> task : tasks) results.add(callUnchecked(task));
            return results;
        }
        List<Object> captured = captureAll();
        Semaphore permits = new Semaphore(concurrency);
        List<CompletableFuture<T>> futures = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) {
            try {
                permits.acquire();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("RUNTIME_GRAPH_CANCELLED", ex);
            }
            CompletableFuture<T> future = new CompletableFuture<>();
            POOL.execute(() -> {
                try {
                    future.complete(withScopes(captured, task));
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                } finally {
                    permits.release();
                }
            });
            futures.add(future);
        }
        List<T> results = new ArrayList<>(futures.size());
        for (CompletableFuture<T> future : futures) {
            try {
                results.add(future.get());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("RUNTIME_GRAPH_CANCELLED", ex);
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException(cause);
            }
        }
        return results;
    }

    /**
     * Runs one task on a worker and waits at most {@code timeoutMs}. On timeout the worker is interrupted and
     * {@code onTimeout} supplies the result; the abandoned worker keeps running against its own copies only.
     */
    <T> T runWithTimeout(Callable<T> task, long timeoutMs, Supplier<T> onTimeout) {
        List<Object> captured = captureAll();
        Future<T> future;
        try {
            future = TIMEOUT_POOL.submit(() -> withScopes(captured, task));
        } catch (java.util.concurrent.RejectedExecutionException saturated) {
            return callUnchecked(task);
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            return onTimeout.get();
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("RUNTIME_GRAPH_CANCELLED", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(cause);
        }
    }

    private List<Object> captureAll() {
        List<Object> captured = new ArrayList<>(carriers.size());
        for (ScopeCarrier carrier : carriers) captured.add(carrier.capture());
        return captured;
    }

    private <T> T withScopes(List<Object> captured, Callable<T> task) throws Exception {
        for (int index = 0; index < carriers.size(); index++) {
            Object scope = captured.get(index);
            if (scope != null) carriers.get(index).install(scope);
        }
        try {
            return task.call();
        } finally {
            for (ScopeCarrier carrier : carriers) carrier.clear();
        }
    }

    private static <T> T callUnchecked(Callable<T> task) {
        try {
            return task.call();
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Serialises sink callbacks from concurrent branches; drops events once closed (timed-out workers). */
    static final class GuardedEventSink implements RuntimeGraphSpecExecutionEventSink {
        private final RuntimeGraphSpecExecutionEventSink delegate;
        private final Map<String, Object> extraPayload;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final Object lock;

        GuardedEventSink(RuntimeGraphSpecExecutionEventSink delegate, Map<String, Object> extraPayload, Object lock) {
            this.delegate = delegate == null ? RuntimeGraphSpecExecutionEventSink.NOOP : delegate;
            this.extraPayload = extraPayload == null ? Map.of() : Map.copyOf(extraPayload);
            this.lock = lock == null ? this : lock;
        }

        void close() {
            closed.set(true);
        }

        private Map<String, Object> payload(Map<String, Object> safePayload) {
            if (extraPayload.isEmpty()) return safePayload;
            Map<String, Object> merged = new java.util.LinkedHashMap<>(safePayload == null ? Map.of() : safePayload);
            extraPayload.forEach(merged::putIfAbsent);
            return merged;
        }

        @Override
        public void onExecutionEvent(RuntimeExecutionEvent event) {
            if (closed.get()) return;
            synchronized (lock) { delegate.onExecutionEvent(event); }
        }

        @Override
        public void onNodeStarted(String nodeId, String nodeType, String nodeName, Map<String, Object> safePayload) {
            if (closed.get()) return;
            synchronized (lock) { delegate.onNodeStarted(nodeId, nodeType, nodeName, payload(safePayload)); }
        }

        @Override
        public void onNodeDelta(String nodeId, String nodeType, String text, Map<String, Object> safePayload) {
            if (closed.get()) return;
            synchronized (lock) { delegate.onNodeDelta(nodeId, nodeType, text, payload(safePayload)); }
        }

        @Override
        public void onNodeCompleted(String nodeId, String nodeType, String nodeName, Map<String, Object> safePayload) {
            if (closed.get()) return;
            synchronized (lock) { delegate.onNodeCompleted(nodeId, nodeType, nodeName, payload(safePayload)); }
        }

        @Override
        public void onNodeWaiting(String nodeId, String nodeType, String nodeName, Map<String, Object> safePayload) {
            if (closed.get()) return;
            synchronized (lock) { delegate.onNodeWaiting(nodeId, nodeType, nodeName, payload(safePayload)); }
        }

        @Override
        public void onNodeFailed(String nodeId, String nodeType, String nodeName, Map<String, Object> safePayload) {
            if (closed.get()) return;
            synchronized (lock) { delegate.onNodeFailed(nodeId, nodeType, nodeName, payload(safePayload)); }
        }

        @Override
        public void onExecutionCancelled(Map<String, Object> safePayload) {
            if (closed.get()) return;
            synchronized (lock) { delegate.onExecutionCancelled(payload(safePayload)); }
        }
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();
        private final String prefix;

        NamedDaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
