package com.enterprise.ai.control.platform;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Produces server-observed Embed conversation evidence without exposing message
 * content or user identity. This is shared by onboarding/readiness workflows.
 */
@Service
@RequiredArgsConstructor
public class PlatformEmbedE2eEvidenceService {

    private static final int SESSION_LOOKBACK_LIMIT = 20;

    private final PlatformEmbedSessionMapper sessionMapper;
    private final PlatformEmbedChatEventMapper eventMapper;

    public EmbedConversationEvidence latestSuccessfulConversation(
            String projectCode,
            LocalDateTime observedAfter) {
        return latestSuccessfulConversation(
                projectCode,
                null,
                observedAfter);
    }

    public EmbedConversationEvidence latestSuccessfulConversation(
            String projectCode,
            String pageKey,
            LocalDateTime observedAfter) {
        if (!StringUtils.hasText(projectCode) || observedAfter == null) {
            return EmbedConversationEvidence.pending(
                    "当前任务缺少可用的浏览器 Embed 观测窗口。");
        }

        String expectedPageKey = normalizeOptional(pageKey);
        List<PlatformEmbedSessionEntity> sessions = recentSessions(
                projectCode,
                expectedPageKey,
                observedAfter);
        if (sessions.isEmpty()) {
            return EmbedConversationEvidence.pending(
                    expectedPageKey == null
                            ? "AI Coding 任务启动后，尚未观察到当前项目创建 Embed 会话。"
                            : "AI Coding 任务启动后，尚未观察到当前页面创建 Embed 会话。");
        }

        List<String> sessionIds = sessions.stream()
                .map(PlatformEmbedSessionEntity::getSessionId)
                .filter(StringUtils::hasText)
                .toList();
        Map<String, MessageCounts> countsBySession = new HashMap<>();
        if (!sessionIds.isEmpty()) {
            List<PlatformEmbedChatEventEntity> events = eventMapper.selectList(
                    Wrappers.<PlatformEmbedChatEventEntity>lambdaQuery()
                            .in(PlatformEmbedChatEventEntity::getSessionId, sessionIds)
                            .eq(PlatformEmbedChatEventEntity::getEventType, "MESSAGE")
                            .in(PlatformEmbedChatEventEntity::getRole, List.of("user", "assistant"))
                            .ge(PlatformEmbedChatEventEntity::getCreatedAt, observedAfter));
            for (PlatformEmbedChatEventEntity event : events == null ? List.<PlatformEmbedChatEventEntity>of() : events) {
                MessageCounts counts = countsBySession.computeIfAbsent(
                        event.getSessionId(),
                        ignored -> new MessageCounts());
                if ("user".equals(event.getRole())) {
                    counts.userMessages++;
                } else if ("assistant".equals(event.getRole())) {
                    counts.assistantMessages++;
                }
            }
        }

        for (PlatformEmbedSessionEntity session : sessions) {
            MessageCounts counts = countsBySession.getOrDefault(
                    session.getSessionId(),
                    new MessageCounts());
            if (StringUtils.hasText(session.getSdkVersion())
                    && counts.userMessages > 0
                    && counts.assistantMessages > 0) {
                return EmbedConversationEvidence.passed(
                        "ReachAI 已观察到任务启动后的真实 SDK 会话、用户消息和助手回复。",
                        safeEvidence(session, counts));
            }
        }

        PlatformEmbedSessionEntity latest = sessions.get(0);
        MessageCounts latestCounts = countsBySession.getOrDefault(
                latest.getSessionId(),
                new MessageCounts());
        String blocker;
        if (!StringUtils.hasText(latest.getSdkVersion())) {
            blocker = "最近的 Embed 会话没有上报 SDK 版本。";
        } else if (latestCounts.userMessages == 0) {
            blocker = "最近的 Embed 会话尚未观察到用户消息。";
        } else {
            blocker = "最近的 Embed 会话尚未观察到助手回复。";
        }
        return EmbedConversationEvidence.pending(
                blocker + " 请完成真实的入口、会话和消息链路后重新验证。",
                safeEvidence(latest, latestCounts));
    }

