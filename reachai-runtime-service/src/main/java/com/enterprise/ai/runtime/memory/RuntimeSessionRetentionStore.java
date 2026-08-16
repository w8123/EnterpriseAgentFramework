package com.enterprise.ai.runtime.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** Transactional Runtime-owned persistence boundary for session lifecycle state. */
@Service
public class RuntimeSessionRetentionStore {

    static final String ACTIVE = "ACTIVE";
    static final String CLEARING = "CLEARING";
    static final String CLEARED = "CLEARED";
    static final String EXPIRED = "EXPIRED";
    static final String PURGING = "PURGING";

    private final RuntimeConversationSessionMapper sessionMapper;
    private final RuntimeConversationEventMapper eventMapper;
    private final RuntimeSessionRetentionPolicyMapper policyMapper;
    private final RuntimeSessionRetentionAuditMapper auditMapper;
    private final RuntimeSessionRetentionProperties properties;

    public RuntimeSessionRetentionStore(
            RuntimeConversationSessionMapper sessionMapper,
            RuntimeConversationEventMapper eventMapper,
            RuntimeSessionRetentionPolicyMapper policyMapper,
            RuntimeSessionRetentionAuditMapper auditMapper,
            RuntimeSessionRetentionProperties properties) {
        this.sessionMapper = sessionMapper;
        this.eventMapper = eventMapper;
        this.policyMapper = policyMapper;
        this.auditMapper = auditMapper;
        this.properties = properties;
    }

    public List<RuntimeConversationSessionEntity> dueCandidates(LocalDateTime now) {
        return sessionMapper.selectRetentionCandidates(
                now,
                properties.defaultActiveRetentionDays(),
                properties.defaultClearedRetentionHours(),
                properties.cleanupBatchSize());
    }

    public RuntimeConversationSessionEntity findSession(String tenantId, String sessionId) {
        return sessionMapper.selectOne(new LambdaQueryWrapper<RuntimeConversationSessionEntity>()
                .eq(RuntimeConversationSessionEntity::getTenantId, tenantId)
                .eq(RuntimeConversationSessionEntity::getSessionId, sessionId)
                .last("LIMIT 1"));
    }

    public List<RuntimeConversationSessionEntity> ownerEraseCandidates(
            String tenantId, String runtimeUserId, int limit) {
        return sessionMapper.selectOwnerEraseCandidates(tenantId, runtimeUserId, limit);
    }

    public long countOwnerSessions(String tenantId, String runtimeUserId) {
        return sessionMapper.countOwnerSessions(tenantId, runtimeUserId);
    }

    public EffectivePolicy effectivePolicy(String tenantId) {
        RuntimeSessionRetentionPolicyEntity custom = policyMapper.selectOne(
                new LambdaQueryWrapper<RuntimeSessionRetentionPolicyEntity>()
                        .eq(RuntimeSessionRetentionPolicyEntity::getTenantId, tenantId)
                        .last("LIMIT 1"));
        if (custom == null) {
            return new EffectivePolicy(
                    tenantId,
                    properties.defaultActiveRetentionDays(),
                    properties.defaultClearedRetentionHours(),
                    false,
                    null);
        }
        return new EffectivePolicy(
                tenantId,
                custom.getActiveRetentionDays(),
                custom.getClearedRetentionHours(),
                true,
                custom.getUpdatedAt());
    }

