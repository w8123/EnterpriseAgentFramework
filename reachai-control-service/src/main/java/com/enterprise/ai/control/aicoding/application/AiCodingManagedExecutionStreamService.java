package com.enterprise.ai.control.aicoding.application;

import com.enterprise.ai.control.managed.ControlManagedExecutionRuntimeClient.ExecutionView;
import jakarta.annotation.PreDestroy;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Sanitized, bounded progress stream. It never forwards raw Worker or Codex output. */
@Service
public class AiCodingManagedExecutionStreamService {

    private static final long STREAM_TIMEOUT_MS = 10 * 60 * 1_000L;
    private static final long POLL_INTERVAL_MS = 2_000L;
    private static final long HEARTBEAT_INTERVAL_MS = 15_000L;
    private static final Set<String> TERMINAL = Set.of(
            "SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED");

    private final AiCodingManagedExecutionService managedExecutionService;
    private final ExecutorService streamExecutor;

    public AiCodingManagedExecutionStreamService(
            AiCodingManagedExecutionService managedExecutionService) {
        this.managedExecutionService = managedExecutionService;
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "reachai-managed-execution-sse-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.streamExecutor = new ThreadPoolExecutor(
                2,
                16,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(100),
                factory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    public SseEmitter stream(String taskId, String actorUserId) {
        // Fail synchronously before the response is committed when task/mode/auth is invalid.
        ExecutionView initial = managedExecutionService.progress(taskId, actorUserId);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> {
            closed.set(true);
            emitter.complete();
        });
        emitter.onError(ignored -> closed.set(true));
        try {
            streamExecutor.execute(() -> run(
                    emitter, closed, taskId, actorUserId, initial));
        } catch (RuntimeException saturated) {
            closed.set(true);
            emitter.complete();
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Managed Execution progress stream capacity is exhausted");
        }
        return emitter;
    }

    private void run(
            SseEmitter emitter,
            AtomicBoolean closed,
            String taskId,
            String actorUserId,
            ExecutionView initial) {
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(STREAM_TIMEOUT_MS);
        long lastHeartbeat = 0L;
        String lastFingerprint = null;
        ExecutionView current = initial;
        try {
            while (!closed.get() && System.nanoTime() < deadline) {
                String fingerprint = fingerprint(current);
                if (!fingerprint.equals(lastFingerprint)) {
                    send(emitter, "managed.snapshot", snapshot(taskId, current));
                    lastFingerprint = fingerprint;
                }
                if (TERMINAL.contains(current.status())) {
                    closed.set(true);
                    emitter.complete();
                    return;
                }
                long now = System.nanoTime();
                if (lastHeartbeat == 0L
                        || now - lastHeartbeat >= TimeUnit.MILLISECONDS.toNanos(
                                HEARTBEAT_INTERVAL_MS)) {
                    synchronized (emitter) {
                        emitter.send(SseEmitter.event().comment("heartbeat"));
                    }
                    lastHeartbeat = now;
                }
                Thread.sleep(POLL_INTERVAL_MS);
                if (!closed.get()) {
                    current = managedExecutionService.progress(taskId, actorUserId);
                }
            }
            if (closed.compareAndSet(false, true)) emitter.complete();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (closed.compareAndSet(false, true)) emitter.complete();
        } catch (Exception failure) {
            if (!closed.get()) {
                try {
                    send(emitter, "managed.error", new StreamError(
                            "reachai.ai-coding.managed-progress-error.v1",
                            "MANAGED_EXECUTION_STREAM_UNAVAILABLE",
                            "隔离执行进度流暂时不可用，请重连或手动刷新"));
                } catch (IOException ignored) {
                    // The browser may already be gone.
                }
            }
            if (closed.compareAndSet(false, true)) emitter.complete();
        }
    }

    private ProgressSnapshot snapshot(String taskId, ExecutionView execution) {
        return new ProgressSnapshot(
                "reachai.ai-coding.managed-progress.v1",
                taskId,
                execution.executionId(),
                execution.status(),
                execution.cleanupStatus(),
                execution.pendingInteractionId(),
                execution.lastEventSequence(),
                execution.approvalCount(),
                TERMINAL.contains(execution.status()),
                OffsetDateTime.now().toString());
    }

    private String fingerprint(ExecutionView execution) {
        return String.join("|",
                value(execution.status()),
                value(execution.cleanupStatus()),
                value(execution.pendingInteractionId()),
                Integer.toString(execution.lastEventSequence()),
                Integer.toString(execution.approvalCount()));
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private void send(SseEmitter emitter, String event, Object data) throws IOException {
        synchronized (emitter) {
            emitter.send(SseEmitter.event()
                    .name(event)
                    .data(data, MediaType.APPLICATION_JSON));
        }
    }

    @PreDestroy
    void shutdown() {
        streamExecutor.shutdownNow();
    }

    public record ProgressSnapshot(
            String schema,
            String taskId,
            String executionId,
            String status,
            String cleanupStatus,
            String pendingInteractionId,
            int lastEventSequence,
            int approvalCount,
            boolean terminal,
            String observedAt) {
    }

    private record StreamError(String schema, String code, String message) {
    }
}
