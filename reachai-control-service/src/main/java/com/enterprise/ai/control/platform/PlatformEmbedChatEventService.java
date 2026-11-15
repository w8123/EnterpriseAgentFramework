package com.enterprise.ai.control.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;

@Service
@RequiredArgsConstructor
public class PlatformEmbedChatEventService {

    private final PlatformEmbedChatEventMapper mapper;
    private final ObjectMapper objectMapper;
    private PlatformEmbedSessionMapper sessionMapper;

    private static final String CLEAR = "CONTEXT_CLEAR";
    private static final String PENDING = "PENDING";
    private static final String COMPLETE = "COMPLETE";
    private static final String INTERACTION = "INTERACTION_EPOCH";
    private static final String EPOCH = "__reachaiHistoryEpoch";

    @Autowired
    void setSessionMapper(PlatformEmbedSessionMapper sessionMapper) {
        this.sessionMapper = sessionMapper;
    }

    /** Serialize admission against clearing and capture the epoch before starting Runtime. */
    @Transactional
    public long recordUserMessage(PlatformEmbedSessionEntity session, String message) {
        lockSession(session);
        long epoch = currentEpoch(session);
        insert(session, "MESSAGE", "user", message, Map.of("message", message, EPOCH, epoch), null);
        return epoch;
    }

    public void recordAssistantMessage(PlatformEmbedSessionEntity session, String answer,
                                       Map<String, Object> payload, String traceId, long epoch) {
        Map<String, Object> audited = new LinkedHashMap<>(payload == null ? Map.of() : payload);
        // Never accept the history boundary from Runtime or browser input.
        audited.put(EPOCH, epoch);
        insert(session, "MESSAGE", "assistant", answer, audited, traceId);
    }

    /** Persist the visibility barrier before the remote clear: uncertainty stays fail-closed. */
    @Transactional
    public long beginClear(PlatformEmbedSessionEntity session) {
        lockSession(session);
        PlatformEmbedChatEventEntity last = latestClear(session.getSessionId());
        if (last != null && PENDING.equals(last.getRole())) return last.getId();
        return insert(session, CLEAR, PENDING, null, Map.of(), null).getId();
    }

    @Transactional
    public void completeClear(PlatformEmbedSessionEntity session, long markerId) {
        lockSession(session);
        PlatformEmbedChatEventEntity marker = latestClear(session.getSessionId());
        if (marker == null || !Objects.equals(marker.getId(), markerId)) {
            throw new IllegalStateException("embed history clear marker changed");
        }
        if (COMPLETE.equals(marker.getRole())) return;
        if (!PENDING.equals(marker.getRole())) throw new IllegalStateException("embed history clear marker changed");
        marker.setRole(COMPLETE);
        if (mapper.updateById(marker) != 1) throw new IllegalStateException("embed history clear failed");
    }

    /** Bind an interaction to its original trusted turn, never to a request-provided epoch. */
    public void bindInteraction(PlatformEmbedSessionEntity session, String interactionId, long epoch) {
        if (!StringUtils.hasText(interactionId)) return;
        insert(session, INTERACTION, "BOUND", interactionId.trim(), Map.of(EPOCH, epoch), null);
    }

    @Transactional
    public long admitInteraction(PlatformEmbedSessionEntity session, String interactionId) {
        if (!StringUtils.hasText(interactionId)) throw new IllegalArgumentException("interactionId is required");
        lockSession(session);
        long epoch = currentEpoch(session);
        PlatformEmbedChatEventEntity binding = mapper.selectOne(Wrappers.<PlatformEmbedChatEventEntity>lambdaQuery()
                .eq(PlatformEmbedChatEventEntity::getSessionId, session.getSessionId())
                .eq(PlatformEmbedChatEventEntity::getEventType, INTERACTION)
                .eq(PlatformEmbedChatEventEntity::getContent, interactionId.trim())
                .orderByDesc(PlatformEmbedChatEventEntity::getId).last("LIMIT 1"));
        if (binding == null || !Objects.equals(binding.getSessionId(), session.getSessionId())
                || !INTERACTION.equals(binding.getEventType()) || !Objects.equals(binding.getContent(), interactionId.trim())
                || eventEpoch(binding) != epoch) {
            throw new InteractionNotReadyException();
        }
        return epoch;
    }

