package com.enterprise.ai.control.platform;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/** Serializes clear admission with page-action claim on the same session row. */
@Service
@RequiredArgsConstructor
public class PlatformEmbedContextClearService {
    private final PlatformEmbedSessionService sessions;
    private final PlatformEmbedChatEventService history;
    private final PlatformPageActionEventMapper pageEvents;
    private final ObjectMapper objectMapper;

    @Transactional
    public long begin(String sessionId, PlatformEmbedTokenClaims claims) {
        PlatformEmbedSessionEntity session = sessions.requireActiveSessionForNavigationRebind(sessionId, claims);
        if (!Objects.equals(session.getPageInstanceId(), claims.getPageInstanceId())) {
            throw new PlatformEmbedTokenException("embed chat session does not match embed token");
        }
        var candidates = pageEvents.selectList(Wrappers.<PlatformPageActionEventEntity>lambdaQuery()
                .eq(PlatformPageActionEventEntity::getSessionId, sessionId)
                .and(query -> query.in(PlatformPageActionEventEntity::getStatus, "REQUESTED", "EXECUTING")
                        .or(navigation -> navigation.eq(PlatformPageActionEventEntity::getStatus, "SUCCESS")
                                .eq(PlatformPageActionEventEntity::getCommandType, "NAVIGATE")
                                .eq(PlatformPageActionEventEntity::getActionKey, PlatformPageBridgeCommandService.NAVIGATE_ACTION)
                                .eq(PlatformPageActionEventEntity::getTargetPageInstanceId, session.getPageInstanceId())))
                .last("LIMIT 10001"));
        if (candidates.size() > 10000) throw new IllegalStateException("too many pending page actions");
        for (PlatformPageActionEventEntity event : candidates) {
            if (!Objects.equals(event.getSessionId(), sessionId)) continue;
            if ("EXECUTING".equals(event.getStatus())) throw new PageActionBusyException();
            if (!"REQUESTED".equals(event.getStatus()) && !"SUCCESS".equals(event.getStatus())) {
                throw new IllegalStateException("invalid page action status during context clear");
            }
        }
        long marker = history.beginClear(session);
        for (PlatformPageActionEventEntity navigation : candidates) {
            if (!Objects.equals(navigation.getSessionId(), sessionId)) continue;
            if ("SUCCESS".equals(navigation.getStatus())
                    && (!"NAVIGATE".equals(navigation.getCommandType())
                    || !PlatformPageBridgeCommandService.NAVIGATE_ACTION.equals(navigation.getActionKey())
                    || !Objects.equals(navigation.getTargetPageInstanceId(), session.getPageInstanceId())
                    || navigationReady(navigation.getResultJson()))) continue;
            int updated = pageEvents.update(null, new UpdateWrapper<PlatformPageActionEventEntity>()
                    .eq("id", navigation.getId()).eq("status", navigation.getStatus())
                    .set("status", "USER_CANCELLED").set("error_message", "Conversation context was cleared")
                    .set("completed_at", LocalDateTime.now()));
            if (updated != 1) throw new IllegalStateException("page navigation changed during context clear");
        }
        return marker;
    }

    private boolean navigationReady(String result) {
        if (result == null) return false;
        try {
            JsonNode root = objectMapper.readTree(result);
            return root != null && root.path("data").path("ready").isBoolean()
                    && root.path("data").path("ready").booleanValue();
        } catch (Exception invalid) {
            return false;
        }
    }

    public static final class PageActionBusyException extends IllegalStateException {
        public PageActionBusyException() { super("page action is executing; its outcome is not known"); }
    }
}
