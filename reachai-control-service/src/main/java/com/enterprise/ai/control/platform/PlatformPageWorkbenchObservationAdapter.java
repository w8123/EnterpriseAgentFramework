package com.enterprise.ai.control.platform;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchObservationPort;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchObservationPort.ActionObservation;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchObservationPort.ConversationEvidence;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchObservationPort.SessionObservation;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchObservationPort.TraceCandidate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/** Platform-owned persistence adapter for Page Workbench acceptance evidence. */
@Component
@RequiredArgsConstructor
public class PlatformPageWorkbenchObservationAdapter
        implements PageWorkbenchObservationPort {

    private static final List<String> OBSERVABLE_ACTION_STATUSES = List.of(
            "SUCCESS",
            "NO_DATA",
            "PRECONDITION_FAILED",
            "USER_CANCELLED",
            "FAILED",
            "CANCELLED",
            "ACTION_NOT_FOUND",
            "FORBIDDEN",
            "TIMEOUT");

    private final PlatformEmbedSessionMapper sessionMapper;
    private final PlatformPageActionEventMapper actionEventMapper;
    private final PlatformEmbedE2eEvidenceService embedEvidence;

    @Override
    public ConversationEvidence latestSuccessfulConversation(
            String projectCode,
            String pageKey,
            LocalDateTime observedAfter) {
        PlatformEmbedE2eEvidenceService.EmbedConversationEvidence observed =
                embedEvidence.latestSuccessfulConversation(
                        projectCode,
                        pageKey,
                        observedAfter);
        return new ConversationEvidence(
                observed.passed(),
                observed.message(),
                observed.evidence());
    }

    @Override
    public List<TraceCandidate> successfulTraceCandidates(
            String projectCode,
            String pageKey,
            LocalDateTime observedAfter) {
        return embedEvidence.successfulTraceCandidates(
                        projectCode,
                        pageKey,
                        observedAfter)
                .stream()
                .map(candidate -> new TraceCandidate(
                        candidate.projectCode(),
                        candidate.pageKey(),
                        candidate.sessionId(),
                        candidate.pageInstanceId(),
                        candidate.traceId(),
                        candidate.observedAt(),
                        candidate.uiRequestObserved(),
                        candidate.uiRequestComponent()))
                .toList();
    }

    @Override
    public SessionObservation latestActiveSession(
            String projectCode,
            String pageKey,
            LocalDateTime now) {
        PlatformEmbedSessionEntity session = sessionMapper.selectOne(
                Wrappers.<PlatformEmbedSessionEntity>lambdaQuery()
                        .eq(
                                PlatformEmbedSessionEntity::getProjectCode,
                                projectCode)
                        .eq(PlatformEmbedSessionEntity::getPageKey, pageKey)
                        .eq(PlatformEmbedSessionEntity::getStatus, "ACTIVE")
                        .orderByDesc(PlatformEmbedSessionEntity::getUpdatedAt)
                        .last("LIMIT 1"));
        if (session == null
                || (session.getExpiresAt() != null
                && !session.getExpiresAt().isAfter(now))) {
            return null;
        }
        return new SessionObservation(
                session.getSessionId(),
                session.getPageInstanceId(),
                session.getSdkVersion(),
                session.getRoute(),
                session.getBridgeActionsJson(),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                session.getExpiresAt());
    }

    @Override
    public ActionObservation latestPageActionEvent(
            String sessionId,
            String pageKey) {
        if (!StringUtils.hasText(sessionId)) {
            return null;
        }
        PlatformPageActionEventEntity event = actionEventMapper.selectOne(
                Wrappers.<PlatformPageActionEventEntity>lambdaQuery()
                        .eq(
                                PlatformPageActionEventEntity::getSessionId,
                                sessionId)
                        .eq(
                                PlatformPageActionEventEntity::getCommandType,
                                "PAGE_ACTION")
                        .eq(
                                PlatformPageActionEventEntity::getTargetPageKey,
                                pageKey)
                        .in(
                                PlatformPageActionEventEntity::getStatus,
                                OBSERVABLE_ACTION_STATUSES)
                        .orderByDesc(
                                PlatformPageActionEventEntity::getCompletedAt)
                        .last("LIMIT 1"));
        return actionObservation(event);
    }

    @Override
    public ActionObservation latestWriteActionEvent(
            String sessionId,
            String pageKey,
            Set<String> actionKeys) {
        if (!StringUtils.hasText(sessionId)
                || actionKeys == null
                || actionKeys.isEmpty()) {
            return null;
        }
        PlatformPageActionEventEntity event = actionEventMapper.selectOne(
                Wrappers.<PlatformPageActionEventEntity>lambdaQuery()
                        .eq(
                                PlatformPageActionEventEntity::getSessionId,
                                sessionId)
                        .eq(
                                PlatformPageActionEventEntity::getCommandType,
                                "PAGE_ACTION")
                        .eq(
                                PlatformPageActionEventEntity::getTargetPageKey,
                                pageKey)
                        .in(
                                PlatformPageActionEventEntity::getActionKey,
                                actionKeys)
                        .in(
                                PlatformPageActionEventEntity::getStatus,
                                OBSERVABLE_ACTION_STATUSES)
                        .orderByDesc(
                                PlatformPageActionEventEntity::getCompletedAt)
                        .last("LIMIT 1"));
        return actionObservation(event);
    }

    private static ActionObservation actionObservation(
            PlatformPageActionEventEntity event) {
        if (event == null) {
            return null;
        }
        return new ActionObservation(
                event.getRequestId(),
                event.getActionKey(),
                event.getStatus(),
                event.getCompletedAt());
    }
}
