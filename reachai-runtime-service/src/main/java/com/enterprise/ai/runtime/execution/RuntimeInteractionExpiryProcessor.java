package com.enterprise.ai.runtime.execution;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionCodes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reconciles waiting TTLs and Workflow/Supervisor resume deadlines without repeating execution. */
@Service
@RequiredArgsConstructor
public class RuntimeInteractionExpiryProcessor {

    private static final String WAITING_USER = "WAITING_USER";
    private static final String EXPIRED = "EXPIRED";

    private final RuntimeInteractionSessionMapper sessionMapper;
    private final RuntimeWorkflowInteractionSessionService sessionService;
    private final RuntimeInteractionExpiryTracePort tracePort;
    private final RuntimeSupervisorApprovalService supervisorApprovals;

    public List<RuntimeInteractionSessionEntity> findExpiredCandidates(LocalDateTime now, int batchSize) {
        LocalDateTime cutoff = now == null ? LocalDateTime.now() : now;
        int limit = Math.max(1, Math.min(batchSize, 500));
        var waiting = sessionMapper.selectList(Wrappers.<RuntimeInteractionSessionEntity>lambdaQuery()
                .eq(RuntimeInteractionSessionEntity::getStatus, WAITING_USER)
                .isNotNull(RuntimeInteractionSessionEntity::getExpiresAt)
                .le(RuntimeInteractionSessionEntity::getExpiresAt, cutoff)
                .orderByAsc(RuntimeInteractionSessionEntity::getExpiresAt, RuntimeInteractionSessionEntity::getId)
                .last("LIMIT " + limit));
        var resuming = sessionMapper.selectList(Wrappers.<RuntimeInteractionSessionEntity>lambdaQuery()
                .eq(RuntimeInteractionSessionEntity::getStatus, "RESUMING")
                .and(source -> source.in(RuntimeInteractionSessionEntity::getSourceType, RuntimeWorkflowInteractionSessionService.RESUME_SOURCE_TYPES)
                        .or(supervisor -> supervisor.eq(RuntimeInteractionSessionEntity::getSourceType, RuntimeSupervisorApprovalService.SOURCE_TYPE)
                                .eq(RuntimeInteractionSessionEntity::getInteractionType, RuntimeSupervisorApprovalService.INTERACTION_TYPE)))
                .le(RuntimeInteractionSessionEntity::getResumeDeadlineAt, cutoff)
                .orderByAsc(RuntimeInteractionSessionEntity::getResumeDeadlineAt, RuntimeInteractionSessionEntity::getId)
                .last("LIMIT " + limit));
        var candidates = new ArrayList<>(waiting);
        candidates.addAll(resuming);
        return candidates.stream().sorted(Comparator.comparing((RuntimeInteractionSessionEntity row) ->
                        "RESUMING".equals(row.getStatus()) ? row.getResumeDeadlineAt() : row.getExpiresAt())
                .thenComparing(RuntimeInteractionSessionEntity::getId)).limit(limit).toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expireOne(RuntimeInteractionSessionEntity session, LocalDateTime now) {
        if (session == null || !StringUtils.hasText(session.getId())) {
            return false;
        }
        LocalDateTime expiredAt = now == null ? LocalDateTime.now() : now;
        if ("RESUMING".equals(session.getStatus()) && RuntimeSupervisorApprovalService.SOURCE_TYPE.equals(session.getSourceType())) {
            return supervisorApprovals.expireResume(session, expiredAt);
        }
        if ("RESUMING".equals(session.getStatus())) return expireResume(session, expiredAt);
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

    /** The caller's REQUIRES_NEW transaction includes session, event, Trace and RunOps writes. */
    private boolean expireResume(RuntimeInteractionSessionEntity session, LocalDateTime expiredAt) {
        if (session.getResumeDeadlineAt() == null || session.getResumeDeadlineAt().isAfter(expiredAt)
                || !RuntimeWorkflowInteractionSessionService.RESUME_SOURCE_TYPES.contains(session.getSourceType())) return false;
        int revision = session.getRevision() == null ? 0 : session.getRevision();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("code", WorkflowInteractionCodes.RESUME_TIMEOUT);
        response.put("answer", WorkflowInteractionCodes.RESUME_TIMEOUT_MESSAGE);
        response.put("status", EXPIRED);
        response.put("waiting", false);
        response.put("interactionId", session.getId());
        response.put("interactionSessionId", session.getId());
        response.put("runId", session.getRunId());
        response.put("traceId", session.getTraceId());
        response.put("workflowId", session.getWorkflowId());
        response.put("workflowVersionId", session.getWorkflowVersionId());
        Map<String, Object> outcome = Map.of("outcome", "UNKNOWN", "retryable", false, "reconciliationRequired", true);
        response.putAll(outcome);
        response.put("metadata", outcome);
        String result = sessionService.writeJson(Map.of("resumeResultSchemaVersion", 1, "response", response));
        int updated = sessionMapper.update(null, new UpdateWrapper<RuntimeInteractionSessionEntity>()
                .eq("id", session.getId()).eq("status", "RESUMING").eq("revision", revision)
                .in("source_type", RuntimeWorkflowInteractionSessionService.RESUME_SOURCE_TYPES)
                .le("resume_deadline_at", expiredAt)
                .set("status", EXPIRED).set("revision", revision + 1)
                .set("result_json", result).set("update_time", expiredAt));
        if (updated != 1) return false;
        sessionService.writeEvent(session.getId(), EXPIRED, Map.of(
                "reason", "resume_deadline", "code", WorkflowInteractionCodes.RESUME_TIMEOUT,
                "outcome", "UNKNOWN", "resumeDeadlineAt", session.getResumeDeadlineAt().toString()),
                "runtime-expiry-reconciler");
        tracePort.expireResumingInteraction(session.getTraceId(), session.getId(), expiredAt);
        return true;
    }
}