    /** Check readiness without throwing across a transaction boundary. */
    @Transactional
    public boolean interactionReady(PlatformEmbedSessionEntity session, String interactionId) {
        try {
            admitInteraction(session, interactionId);
            return true;
        } catch (InteractionNotReadyException | HistoryClearPendingException unavailable) {
            return false;
        }
    }

    @Transactional
    public long currentEpochForTransfer(PlatformEmbedSessionEntity session) {
        lockSession(session);
        return currentEpoch(session);
    }

    /** Public projection is bounded and contains text only; payload and trace never leave Control. */
    @Transactional
    public HistoryPage history(PlatformEmbedSessionEntity session, Long beforeId, int requestedLimit) {
        return history(session, beforeId, requestedLimit, null);
    }

    @Transactional
    public HistoryPage history(PlatformEmbedSessionEntity session, Long beforeId, int requestedLimit, Long expectedEpoch) {
        lockSession(session);
        long epoch = currentEpoch(session);
        if (beforeId != null && expectedEpoch == null) {
            throw new IllegalArgumentException("history cursor requires expected epoch");
        }
        if (expectedEpoch != null && (expectedEpoch <= 0 || expectedEpoch.longValue() != epoch)) {
            throw new HistoryEpochChangedException();
        }
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        if (beforeId != null && beforeId <= 0) throw new IllegalArgumentException("invalid history cursor");
        var query = Wrappers.<PlatformEmbedChatEventEntity>lambdaQuery()
                .eq(PlatformEmbedChatEventEntity::getSessionId, session.getSessionId())
                .eq(PlatformEmbedChatEventEntity::getEventType, "MESSAGE")
                .in(PlatformEmbedChatEventEntity::getRole, "user", "assistant")
                .gt(PlatformEmbedChatEventEntity::getId, epoch)
                .lt(beforeId != null, PlatformEmbedChatEventEntity::getId, beforeId)
                .orderByDesc(PlatformEmbedChatEventEntity::getId)
                .last("LIMIT " + (limit + 1));
        List<PlatformEmbedChatEventEntity> rows = mapper.selectList(query);
        boolean more = rows.size() > limit;
        if (more) rows = rows.subList(0, limit);
        Long cursor = more && !rows.isEmpty() ? rows.get(rows.size() - 1).getId() : null;
        List<HistoryMessage> messages = new ArrayList<>();
        for (PlatformEmbedChatEventEntity row : rows) {
            if (!Objects.equals(row.getSessionId(), session.getSessionId()) || !"MESSAGE".equals(row.getEventType())
                    || (!"user".equals(row.getRole()) && !"assistant".equals(row.getRole()))) {
                throw new IllegalStateException("embed history ownership mismatch");
            }
            if (eventEpoch(row) == epoch && StringUtils.hasText(row.getContent())) {
                messages.add(new HistoryMessage(Long.toString(row.getId()), row.getRole(), row.getContent(), row.getCreatedAt()));
            }
        }
        Collections.reverse(messages);
        return new HistoryPage(List.copyOf(messages), cursor == null ? null : Long.toString(cursor), Long.toString(epoch));
    }

    private long currentEpoch(PlatformEmbedSessionEntity session) {
        PlatformEmbedChatEventEntity marker = latestClear(session.getSessionId());
        // Older sessions have no recorded clear boundary. Start a new visibility epoch;
        // importing their audit log could resurrect messages cleared before this feature existed.
        if (marker == null) return insert(session, CLEAR, COMPLETE, null, Map.of(), null).getId();
        if (!COMPLETE.equals(marker.getRole())) throw new HistoryClearPendingException();
        return marker.getId();
    }