    @Transactional
    public EffectivePolicy savePolicy(String tenantId,
                                      int activeRetentionDays,
                                      int clearedRetentionHours,
                                      String actorHash,
                                      LocalDateTime now) {
        RuntimeSessionRetentionPolicyEntity existing = policyMapper.selectOne(
                new LambdaQueryWrapper<RuntimeSessionRetentionPolicyEntity>()
                        .eq(RuntimeSessionRetentionPolicyEntity::getTenantId, tenantId)
                        .last("LIMIT 1"));
        if (existing == null) {
            RuntimeSessionRetentionPolicyEntity created = new RuntimeSessionRetentionPolicyEntity();
            created.setTenantId(tenantId);
            created.setActiveRetentionDays(activeRetentionDays);
            created.setClearedRetentionHours(clearedRetentionHours);
            created.setUpdatedByHash(actorHash);
            created.setCreatedAt(now);
            created.setUpdatedAt(now);
            try {
                policyMapper.insert(created);
                existing = created;
            } catch (DuplicateKeyException concurrentInsert) {
                existing = policyMapper.selectOne(
                        new LambdaQueryWrapper<RuntimeSessionRetentionPolicyEntity>()
                                .eq(RuntimeSessionRetentionPolicyEntity::getTenantId, tenantId)
                                .last("LIMIT 1"));
                if (existing == null) {
                    throw concurrentInsert;
                }
            }
        }
        if (existing.getId() != null
                && (!Integer.valueOf(activeRetentionDays).equals(existing.getActiveRetentionDays())
                || !Integer.valueOf(clearedRetentionHours).equals(existing.getClearedRetentionHours())
                || !actorHash.equals(existing.getUpdatedByHash()))) {
            policyMapper.update(null,
                    new LambdaUpdateWrapper<RuntimeSessionRetentionPolicyEntity>()
                            .eq(RuntimeSessionRetentionPolicyEntity::getId, existing.getId())
                            .set(RuntimeSessionRetentionPolicyEntity::getActiveRetentionDays,
                                    activeRetentionDays)
                            .set(RuntimeSessionRetentionPolicyEntity::getClearedRetentionHours,
                                    clearedRetentionHours)
                            .set(RuntimeSessionRetentionPolicyEntity::getUpdatedByHash, actorHash)
                            .set(RuntimeSessionRetentionPolicyEntity::getUpdatedAt, now));
        }
        audit(null, tenantId, null, "RETENTION_POLICY_UPDATED", "PLATFORM_USER", actorHash,
                "POLICY_UPDATED", null, null, "CUSTOM", null, now);
        return new EffectivePolicy(
                tenantId, activeRetentionDays, clearedRetentionHours, true, now);
    }

    @Transactional
    public EffectivePolicy deletePolicy(String tenantId, String actorHash, LocalDateTime now) {
        policyMapper.delete(new LambdaQueryWrapper<RuntimeSessionRetentionPolicyEntity>()
                .eq(RuntimeSessionRetentionPolicyEntity::getTenantId, tenantId));
        audit(null, tenantId, null, "RETENTION_POLICY_DELETED", "PLATFORM_USER", actorHash,
                "POLICY_DEFAULT_RESTORED", null, null, "DEFAULT", null, now);
        return new EffectivePolicy(
                tenantId,
                properties.defaultActiveRetentionDays(),
                properties.defaultClearedRetentionHours(),
                false,
                now);
    }

    @Transactional
    public RuntimeConversationSessionEntity setLegalHold(
            String tenantId,
            String sessionId,
            boolean enabled,
            String reasonCode,
            String referenceId,
            String actorHash,
            LocalDateTime now) {
        RuntimeConversationSessionEntity session = findSession(tenantId, sessionId);
        if (session == null) {
            throw RuntimeSessionRetentionException.notFound();
        }
        String status = normalizedStatus(session.getStatus());
        if (CLEARING.equals(status) || PURGING.equals(status)) {
            throw RuntimeSessionRetentionException.lifecycleBusy();
        }
        boolean current = Boolean.TRUE.equals(session.getLegalHold());
        LambdaUpdateWrapper<RuntimeConversationSessionEntity> update =
                new LambdaUpdateWrapper<RuntimeConversationSessionEntity>()
                        .eq(RuntimeConversationSessionEntity::getId, session.getId())
                        .eq(RuntimeConversationSessionEntity::getStatus, status)
                        .eq(RuntimeConversationSessionEntity::getLegalHold, current ? 1 : 0)
                        .set(RuntimeConversationSessionEntity::getLegalHold, enabled ? 1 : 0)
                        .set(RuntimeConversationSessionEntity::getUpdatedAt, now);
        if (enabled) {
            update.set(RuntimeConversationSessionEntity::getLegalHoldReasonCode, reasonCode)
                    .set(RuntimeConversationSessionEntity::getLegalHoldReference, referenceId)
                    .set(RuntimeConversationSessionEntity::getLegalHoldSetAt, now)
                    .set(RuntimeConversationSessionEntity::getLegalHoldSetByHash, actorHash);
        } else {
            update.set(RuntimeConversationSessionEntity::getLegalHoldReasonCode, null)
                    .set(RuntimeConversationSessionEntity::getLegalHoldReference, null)
                    .set(RuntimeConversationSessionEntity::getLegalHoldSetAt, null)
                    .set(RuntimeConversationSessionEntity::getLegalHoldSetByHash, null);
        }
        if (sessionMapper.update(null, update) != 1) {
            throw RuntimeSessionRetentionException.lifecycleBusy();
        }
        session.setLegalHold(enabled);
        session.setLegalHoldReasonCode(enabled ? reasonCode : null);
        session.setLegalHoldReference(enabled ? referenceId : null);
        session.setLegalHoldSetAt(enabled ? now : null);
        session.setLegalHoldSetByHash(enabled ? actorHash : null);
        session.setUpdatedAt(now);
        audit(session, tenantId, sessionId,
                enabled ? "LEGAL_HOLD_SET" : "LEGAL_HOLD_RELEASED",
                "PLATFORM_USER", actorHash, reasonCode, referenceId,
                status, status, null, now);
        return session;
    }

