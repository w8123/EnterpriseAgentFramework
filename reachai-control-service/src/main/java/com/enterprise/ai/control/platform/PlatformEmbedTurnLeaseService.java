package com.enterprise.ai.control.platform;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

/** Control-owned conservative activity tracking for safe document transfer. */
@Service
@RequiredArgsConstructor
public class PlatformEmbedTurnLeaseService {
    private static final String LEASE = "TURN_LEASE";
    private static final String BINDING = "TURN_INTERACTION";
    private static final String TRACKING = "TURN_TRACKING";
    private final PlatformEmbedSessionMapper sessions;
    private final PlatformEmbedChatEventMapper events;
    private final PlatformEmbedChatEventService history;
    private final ObjectMapper json;

    public record TurnAdmission(long epoch, long turnId, long attemptId) { }
    public record IdleSnapshot(long epoch, long latestTurnId) { }

    /** Called only for newly created sessions or after a confirmed Runtime context clear. */
    @Transactional
    public void startTracking(PlatformEmbedSessionEntity session) {
        lockSession(session, true);
        insert(session, TRACKING, "ACTIVE", null, Map.of());
    }

    /** Caller keeps the session lock until the ticket operation commits. */
    @Transactional
    public IdleSnapshot idleSnapshot(PlatformEmbedSessionEntity session) {
        long epoch = history.currentEpochForTransfer(session);
        PlatformEmbedChatEventEntity tracking = events.selectOne(Wrappers.<PlatformEmbedChatEventEntity>lambdaQuery()
                .eq(PlatformEmbedChatEventEntity::getSessionId, session.getSessionId())
                .eq(PlatformEmbedChatEventEntity::getEventType, TRACKING)
                .orderByDesc(PlatformEmbedChatEventEntity::getId).last("LIMIT 1"));
        if (tracking == null || !Objects.equals(tracking.getSessionId(), session.getSessionId())
                || !TRACKING.equals(tracking.getEventType()) || !"ACTIVE".equals(tracking.getRole())) {
            throw new IllegalStateException("turn tracking is unavailable for this session");
        }
        var leases = events.selectList(Wrappers.<PlatformEmbedChatEventEntity>lambdaQuery()
                .eq(PlatformEmbedChatEventEntity::getSessionId, session.getSessionId())
                .eq(PlatformEmbedChatEventEntity::getEventType, LEASE)
                .gt(PlatformEmbedChatEventEntity::getId, epoch)
                .orderByDesc(PlatformEmbedChatEventEntity::getId).last("LIMIT 10001"));
        if (leases.size() > 10000) throw new IllegalStateException("too many tracked turns");
        if (leases.stream().anyMatch(event -> {
            long eventEpoch = number(payload(event).get("epoch"));
            return !Objects.equals(event.getSessionId(), session.getSessionId()) || !LEASE.equals(event.getEventType())
                    || eventEpoch <= 0 || (eventEpoch == epoch && !"COMPLETE".equals(event.getRole()));
        })) {
            throw new IllegalStateException("conversation has an unsettled turn");
        }
        return new IdleSnapshot(epoch, leases.isEmpty() ? 0 : leases.get(0).getId());
    }

    @Transactional
    public TurnAdmission admitMessage(PlatformEmbedSessionEntity session, String message) {
        long epoch = history.recordUserMessage(session, message);
        PlatformEmbedChatEventEntity lease = insert(session, LEASE, "ACTIVE", null, Map.of("epoch", epoch, "attempt", 1L));
        return new TurnAdmission(epoch, lease.getId(), 1L);
    }

    @Transactional
    public TurnAdmission resumeInteraction(PlatformEmbedSessionEntity session, String interactionId) {
        long epoch = history.admitInteraction(session, interactionId);
        PlatformEmbedChatEventEntity binding = events.selectOne(Wrappers.<PlatformEmbedChatEventEntity>lambdaQuery()
                .eq(PlatformEmbedChatEventEntity::getSessionId, session.getSessionId())
                .eq(PlatformEmbedChatEventEntity::getEventType, BINDING)
                .eq(PlatformEmbedChatEventEntity::getContent, interactionId)
                .orderByDesc(PlatformEmbedChatEventEntity::getId).last("LIMIT 1"));
        Map<String, Object> details = payload(binding);
        long turnId = number(details.get("turnId"));
        long priorAttempt = number(details.get("attempt"));
        PlatformEmbedChatEventEntity lease = events.selectById(turnId);
        if (binding == null || !Objects.equals(binding.getSessionId(), session.getSessionId())
                || !BINDING.equals(binding.getEventType()) || !Objects.equals(binding.getContent(), interactionId)
                || !matches(session, lease, epoch, priorAttempt) || !"WAITING".equals(lease.getRole())) {
            throw new IllegalStateException("interaction is not waiting in the current turn");
        }
        long attempt = Math.addExact(priorAttempt, 1L);
        lease.setRole("ACTIVE");
        lease.setPayloadJson(write(Map.of("epoch", epoch, "attempt", attempt)));
        update(lease);
        return new TurnAdmission(epoch, turnId, attempt);
    }

    /** Persist waiting and its binding before exposing the UI event. */
    @Transactional
    public boolean waiting(PlatformEmbedSessionEntity session, TurnAdmission admission, String interactionId) {
        if (!StringUtils.hasText(interactionId)) return false;
        lockSession(session, false);
        PlatformEmbedChatEventEntity lease = events.selectById(admission.turnId());
        if (!matches(session, lease, admission.epoch(), admission.attemptId())) return false;
        if (!"ACTIVE".equals(lease.getRole()) && !"WAITING".equals(lease.getRole())) return false;
        if ("WAITING".equals(lease.getRole())) {
            return interactionId.equals(lease.getContent());
        }
        history.bindInteraction(session, interactionId, admission.epoch());
        insert(session, BINDING, "BOUND", interactionId, Map.of("turnId", admission.turnId(), "attempt", admission.attemptId()));
        lease.setRole("WAITING");
        lease.setContent(interactionId);
        update(lease);
        return true;
    }