    private PlatformEmbedChatEventEntity latestClear(String sessionId) {
        PlatformEmbedChatEventEntity marker = mapper.selectOne(Wrappers.<PlatformEmbedChatEventEntity>lambdaQuery()
                .eq(PlatformEmbedChatEventEntity::getSessionId, sessionId)
                .eq(PlatformEmbedChatEventEntity::getEventType, CLEAR)
                .orderByDesc(PlatformEmbedChatEventEntity::getId).last("LIMIT 1"));
        if (marker != null && (!Objects.equals(marker.getSessionId(), sessionId) || !CLEAR.equals(marker.getEventType()))) {
            throw new IllegalStateException("embed history marker ownership mismatch");
        }
        return marker;
    }

    private void lockSession(PlatformEmbedSessionEntity session) {
        if (session == null || !StringUtils.hasText(session.getSessionId()) || sessionMapper == null) {
            throw new IllegalStateException("embed history is unavailable");
        }
        PlatformEmbedSessionEntity locked = sessionMapper.selectOne(Wrappers.<PlatformEmbedSessionEntity>lambdaQuery()
                .eq(PlatformEmbedSessionEntity::getSessionId, session.getSessionId()).last("LIMIT 1 FOR UPDATE"));
        if (locked == null || !Objects.equals(locked.getSessionId(), session.getSessionId()) || !"ACTIVE".equals(locked.getStatus())
                || !Objects.equals(locked.getPageInstanceId(), session.getPageInstanceId())
                || !Objects.equals(locked.getBusinessSessionId(), session.getBusinessSessionId())
                || !Objects.equals(locked.getExternalUserId(), session.getExternalUserId())
                || !Objects.equals(locked.getGlobalUserId(), session.getGlobalUserId())
                || !Objects.equals(locked.getTenantId(), session.getTenantId())
                || !Objects.equals(locked.getAppId(), session.getAppId())
                || !Objects.equals(locked.getProjectCode(), session.getProjectCode())
                || !Objects.equals(locked.getAgentId(), session.getAgentId())
                || !Objects.equals(locked.getOrigin(), session.getOrigin())
                || (locked.getExpiresAt() != null && !locked.getExpiresAt().isAfter(LocalDateTime.now()))) {
            throw new PlatformEmbedTokenException("embed session changed during history operation");
        }
    }

    private long eventEpoch(PlatformEmbedChatEventEntity row) {
        try {
            Map<String, Object> payload = objectMapper.readValue(row.getPayloadJson(), new TypeReference<>() {});
            Object value = payload.get(EPOCH);
            return value instanceof Number number ? number.longValue() : 0L;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private PlatformEmbedChatEventEntity insert(PlatformEmbedSessionEntity session,
                        String eventType,
                        String role,
                        String content,
                        Map<String, Object> payload,
                        String traceId) {
        PlatformEmbedChatEventEntity entity = new PlatformEmbedChatEventEntity();
        entity.setSessionId(session.getSessionId());
        entity.setEventType(eventType);
        entity.setRole(role);
        entity.setContent(content);
        entity.setPayloadJson(writeJson(payload));
        entity.setTraceId(StringUtils.hasText(traceId) ? traceId : null);
        entity.setCreatedAt(LocalDateTime.now());
        if (mapper.insert(entity) != 1 || entity.getId() == null) throw new IllegalStateException("embed audit insert failed");
        return entity;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    public record HistoryMessage(String id, String role, String text, LocalDateTime createdAt) { }
    public record HistoryPage(List<HistoryMessage> messages, String nextBeforeId, String epoch) { }
    public static final class HistoryClearPendingException extends IllegalStateException {
        public HistoryClearPendingException() { super("embed context clear is pending"); }
    }
    public static final class InteractionNotReadyException extends IllegalStateException {
        public InteractionNotReadyException() { super("interaction is not in the current context"); }
    }
    public static final class HistoryEpochChangedException extends IllegalStateException {
        public HistoryEpochChangedException() { super("embed history epoch changed"); }
    }
}