    @Transactional
    public boolean claimClear(RuntimeConversationSessionEntity session,
                              String owner,
                              String actorHash,
                              LocalDateTime now) {
        if (Boolean.TRUE.equals(session.getLegalHold())) {
            throw RuntimeSessionRetentionException.legalHold();
        }
        String status = normalizedStatus(session.getStatus());
        String previousStatus = CLEARING.equals(status)
                && session.getLifecyclePreviousStatus() != null
                ? session.getLifecyclePreviousStatus() : status;
        LambdaUpdateWrapper<RuntimeConversationSessionEntity> update = lifecycleClaimBase(session, now)
                .set(RuntimeConversationSessionEntity::getStatus, CLEARING)
                .set(RuntimeConversationSessionEntity::getLifecycleOwner, owner)
                .set(RuntimeConversationSessionEntity::getLifecycleLeaseExpiresAt,
                        now.plusSeconds(properties.lifecycleLeaseSeconds()))
                .set(RuntimeConversationSessionEntity::getLifecycleReasonCode, "USER_CLEAR")
                .set(RuntimeConversationSessionEntity::getLifecyclePreviousStatus, previousStatus)
                .set(RuntimeConversationSessionEntity::getUpdatedAt, now);
        if (CLEARING.equals(status)) {
            update.eq(RuntimeConversationSessionEntity::getStatus, CLEARING)
                    .and(wrapper -> wrapper.isNull(
                                    RuntimeConversationSessionEntity::getLifecycleLeaseExpiresAt)
                            .or().le(RuntimeConversationSessionEntity::getLifecycleLeaseExpiresAt, now));
        } else if (ACTIVE.equals(status) || CLEARED.equals(status)) {
            update.eq(RuntimeConversationSessionEntity::getStatus, status);
            if (session.getUpdatedAt() != null) {
                update.eq(RuntimeConversationSessionEntity::getUpdatedAt, session.getUpdatedAt());
            }
        } else {
            throw RuntimeSessionRetentionException.lifecycleBusy();
        }
        if (sessionMapper.update(null, update) != 1) {
            throw RuntimeSessionRetentionException.activeTurn();
        }
        session.setLifecyclePreviousStatus(previousStatus);
        session.setLifecycleReasonCode("USER_CLEAR");
        session.setLifecycleOwner(owner);
        session.setLifecycleLeaseExpiresAt(now.plusSeconds(properties.lifecycleLeaseSeconds()));
        session.setStatus(CLEARING);
        audit(session, session.getTenantId(), session.getSessionId(), "CLEAR_CLAIMED",
                "RUNTIME_USER", actorHash, "USER_CLEAR", null,
                status, CLEARING, null, now);
        return true;
    }

