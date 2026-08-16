package com.enterprise.ai.runtime.memory;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Application service for tenant retention policies, legal hold, and physical Runtime-memory erase. */
@Service
public class RuntimeSessionRetentionService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeSessionRetentionService.class);
    private static final int MAX_OWNER_ERASE_BATCH_SIZE = 500;

    private final RuntimeSessionRetentionStore store;
    private final RuntimeSessionStateEraser stateEraser;
    private final RuntimeSessionRetentionProperties properties;

    public RuntimeSessionRetentionService(
            RuntimeSessionRetentionStore store,
            RuntimeSessionStateEraser stateEraser,
            RuntimeSessionRetentionProperties properties) {
        this.store = store;
        this.stateEraser = stateEraser;
        this.properties = properties;
    }

    public PolicyView policy(String tenantId) {
        return policyView(store.effectivePolicy(RuntimeSessionRetentionSupport.tenantId(tenantId)));
    }

    public PolicyView savePolicy(String tenantId,
                                 int activeRetentionDays,
                                 int clearedRetentionHours,
                                 String actorId) {
        validatePolicy(activeRetentionDays, clearedRetentionHours);
        return policyView(store.savePolicy(
                RuntimeSessionRetentionSupport.tenantId(tenantId),
                activeRetentionDays,
                clearedRetentionHours,
                RuntimeSessionRetentionSupport.actorHash(actorId),
                LocalDateTime.now()));
    }

    public PolicyView deletePolicy(String tenantId, String actorId) {
        return policyView(store.deletePolicy(
                RuntimeSessionRetentionSupport.tenantId(tenantId),
                RuntimeSessionRetentionSupport.actorHash(actorId),
                LocalDateTime.now()));
    }

    public SessionView session(String tenantId, String sessionId) {
        String tenant = RuntimeSessionRetentionSupport.tenantId(tenantId);
        String publicSessionId = RuntimeSessionRetentionSupport.sessionId(sessionId);
        RuntimeConversationSessionEntity session = store.findSession(tenant, publicSessionId);
        if (session == null) {
            throw RuntimeSessionRetentionException.notFound();
        }
        return sessionView(session, store.effectivePolicy(tenant));
    }

    public SessionView setLegalHold(String tenantId,
                                    String sessionId,
                                    boolean enabled,
                                    String reasonCode,
                                    String referenceId,
                                    String actorId) {
        String tenant = RuntimeSessionRetentionSupport.tenantId(tenantId);
        String publicSessionId = RuntimeSessionRetentionSupport.sessionId(sessionId);
        String reason = RuntimeSessionRetentionSupport.reasonCode(reasonCode);
        RuntimeConversationSessionEntity session = store.setLegalHold(
                tenant,
                publicSessionId,
                enabled,
                reason,
                RuntimeSessionRetentionSupport.referenceId(referenceId),
                RuntimeSessionRetentionSupport.actorHash(actorId),
                LocalDateTime.now());
        return sessionView(session, store.effectivePolicy(tenant));
    }

    public EraseResult erase(String tenantId,
                             String sessionId,
                             String reasonCode,
                             String referenceId,
                             String actorId) {
        String tenant = RuntimeSessionRetentionSupport.tenantId(tenantId);
        String publicSessionId = RuntimeSessionRetentionSupport.sessionId(sessionId);
        String reason = RuntimeSessionRetentionSupport.reasonCode(reasonCode);
        String reference = RuntimeSessionRetentionSupport.referenceId(referenceId);
        String actorHash = RuntimeSessionRetentionSupport.actorHash(actorId);
        String owner = lifecycleOwner();
        LocalDateTime now = LocalDateTime.now();
        RuntimeConversationSessionEntity session = store.claimManualPurge(
                tenant, publicSessionId, reason, reference, actorHash, owner, now);
        try {
            stateEraser.purgeAll(session);
            store.finalizePurge(session, owner, "PLATFORM_USER", actorHash, reference,
                    LocalDateTime.now());
            return new EraseResult(
                    tenant,
                    RuntimeSessionRetentionSupport.sessionHash(tenant, publicSessionId),
                    "ERASED",
                    reason,
                    LocalDateTime.now());
        } catch (RuntimeException failure) {
            recordFailureSafely(session, "MANUAL_ERASE_FAILED", "PLATFORM_USER", actorHash,
                    reference, failure);
            throw failure;
        }
    }

    /**
     * Physically erases a bounded batch of sessions owned by one trusted Runtime user. This is an
     * administration primitive for a durable cross-domain erasure workflow, not a claim that a
     * complete data-subject request has finished. Legal Hold and active lifecycle fences remain
     * authoritative, and the response contains only a pseudonymous owner identity and counts.
     */
    public OwnerEraseResult eraseOwner(String tenantId,
                                       String runtimeUserId,
                                       String reasonCode,
                                       String referenceId,
                                       Integer requestedBatchSize,
                                       String actorId) {
        String tenant = RuntimeSessionRetentionSupport.tenantId(tenantId);
        String user = RuntimeSessionRetentionSupport.userId(runtimeUserId);
        String reason = RuntimeSessionRetentionSupport.reasonCode(reasonCode);
        String reference = RuntimeSessionRetentionSupport.referenceId(referenceId);
        String actorHash = RuntimeSessionRetentionSupport.actorHash(actorId);
        int batchSize = ownerEraseBatchSize(requestedBatchSize);
        LocalDateTime attemptedAt = LocalDateTime.now();
        List<RuntimeConversationSessionEntity> candidates =
                store.ownerEraseCandidates(tenant, user, batchSize);
        int erased = 0;
        int alreadyErased = 0;
        int legalHoldBlocked = 0;
        int retryable = 0;
        int failed = 0;
        for (RuntimeConversationSessionEntity candidate : candidates) {
            if (Boolean.TRUE.equals(candidate.getLegalHold())) {
                legalHoldBlocked++;
                recordOwnerEraseNotCompletedSafely(candidate, actorHash, reason, reference,
                        "RUNTIME_SESSION_LEGAL_HOLD");
                continue;
            }
            try {
                erase(tenant, candidate.getSessionId(), reason, reference, actorId);
                erased++;
            } catch (RuntimeSessionRetentionException failure) {
                switch (failure.code()) {
                    case "RUNTIME_SESSION_NOT_FOUND" -> alreadyErased++;
                    case "RUNTIME_SESSION_LEGAL_HOLD" -> legalHoldBlocked++;
                    case "RUNTIME_SESSION_TURN_ACTIVE", "RUNTIME_SESSION_LIFECYCLE_BUSY" -> retryable++;
                    default -> failed++;
                }
                if (!"RUNTIME_SESSION_NOT_FOUND".equals(failure.code())) {
                    recordOwnerEraseNotCompletedSafely(candidate, actorHash, reason, reference,
                            failure.code());
                }
            } catch (RuntimeException failure) {
                failed++;
                recordOwnerEraseNotCompletedSafely(candidate, actorHash, reason, reference,
                        "RUNTIME_OWNER_ERASE_FAILED");
            }
        }
        long remaining = store.countOwnerSessions(tenant, user);
        boolean complete = remaining == 0;
        String status = complete
                ? "ERASED"
                : legalHoldBlocked > 0 && retryable == 0 && failed == 0
                        && remaining == legalHoldBlocked
                        ? "BLOCKED_LEGAL_HOLD"
                        : "RETRY_REQUIRED";
        return new OwnerEraseResult(
                tenant,
                RuntimeSessionRetentionSupport.userHash(tenant, user),
                status,
                batchSize,
                candidates.size(),
                erased,
                alreadyErased,
                legalHoldBlocked,
                retryable,
                failed,
                remaining,
                complete,
                reason,
                attemptedAt);
    }

    public CleanupResult cleanupDue() {
        if (!properties.enabled()) {
            return new CleanupResult(0, 0, 0, 0);
        }
        List<RuntimeConversationSessionEntity> candidates = store.dueCandidates(LocalDateTime.now());
        int purged = 0;
        int recoveredClears = 0;
        int failed = 0;
        int skipped = 0;
        for (RuntimeConversationSessionEntity candidate : candidates) {
            String owner = lifecycleOwner();
            LocalDateTime now = LocalDateTime.now();
            try {
                if (RuntimeSessionRetentionStore.CLEARING.equalsIgnoreCase(candidate.getStatus())) {
                    if (!store.claimClearRecovery(candidate, owner, now)) {
                        skipped++;
                        continue;
                    }
                    stateEraser.clearTransient(candidate);
                    store.completeClear(candidate, owner, "SYSTEM", null, LocalDateTime.now());
                    recoveredClears++;
                    continue;
                }
                if (!store.claimRetentionPurge(candidate, owner, now)) {
                    skipped++;
                    continue;
                }
                stateEraser.purgeAll(candidate);
                store.finalizePurge(candidate, owner, "SYSTEM", null, null, LocalDateTime.now());
                purged++;
            } catch (RuntimeException failure) {
                failed++;
                recordFailureSafely(candidate, "RETENTION_MAINTENANCE_FAILED", "SYSTEM", null,
                        null, failure);
            }
        }
        return new CleanupResult(candidates.size(), purged, recoveredClears, failed + skipped);
    }

    private void validatePolicy(int activeRetentionDays, int clearedRetentionHours) {
        if (activeRetentionDays < 1 || activeRetentionDays > 3_650) {
            throw new IllegalArgumentException("activeRetentionDays must be between 1 and 3650");
        }
        if (clearedRetentionHours < 1 || clearedRetentionHours > 87_600) {
            throw new IllegalArgumentException("clearedRetentionHours must be between 1 and 87600");
        }
    }

    private int ownerEraseBatchSize(Integer requestedBatchSize) {
        int defaultBatch = Math.max(1,
                Math.min(properties.cleanupBatchSize(), MAX_OWNER_ERASE_BATCH_SIZE));
        if (requestedBatchSize == null) {
            return defaultBatch;
        }
        if (requestedBatchSize < 1 || requestedBatchSize > MAX_OWNER_ERASE_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "batchSize must be between 1 and " + MAX_OWNER_ERASE_BATCH_SIZE);
        }
        return requestedBatchSize;
    }

    private static String lifecycleOwner() {
        return UUID.randomUUID().toString();
    }

    private void recordFailureSafely(RuntimeConversationSessionEntity session,
                                     String eventType,
                                     String actorType,
                                     String actorHash,
                                     String referenceId,
                                     RuntimeException failure) {
        try {
            store.recordFailure(session, eventType, actorType, actorHash,
                    referenceId, failure, LocalDateTime.now());
        } catch (RuntimeException auditFailure) {
            failure.addSuppressed(auditFailure);
            log.warn("Runtime session lifecycle failure audit could not be persisted: {}",
                    auditFailure.getClass().getSimpleName());
        }
    }

    private void recordOwnerEraseNotCompletedSafely(RuntimeConversationSessionEntity session,
                                                     String actorHash,
                                                     String reasonCode,
                                                     String referenceId,
                                                     String failureCode) {
        try {
            store.recordOwnerEraseNotCompleted(session, actorHash, reasonCode, referenceId,
                    failureCode, LocalDateTime.now());
        } catch (RuntimeException auditFailure) {
            log.warn("Runtime owner erase audit could not be persisted: failureType={}",
                    auditFailure.getClass().getSimpleName());
        }
    }

    private static PolicyView policyView(RuntimeSessionRetentionStore.EffectivePolicy policy) {
        return new PolicyView(
                policy.tenantId(),
                policy.activeRetentionDays(),
                policy.clearedRetentionHours(),
                policy.customized(),
                policy.updatedAt());
    }

    private static SessionView sessionView(
            RuntimeConversationSessionEntity session,
            RuntimeSessionRetentionStore.EffectivePolicy policy) {
        return new SessionView(
                session.getTenantId(),
                session.getSessionId(),
                session.getAgentId(),
                session.getStatus(),
                session.getEventCount() == null ? 0 : session.getEventCount(),
                Boolean.TRUE.equals(session.getLegalHold()),
                session.getLegalHoldReasonCode(),
                session.getLegalHoldReference(),
                session.getLegalHoldSetAt(),
                session.getLastTurnAt(),
                session.getClearedAt(),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                policyView(policy));
    }

    public record PolicyView(
            String tenantId,
            int activeRetentionDays,
            int clearedRetentionHours,
            boolean customized,
            LocalDateTime updatedAt) {
    }

    public record SessionView(
            String tenantId,
            String sessionId,
            String agentId,
            String status,
            int eventCount,
            boolean legalHold,
            String legalHoldReasonCode,
            String legalHoldReference,
            LocalDateTime legalHoldSetAt,
            LocalDateTime lastTurnAt,
            LocalDateTime clearedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            PolicyView retentionPolicy) {
    }

    public record EraseResult(
            String tenantId,
            String sessionIdHash,
            String status,
            String reasonCode,
            LocalDateTime erasedAt) {
    }

    public record OwnerEraseResult(
            String tenantId,
            String runtimeUserHash,
            String status,
            int batchSize,
            int selected,
            int erased,
            int alreadyErased,
            int legalHoldBlocked,
            int retryable,
            int failed,
            long remaining,
            boolean complete,
            String reasonCode,
            LocalDateTime attemptedAt) {
    }

    public record CleanupResult(int candidates, int purged, int recoveredClears, int notCompleted) {
    }
}
