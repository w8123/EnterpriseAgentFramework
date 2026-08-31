package com.enterprise.ai.runtime.a2a;

import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionCancellation;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Runtime-local live execution registry for A2A cancellation.
 *
 * <p>A cancel arriving just before its dispatch creates a short-lived tombstone. The later
 * dispatch consumes that tombstone and returns a confirmed cancelled result without starting
 * the Agent. This closes the cancel-before-register race without pretending that a completed
 * or unreachable execution was cancelled.</p>
 */
@Component
public class RuntimeA2aExecutionRegistry {

    private static final long PENDING_CANCEL_TTL_MS = 10 * 60_000L;

    private final Map<String, Entry> entries = new HashMap<>();

    public StartResult start(String executionId, String tenantId, String principalKey) {
        synchronized (entries) {
            removeExpired(System.currentTimeMillis());
            Entry existing = entries.get(executionId);
            if (existing == null) {
                RuntimeAgentExecutionCancellation cancellation = new RuntimeAgentExecutionCancellation();
                entries.put(executionId, new Entry(
                        tenantId, principalKey, cancellation, true, Long.MAX_VALUE));
                return new StartResult(cancellation, false);
            }
            requireOwner(existing, tenantId, principalKey);
            if (existing.active()) {
                throw new RegistryException("A2A_RUNTIME_EXECUTION_ACTIVE",
                        "the A2A execution is already active");
            }
            entries.remove(executionId);
            return new StartResult(existing.cancellation(), true);
        }
    }

    public CancelResult cancel(String executionId, String tenantId, String principalKey) {
        RuntimeAgentExecutionCancellation toCancel;
        boolean active;
        synchronized (entries) {
            removeExpired(System.currentTimeMillis());
            Entry existing = entries.get(executionId);
            if (existing == null) {
                toCancel = new RuntimeAgentExecutionCancellation();
                entries.put(executionId, new Entry(
                        tenantId, principalKey, toCancel, false,
                        System.currentTimeMillis() + PENDING_CANCEL_TTL_MS));
                active = false;
            } else {
                requireOwner(existing, tenantId, principalKey);
                toCancel = existing.cancellation();
                active = existing.active();
            }
        }
        toCancel.cancel();
        return new CancelResult(true, active);
    }

    public void finish(String executionId, RuntimeAgentExecutionCancellation cancellation) {
        synchronized (entries) {
            Entry existing = entries.get(executionId);
            if (existing != null && existing.active() && existing.cancellation() == cancellation) {
                entries.remove(executionId);
            }
        }
    }

    @Scheduled(fixedDelayString = "${reachai.runtime.a2a.cancel-tombstone-cleanup-ms:60000}")
    void cleanup() {
        synchronized (entries) {
            removeExpired(System.currentTimeMillis());
        }
    }

    int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    private void removeExpired(long now) {
        entries.entrySet().removeIf(value -> !value.getValue().active()
                && value.getValue().expiresAtEpochMs() <= now);
    }

    private void requireOwner(Entry entry, String tenantId, String principalKey) {
        if (!entry.tenantId().equals(normalizeTenant(tenantId))
                || !entry.principalKey().equals(required(principalKey, "principalKey"))) {
            throw new RegistryException("A2A_RUNTIME_EXECUTION_FORBIDDEN",
                    "the A2A execution belongs to a different Principal");
        }
    }

    private static String normalizeTenant(String value) {
        return value == null || value.isBlank() ? "default" : value.trim();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new RegistryException("A2A_RUNTIME_IDENTITY_REQUIRED", field + " is required");
        }
        return value.trim();
    }

    private record Entry(
            String tenantId,
            String principalKey,
            RuntimeAgentExecutionCancellation cancellation,
            boolean active,
            long expiresAtEpochMs) {
        private Entry {
            tenantId = normalizeTenant(tenantId);
            principalKey = required(principalKey, "principalKey");
        }
    }

    public record StartResult(RuntimeAgentExecutionCancellation cancellation, boolean preCancelled) {
    }

    public record CancelResult(boolean accepted, boolean active) {
    }

    public static final class RegistryException extends RuntimeException {
        private final String code;

        public RegistryException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