    @Transactional
    public boolean claimClearRecovery(RuntimeConversationSessionEntity session,
                                      String owner,
                                      LocalDateTime now) {
        String status = normalizedStatus(session.getStatus());
        if (!CLEARING.equals(status)) {
            return false;
        }
        int updated = sessionMapper.update(null, lifecycleClaimBase(session, now)
                .eq(RuntimeConversationSessionEntity::getStatus, CLEARING)
                .and(wrapper -> wrapper.isNull(RuntimeConversationSessionEntity::getLifecycleLeaseExpiresAt)
                        .or().le(RuntimeConversationSessionEntity::getLifecycleLeaseExpiresAt, now))
                .set(RuntimeConversationSessionEntity::getLifecycleOwner, owner)
                .set(RuntimeConversationSessionEntity::getLifecycleLeaseExpiresAt,
                        now.plusSeconds(properties.lifecycleLeaseSeconds()))
                .set(RuntimeConversationSessionEntity::getUpdatedAt, now));
        if (updated == 1) {
            session.setLifecycleOwner(owner);
            session.setLifecycleLeaseExpiresAt(now.plusSeconds(properties.lifecycleLeaseSeconds()));
            audit(session, session.getTenantId(), session.getSessionId(), "CLEAR_RECOVERY_CLAIMED",
                    "SYSTEM", null, "USER_CLEAR", null,
                    session.getLifecyclePreviousStatus(), CLEARING, null, now);
        }
        return updated == 1;
    }

    @Transactional
    public void completeClear(RuntimeConversationSessionEntity session,
                              String owner,
                              String actorType,
                              String actorHash,
                              LocalDateTime now) {
        int updated = sessionMapper.update(null,
                new LambdaUpdateWrapper<RuntimeConversationSessionEntity>()
                        .eq(RuntimeConversationSessionEntity::getId, session.getId())
                        .eq(RuntimeConversationSessionEntity::getStatus, CLEARING)
                        .eq(RuntimeConversationSessionEntity::getLifecycleOwner, owner)
                        .set(RuntimeConversationSessionEntity::getStatus, CLEARED)
                        .set(RuntimeConversationSessionEntity::getClearedAt, now)
                        .set(RuntimeConversationSessionEntity::getTurnLeaseOwner, null)
                        .set(RuntimeConversationSessionEntity::getTurnLeaseExpiresAt, null)
                        .set(RuntimeConversationSessionEntity::getLifecycleOwner, null)
                        .set(RuntimeConversationSessionEntity::getLifecycleLeaseExpiresAt, null)
                        .set(RuntimeConversationSessionEntity::getLifecycleReasonCode, null)
                        .set(RuntimeConversationSessionEntity::getLifecyclePreviousStatus, null)
                        .set(RuntimeConversationSessionEntity::getUpdatedAt, now));
        if (updated != 1) {
            throw RuntimeSessionRetentionException.lifecycleBusy();
        }
        audit(session, session.getTenantId(), session.getSessionId(), "CLEAR_COMPLETED",
                actorType, actorHash, "USER_CLEAR", null,
                CLEARING, CLEARED, null, now);
    }

    @Transactional
    public boolean claimRetentionPurge(RuntimeConversationSessionEntity session,
                                       String owner,
                                       LocalDateTime now) {
        String status = normalizedStatus(session.getStatus());
        if (CLEARING.equals(status)) {
            return false;
        }
        String reason = switch (status) {
            case ACTIVE -> "RETENTION_ACTIVE";
            case CLEARED -> "RETENTION_CLEARED";
            case EXPIRED -> "RETENTION_EXPIRED";
            case PURGING -> session.getLifecycleReasonCode() == null
                    ? "RETENTION_RETRY" : session.getLifecycleReasonCode();
            default -> null;
        };
        if (reason == null) {
            return false;
        }
        LambdaUpdateWrapper<RuntimeConversationSessionEntity> update = lifecycleClaimBase(session, now)
                .set(RuntimeConversationSessionEntity::getStatus, PURGING)
                .set(RuntimeConversationSessionEntity::getLifecycleOwner, owner)
                .set(RuntimeConversationSessionEntity::getLifecycleLeaseExpiresAt,
                        now.plusSeconds(properties.lifecycleLeaseSeconds()))
                .set(RuntimeConversationSessionEntity::getLifecycleReasonCode, reason)
                .set(RuntimeConversationSessionEntity::getUpdatedAt, now);
        if (PURGING.equals(status)) {
            update.eq(RuntimeConversationSessionEntity::getStatus, PURGING)
                    .and(wrapper -> wrapper.isNull(
                                    RuntimeConversationSessionEntity::getLifecycleLeaseExpiresAt)
                            .or().le(RuntimeConversationSessionEntity::getLifecycleLeaseExpiresAt, now));
        } else {
            update.eq(RuntimeConversationSessionEntity::getStatus, status)
                    .set(RuntimeConversationSessionEntity::getLifecyclePreviousStatus, status);
            if (session.getUpdatedAt() != null) {
                update.eq(RuntimeConversationSessionEntity::getUpdatedAt, session.getUpdatedAt());
            }
        }
        int updated = sessionMapper.update(null, update);
        if (updated == 1) {
            session.setLifecyclePreviousStatus(PURGING.equals(status)
                    ? session.getLifecyclePreviousStatus() : status);
            session.setLifecycleReasonCode(reason);
            session.setLifecycleOwner(owner);
            session.setLifecycleLeaseExpiresAt(now.plusSeconds(properties.lifecycleLeaseSeconds()));
            session.setStatus(PURGING);
            audit(session, session.getTenantId(), session.getSessionId(), "PURGE_CLAIMED",
                    "SYSTEM", null, reason, null,
                    status, PURGING, null, now);
        }
        return updated == 1;
    }