    @Transactional
    public boolean complete(PlatformEmbedSessionEntity session, TurnAdmission admission) {
        return changeStatus(session, admission, "COMPLETE");
    }

    @Transactional
    public void unknown(PlatformEmbedSessionEntity session, TurnAdmission admission) {
        changeStatus(session, admission, "UNKNOWN");
    }

    @Transactional
    public boolean completeAndRecord(PlatformEmbedSessionEntity session, TurnAdmission admission,
                                     String answer, Map<String, Object> payload, String traceId) {
        lockSession(session, false);
        PlatformEmbedChatEventEntity lease = events.selectById(admission.turnId());
        if (!matches(session, lease, admission.epoch(), admission.attemptId())
                || (!"ACTIVE".equals(lease.getRole()) && !"WAITING".equals(lease.getRole()))) return false;
        history.recordAssistantMessage(session, answer, payload, traceId, admission.epoch());
        lease.setRole("COMPLETE");
        update(lease);
        return true;
    }

    @Transactional
    public boolean recordWaitingAnswer(PlatformEmbedSessionEntity session, TurnAdmission admission,
                                       String answer, Map<String, Object> payload, String traceId) {
        lockSession(session, false);
        PlatformEmbedChatEventEntity lease = events.selectById(admission.turnId());
        if (!matches(session, lease, admission.epoch(), admission.attemptId()) || !"WAITING".equals(lease.getRole())) return false;
        history.recordAssistantMessage(session, answer, payload, traceId, admission.epoch());
        return true;
    }

    private boolean changeStatus(PlatformEmbedSessionEntity session, TurnAdmission admission, String status) {
        lockSession(session, false);
        PlatformEmbedChatEventEntity lease = events.selectById(admission.turnId());
        if (!matches(session, lease, admission.epoch(), admission.attemptId())) return false;
        // WAITING is a persisted interaction and remains non-idle on disconnect.
        if ("UNKNOWN".equals(status) && "WAITING".equals(lease.getRole())) return false;
        if (!"ACTIVE".equals(lease.getRole()) && !"WAITING".equals(lease.getRole())) return false;
        lease.setRole(status);
        update(lease);
        return true;
    }

    private boolean matches(PlatformEmbedSessionEntity session, PlatformEmbedChatEventEntity lease, long epoch, long attempt) {
        Map<String, Object> data = payload(lease);
        return lease != null && LEASE.equals(lease.getEventType()) && Objects.equals(session.getSessionId(), lease.getSessionId())
                && number(data.get("epoch")) == epoch && number(data.get("attempt")) == attempt;
    }

    private void lockSession(PlatformEmbedSessionEntity source, boolean requirePage) {
        PlatformEmbedSessionEntity current = sessions.selectOne(Wrappers.<PlatformEmbedSessionEntity>lambdaQuery()
                .eq(PlatformEmbedSessionEntity::getSessionId, source.getSessionId()).last("LIMIT 1 FOR UPDATE"));
        if (current == null || !Objects.equals(current.getSessionId(), source.getSessionId()) || !"ACTIVE".equals(current.getStatus())
                || !Objects.equals(current.getTenantId(), source.getTenantId())
                || !Objects.equals(current.getAppId(), source.getAppId())
                || !Objects.equals(current.getProjectCode(), source.getProjectCode())
                || !Objects.equals(current.getAgentId(), source.getAgentId())
                || !Objects.equals(current.getExternalUserId(), source.getExternalUserId())
                || !Objects.equals(current.getGlobalUserId(), source.getGlobalUserId())
                || !Objects.equals(current.getBusinessSessionId(), source.getBusinessSessionId())
                || !Objects.equals(current.getOrigin(), source.getOrigin())
                || (requirePage && !Objects.equals(current.getPageInstanceId(), source.getPageInstanceId()))) {
            throw new PlatformEmbedTokenException("embed session changed during turn tracking");
        }
    }

    private PlatformEmbedChatEventEntity insert(PlatformEmbedSessionEntity session, String type, String role, String content, Map<String, Object> data) {
        var event = new PlatformEmbedChatEventEntity();
        event.setSessionId(session.getSessionId()); event.setEventType(type); event.setRole(role);
        event.setContent(content); event.setPayloadJson(write(data)); event.setCreatedAt(LocalDateTime.now());
        if (events.insert(event) != 1 || event.getId() == null) throw new IllegalStateException("turn tracking insert failed");
        return event;
    }

    private void update(PlatformEmbedChatEventEntity event) {
        if (events.updateById(event) != 1) throw new IllegalStateException("turn tracking update failed");
    }

    private Map<String, Object> payload(PlatformEmbedChatEventEntity event) {
        if (event == null || !StringUtils.hasText(event.getPayloadJson())) return Map.of();
        try { return json.readValue(event.getPayloadJson(), new TypeReference<>() {}); }
        catch (Exception ex) { throw new IllegalStateException("invalid turn tracking state", ex); }
    }

    private String write(Map<String, Object> value) {
        try { return json.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("cannot encode turn tracking state", ex); }
    }

    private long number(Object value) { return value instanceof Number number ? number.longValue() : -1L; }
}
