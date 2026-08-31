package com.enterprise.ai.control.pageworkbench.application;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Read-only observations supplied by the Platform embed runtime.
 *
 * <p>The Page Workbench owns the acceptance semantics while Platform owns the
 * session/action persistence. Immutable snapshots prevent Platform entities
 * and mappers from leaking into the Page Workbench application layer.</p>
 */
public interface PageWorkbenchObservationPort {

    ConversationEvidence latestSuccessfulConversation(
            String projectCode,
            String pageKey,
            LocalDateTime observedAfter);

    List<TraceCandidate> successfulTraceCandidates(
            String projectCode,
            String pageKey,
            LocalDateTime observedAfter);

    SessionObservation latestActiveSession(
            String projectCode,
            String pageKey,
            LocalDateTime now);

    ActionObservation latestPageActionEvent(
            String sessionId,
            String pageKey);

    ActionObservation latestWriteActionEvent(
            String sessionId,
            String pageKey,
            Set<String> actionKeys);

    record ConversationEvidence(
            boolean passed,
            String message,
            String evidence) {

        public static ConversationEvidence passed(
                String message,
                String evidence) {
            return new ConversationEvidence(true, message, evidence);
        }

        public static ConversationEvidence pending(String message) {
            return pending(message, null);
        }

        public static ConversationEvidence pending(
                String message,
                String evidence) {
            return new ConversationEvidence(false, message, evidence);
        }
    }

    record TraceCandidate(
            String projectCode,
            String pageKey,
            String sessionId,
            String pageInstanceId,
            String traceId,
            LocalDateTime observedAt,
            boolean uiRequestObserved,
            String uiRequestComponent) {

        public TraceCandidate(
                String projectCode,
                String pageKey,
                String sessionId,
                String pageInstanceId,
                String traceId,
                LocalDateTime observedAt) {
            this(
                    projectCode,
                    pageKey,
                    sessionId,
                    pageInstanceId,
                    traceId,
                    observedAt,
                    false,
                    null);
        }
    }

    record SessionObservation(
            String sessionId,
            String pageInstanceId,
            String sdkVersion,
            String route,
            String bridgeActionsJson,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime expiresAt) {
    }

    record ActionObservation(
            String requestId,
            String actionKey,
            String status,
            LocalDateTime completedAt) {
    }
}