    @Transactional
    public RuntimeConversationSessionEntity claimManualPurge(
            String tenantId,
            String sessionId,
            String reasonCode,
            String referenceId,
            String actorHash,
            String owner,
            LocalDateTime now) {
        RuntimeConversationSessionEntity session = findSession(tenantId, sessionId);
        if (session == null) {
            throw RuntimeSessionRetentionException.notFound();
        }
        if (Boolean.TRUE.equals(session.getLegalHold())) {
            throw RuntimeSessionRetentionException.legalHold();
        }
        String status = normalizedStatus(session.getStatus());
        String previousStatus = (CLEARING.equals(status) || PURGING.equals(status))
                && session.getLifecyclePreviousStatus() != null
                ? session.getLifecyclePreviousStatus() : status;
        LambdaUpdateWrapper<RuntimeConversationSessionEntity> update = lifecycleClaimBase(session, now)
                .set(RuntimeConversationSessionEntity::getStatus, PURGING)
                .set(RuntimeConversationSessionEntity::getLifecycleOwner, owner)
                .set(RuntimeConversationSessionEntity::getLifecycleLeaseExpiresAt,
                        now.plusSeconds(properties.lifecycleLeaseSeconds()))
                .set(RuntimeConversationSessionEntity::getLifecycleReasonCode, reasonCode)
                .set(RuntimeConversationSessionEntity::getLifecyclePreviousStatus, previousStatus)
                .set(RuntimeConversationSessionEntity::getUpdatedAt, now);
        if (CLEARING.equals(status) || PURGING.equals(status)) {
            update.eq(RuntimeConversationSessionEntity::getStatus, status)
                    .and(wrapper -> wrapper.isNull(
                                    RuntimeConversationSessionEntity::getLifecycleLeaseExpiresAt)
                            .or().le(RuntimeConversationSessionEntity::getLifecycleLeaseExpiresAt, now));
        } else if (ACTIVE.equals(status) || CLEARED.equals(status) || EXPIRED.equals(status)) {
            update.eq(RuntimeConversationSessionEntity::getStatus, status);
            if (session.getUpdatedAt() != null) {
                update.eq(RuntimeConversationSessionEntity::getUpdatedAt, session.getUpdatedAt());
            }
        } else {
            throw RuntimeSessionRetentionException.lifecycleBusy();
        }
        if (sessionMapper.update(null, update) != 1) {
            throw hasActiveTurn(session, now)
                    ? RuntimeSessionRetentionException.activeTurn()
                    : RuntimeSessionRetentionException.lifecycleBusy();
        }
        session.setLifecyclePreviousStatus(previousStatus);
        session.setLifecycleReasonCode(reasonCode);
        session.setLifecycleOwner(owner);
        session.setLifecycleLeaseExpiresAt(now.plusSeconds(properties.lifecycleLeaseSeconds()));
        session.setStatus(PURGING);
        audit(session, tenantId, sessionId, "MANUAL_ERASE_CLAIMED",
                "PLATFORM_USER", actorHash, reasonCode, referenceId,
                status, PURGING, null, now);
        return session;
    }

