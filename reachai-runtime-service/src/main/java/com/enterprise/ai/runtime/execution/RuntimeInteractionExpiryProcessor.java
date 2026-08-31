package com.enterprise.ai.runtime.execution;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Reconciles durable WAITING_USER sessions whose TTL has elapsed. */
@Service
@RequiredArgsConstructor
public class RuntimeInteractionExpiryProcessor {

    private static final String WAITING_USER = "WAITING_USER";
    private static final String EXPIRED = "EXPIRED";

    private final RuntimeInteractionSessionMapper sessionMapper;
    private final RuntimeWorkflowInteractionSessionService sessionService;
    private final RuntimeInteractionExpiryTracePort tracePort;

    public List<RuntimeInteractionSessionEntity> findExpiredCandidates(LocalDateTime now, int batchSize) {
        LocalDateTime cutoff = now == null ? LocalDateTime.now() : now;
        int limit = Math.max(1, Math.min(batchSize, 500));
        return sessionMapper.selectList(Wrappers.<RuntimeInteractionSessionEntity>lambdaQuery()
                .eq(RuntimeInteractionSessionEntity::getStatus, WAITING_USER)
                .isNotNull(RuntimeInteractionSessionEntity::getExpiresAt)
                .le(RuntimeInteractionSessionEntity::getExpiresAt, cutoff)
                .orderByAsc(RuntimeInteractionSessionEntity::getExpiresAt)
                .last("LIMIT " + limit));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expireOne(RuntimeInteractionSessionEntity session, LocalDateTime now) {
        if (session == null || !StringUtils.hasText(session.getId())) {
            return false;
        }
        LocalDateTime expiredAt = now == null ? LocalDateTime.now() : now;
        if (session.getExpiresAt() == null || session.getExpiresAt().isAfter(expiredAt)) {
            return false;
        }
        int revision = session.getRevision() == null ? 0 : session.getRevision();
        Map<String, Object> result = Map.of(
                "code", "RUNTIME_INTERACTION_EXPIRED",
                "status", EXPIRED,
                "expiredAt", expiredAt.toString());
        UpdateWrapper<RuntimeInteractionSessionEntity> update = new UpdateWrapper<>();
        update.eq("id", session.getId())
                .eq("status", WAITING_USER)
                .eq("revision", revision)
                .le("expires_at", expiredAt)
                .set("status", EXPIRED)
                .set("revision", revision + 1)
                .set("result_json", sessionService.writeJson(result))
                .set("update_time", expiredAt);
        if (sessionMapper.update(null, update) != 1) {
            return false;
        }
        sessionService.writeEvent(session.getId(), EXPIRED, Map.of(
                "reason", "ttl",
                "traceId", session.getTraceId() == null ? "" : session.getTraceId()),
                "runtime-expiry-reconciler");
        tracePort.expireWaitingInteraction(session.getTraceId(), session.getId(), expiredAt);
        session.setStatus(EXPIRED);
        session.setRevision(revision + 1);
        session.setResultJson(sessionService.writeJson(result));
        session.setUpdateTime(expiredAt);
        return true;
    }
}