    /**
     * Returns causal Runtime trace candidates observed from assistant replies
     * in an exact post-task page session. Message content and user identity are
     * deliberately excluded.
     */
    public List<EmbedTraceCandidate> successfulTraceCandidates(
            String projectCode,
            String pageKey,
            LocalDateTime observedAfter) {
        if (!StringUtils.hasText(projectCode)
                || !StringUtils.hasText(pageKey)
                || observedAfter == null) {
            return List.of();
        }
        String expectedPageKey = pageKey.trim();
        List<PlatformEmbedSessionEntity> sessions = recentSessions(
                projectCode,
                expectedPageKey,
                observedAfter);
        if (sessions.isEmpty()) {
            return List.of();
        }

        Map<String, PlatformEmbedSessionEntity> sessionsById = new HashMap<>();
        for (PlatformEmbedSessionEntity session : sessions) {
            if (StringUtils.hasText(session.getSessionId())
                    && StringUtils.hasText(session.getSdkVersion())) {
                sessionsById.put(session.getSessionId(), session);
            }
        }
        if (sessionsById.isEmpty()) {
            return List.of();
        }

        List<PlatformEmbedChatEventEntity> events = defaultList(
                eventMapper.selectList(
                        Wrappers.<PlatformEmbedChatEventEntity>lambdaQuery()
                                .in(
                                        PlatformEmbedChatEventEntity::getSessionId,
                                        sessionsById.keySet())
                                .eq(
                                        PlatformEmbedChatEventEntity::getEventType,
                                        "MESSAGE")
                                .in(
                                        PlatformEmbedChatEventEntity::getRole,
                                        List.of("user", "assistant"))
                                .ge(
                                        PlatformEmbedChatEventEntity::getCreatedAt,
                                        observedAfter)));
        Map<String, LocalDateTime> firstUserMessageAt = new HashMap<>();
        for (PlatformEmbedChatEventEntity event : events) {
            if ("user".equals(event.getRole())
                    && event.getCreatedAt() != null) {
                firstUserMessageAt.merge(
                        event.getSessionId(),
                        event.getCreatedAt(),
                        (left, right) -> left.isBefore(right) ? left : right);
            }
        }

        return events.stream()
                .filter(event -> "assistant".equals(event.getRole()))
                .filter(event -> StringUtils.hasText(event.getTraceId()))
                .filter(event -> event.getCreatedAt() != null)
                .filter(event -> {
                    LocalDateTime userAt = firstUserMessageAt.get(
                            event.getSessionId());
                    return userAt != null
                            && !event.getCreatedAt().isBefore(userAt);
                })
                .map(event -> {
                    PlatformEmbedSessionEntity session =
                            sessionsById.get(event.getSessionId());
                    return session == null
                            ? null
                            : new EmbedTraceCandidate(
                                    session.getProjectCode(),
                                    session.getPageKey(),
                                    session.getSessionId(),
                                    session.getPageInstanceId(),
                                    event.getTraceId().trim(),
                                    event.getCreatedAt());
                })
                .filter(candidate -> candidate != null
                        && expectedPageKey.equals(candidate.pageKey()))
                .sorted(Comparator.comparing(
                        EmbedTraceCandidate::observedAt).reversed())
                .limit(SESSION_LOOKBACK_LIMIT)
                .toList();
    }

    private List<PlatformEmbedSessionEntity> recentSessions(
            String projectCode,
            String expectedPageKey,
            LocalDateTime observedAfter) {
        return defaultList(sessionMapper.selectList(
                Wrappers.<PlatformEmbedSessionEntity>lambdaQuery()
                        .eq(
                                PlatformEmbedSessionEntity::getProjectCode,
                                projectCode.trim())
                        .eq(
                                expectedPageKey != null,
                                PlatformEmbedSessionEntity::getPageKey,
                                expectedPageKey)
                        .ge(
                                PlatformEmbedSessionEntity::getCreatedAt,
                                observedAfter)
                        .orderByDesc(
                                PlatformEmbedSessionEntity::getCreatedAt)
                        .last("LIMIT " + SESSION_LOOKBACK_LIMIT)))
                .stream()
                .filter(session -> expectedPageKey == null
                        || expectedPageKey.equals(session.getPageKey()))
                .toList();
    }

    private static String normalizeOptional(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String safeEvidence(
            PlatformEmbedSessionEntity session,
            MessageCounts counts) {
        return "sessionId=" + safe(session.getSessionId())
                + "; sdkVersion=" + safe(session.getSdkVersion())
                + "; pageKey=" + safe(session.getPageKey())
                + "; route=" + safe(session.getRoute())
                + "; createdAt=" + safe(session.getCreatedAt())
                + "; userMessages=" + counts.userMessages
                + "; assistantMessages=" + counts.assistantMessages;
    }

    private static String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static <T> List<T> defaultList(List<T> value) {
        return value == null ? List.of() : value;
    }

    public record EmbedConversationEvidence(
            boolean passed,
            String message,
            String evidence) {

        public static EmbedConversationEvidence passed(
                String message,
                String evidence) {
            return new EmbedConversationEvidence(true, message, evidence);
        }

        public static EmbedConversationEvidence pending(String message) {
            return pending(message, null);
        }

        public static EmbedConversationEvidence pending(
                String message,
                String evidence) {
            return new EmbedConversationEvidence(false, message, evidence);
        }
    }

    public record EmbedTraceCandidate(
            String projectCode,
            String pageKey,
            String sessionId,
            String pageInstanceId,
            String traceId,
            LocalDateTime observedAt) {
    }

    private static final class MessageCounts {
        private long userMessages;
        private long assistantMessages;
    }
}