    @Transactional
    public void finalizePurge(RuntimeConversationSessionEntity session,
                              String owner,
                              String actorType,
                              String actorHash,
                              String referenceId,
                              LocalDateTime now) {
        eventMapper.delete(new LambdaQueryWrapper<RuntimeConversationEventEntity>()
                .eq(RuntimeConversationEventEntity::getConversationSessionId, session.getId()));
        int deleted = sessionMapper.delete(new LambdaQueryWrapper<RuntimeConversationSessionEntity>()
                .eq(RuntimeConversationSessionEntity::getId, session.getId())
                .eq(RuntimeConversationSessionEntity::getStatus, PURGING)
                .eq(RuntimeConversationSessionEntity::getLifecycleOwner, owner)
                .eq(RuntimeConversationSessionEntity::getLegalHold, 0));
        if (deleted != 1) {
            throw RuntimeSessionRetentionException.lifecycleBusy();
        }
        audit(session, session.getTenantId(), session.getSessionId(), "PURGE_COMPLETED",
                actorType, actorHash, session.getLifecycleReasonCode(), referenceId,
                session.getLifecyclePreviousStatus(), "ERASED", null, now);
    }

    public void recordFailure(RuntimeConversationSessionEntity session,
                              String eventType,
                              String actorType,
                              String actorHash,
                              String referenceId,
                              Throwable failure,
                              LocalDateTime now) {
        audit(session, session.getTenantId(), session.getSessionId(), eventType,
                actorType, actorHash, session.getLifecycleReasonCode(), referenceId,
                session.getLifecyclePreviousStatus(), session.getStatus(),
                failure == null ? "UNKNOWN" : failure.getClass().getSimpleName(), now);
    }

    public void recordOwnerEraseNotCompleted(RuntimeConversationSessionEntity session,
                                             String actorHash,
                                             String reasonCode,
                                             String referenceId,
                                             String failureCode,
                                             LocalDateTime now) {
        if (session == null) {
            return;
        }
        audit(session, session.getTenantId(), session.getSessionId(),
                "OWNER_ERASE_NOT_COMPLETED", "PLATFORM_USER", actorHash,
                reasonCode, referenceId, normalizedStatus(session.getStatus()),
                normalizedStatus(session.getStatus()), failureCode, now);
    }

    private LambdaUpdateWrapper<RuntimeConversationSessionEntity> lifecycleClaimBase(
            RuntimeConversationSessionEntity session,
            LocalDateTime now) {
        return new LambdaUpdateWrapper<RuntimeConversationSessionEntity>()
                .eq(RuntimeConversationSessionEntity::getId, session.getId())
                .eq(RuntimeConversationSessionEntity::getLegalHold, 0)
                .and(wrapper -> wrapper.isNull(RuntimeConversationSessionEntity::getTurnLeaseExpiresAt)
                        .or().le(RuntimeConversationSessionEntity::getTurnLeaseExpiresAt, now));
    }

    private void audit(RuntimeConversationSessionEntity session,
                       String tenantId,
                       String sessionId,
                       String eventType,
                       String actorType,
                       String actorHash,
                       String reasonCode,
                       String referenceId,
                       String previousStatus,
                       String resultStatus,
                       String failureCode,
                       LocalDateTime now) {
        RuntimeSessionRetentionAuditEntity event = new RuntimeSessionRetentionAuditEntity();
        event.setTenantId(tenantId);
        event.setSessionIdHash(sessionId == null ? null
                : RuntimeSessionRetentionSupport.sessionHash(tenantId, sessionId));
        event.setConversationSessionId(session == null ? null : session.getId());
        event.setEventType(eventType);
        event.setActorType(actorType);
        event.setActorIdHash(actorHash);
        event.setReasonCode(reasonCode);
        event.setReferenceId(referenceId);
        event.setPreviousStatus(previousStatus);
        event.setResultStatus(resultStatus);
        event.setFailureCode(failureCode);
        event.setCreatedAt(now);
        auditMapper.insert(event);
    }

    private static String normalizedStatus(String status) {
        return status == null ? ACTIVE : status.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static boolean hasActiveTurn(RuntimeConversationSessionEntity session, LocalDateTime now) {
        return session.getTurnLeaseExpiresAt() != null && session.getTurnLeaseExpiresAt().isAfter(now);
    }

    public record EffectivePolicy(
            String tenantId,
            int activeRetentionDays,
            int clearedRetentionHours,
            boolean customized,
            LocalDateTime updatedAt) {
    }
}
