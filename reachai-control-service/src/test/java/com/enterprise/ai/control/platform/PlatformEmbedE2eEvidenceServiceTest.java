package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.platform.PlatformEmbedE2eEvidenceService.EmbedConversationEvidence;
import com.enterprise.ai.control.platform.PlatformEmbedE2eEvidenceService.EmbedTraceCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformEmbedE2eEvidenceServiceTest {

    @Test
    void passesOnlyAfterSdkSessionUserMessageAndAssistantResponseAreObserved() {
        PlatformEmbedSessionMapper sessionMapper =
                mock(PlatformEmbedSessionMapper.class);
        PlatformEmbedChatEventMapper eventMapper =
                mock(PlatformEmbedChatEventMapper.class);
        LocalDateTime startedAt = LocalDateTime.of(2026, 7, 26, 10, 0);
        PlatformEmbedSessionEntity session = session(
                "embed-session-1",
                "orders",
                "1.0.0-SNAPSHOT",
                startedAt.plusMinutes(1));
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(eventMapper.selectList(any())).thenReturn(List.of(
                event("embed-session-1", "user", startedAt.plusMinutes(2)),
                event("embed-session-1", "assistant", startedAt.plusMinutes(3))));

        EmbedConversationEvidence evidence =
                new PlatformEmbedE2eEvidenceService(
                        sessionMapper,
                        eventMapper,
                        new ObjectMapper())
                        .latestSuccessfulConversation("orders", startedAt);

        assertTrue(evidence.passed());
        assertTrue(evidence.evidence().contains("sdkVersion=1.0.0-SNAPSHOT"));
        assertTrue(evidence.evidence().contains("userMessages=1"));
        assertTrue(evidence.evidence().contains("assistantMessages=1"));
    }

    @Test
    void remainsPendingWhenNoAssistantResponseWasObserved() {
        PlatformEmbedSessionMapper sessionMapper =
                mock(PlatformEmbedSessionMapper.class);
        PlatformEmbedChatEventMapper eventMapper =
                mock(PlatformEmbedChatEventMapper.class);
        LocalDateTime startedAt = LocalDateTime.of(2026, 7, 26, 10, 0);
        PlatformEmbedSessionEntity session = session(
                "embed-session-2",
                "orders",
                "1.0.0-SNAPSHOT",
                startedAt.plusMinutes(1));
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(eventMapper.selectList(any())).thenReturn(List.of(
                event("embed-session-2", "user", startedAt.plusMinutes(2))));

        EmbedConversationEvidence evidence =
                new PlatformEmbedE2eEvidenceService(
                        sessionMapper,
                        eventMapper,
                        new ObjectMapper())
                        .latestSuccessfulConversation("orders", startedAt);

        assertFalse(evidence.passed());
        assertTrue(evidence.message().contains("尚未观察到助手回复"));
    }

    @Test
    void pageScopedObservationDoesNotAcceptAnotherPageSession() {
        PlatformEmbedSessionMapper sessionMapper =
                mock(PlatformEmbedSessionMapper.class);
        PlatformEmbedChatEventMapper eventMapper =
                mock(PlatformEmbedChatEventMapper.class);
        LocalDateTime startedAt = LocalDateTime.of(2026, 7, 26, 10, 0);
        PlatformEmbedSessionEntity session = session(
                "embed-session-other-page",
                "orders",
                "1.0.0-SNAPSHOT",
                startedAt.plusMinutes(1));
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));

        EmbedConversationEvidence evidence =
                new PlatformEmbedE2eEvidenceService(
                        sessionMapper,
                        eventMapper,
                        new ObjectMapper())
                        .latestSuccessfulConversation(
                                "orders",
                                "orders.detail",
                                startedAt);

        assertFalse(evidence.passed());
        assertTrue(evidence.message().contains("当前页面"));
    }

    @Test
    void traceCandidateComesFromAnExactSdkPageSessionAndAssistantReply() {
        PlatformEmbedSessionMapper sessionMapper =
                mock(PlatformEmbedSessionMapper.class);
        PlatformEmbedChatEventMapper eventMapper =
                mock(PlatformEmbedChatEventMapper.class);
        LocalDateTime startedAt = LocalDateTime.of(2026, 7, 26, 10, 0);
        PlatformEmbedSessionEntity session = session(
                "embed-session-trace",
                "orders",
                "1.0.0-SNAPSHOT",
                startedAt.plusMinutes(1));
        session.setPageKey("orders.detail");
        session.setPageInstanceId("page-instance");
        PlatformEmbedChatEventEntity assistant = event(
                "embed-session-trace",
                "assistant",
                startedAt.plusMinutes(3));
        assistant.setTraceId("trace-orders");
        assistant.setPayloadJson("{\"uiRequest\":{\"type\":\"PRESENT_OUTPUT\",\"component\":\"list_card\"}}");
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(eventMapper.selectList(any())).thenReturn(List.of(
                event(
                        "embed-session-trace",
                        "user",
                        startedAt.plusMinutes(2)),
                assistant));

        List<EmbedTraceCandidate> candidates =
                new PlatformEmbedE2eEvidenceService(
                        sessionMapper,
                        eventMapper,
                        new ObjectMapper())
                        .successfulTraceCandidates(
                                "orders",
                                "orders.detail",
                                startedAt);

        assertEquals(1, candidates.size());
        assertEquals("trace-orders", candidates.get(0).traceId());
        assertEquals("page-instance",
                candidates.get(0).pageInstanceId());
        assertTrue(candidates.get(0).uiRequestObserved());
        assertEquals("list_card",
                candidates.get(0).uiRequestComponent());
    }

    @Test
    void assistantTraceBeforeTheUserMessageIsNotCausalEvidence() {
        PlatformEmbedSessionMapper sessionMapper =
                mock(PlatformEmbedSessionMapper.class);
        PlatformEmbedChatEventMapper eventMapper =
                mock(PlatformEmbedChatEventMapper.class);
        LocalDateTime startedAt = LocalDateTime.of(2026, 7, 26, 10, 0);
        PlatformEmbedSessionEntity session = session(
                "embed-session-trace",
                "orders",
                "1.0.0-SNAPSHOT",
                startedAt.plusMinutes(1));
        session.setPageKey("orders.detail");
        PlatformEmbedChatEventEntity assistant = event(
                "embed-session-trace",
                "assistant",
                startedAt.plusMinutes(2));
        assistant.setTraceId("trace-orders");
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(eventMapper.selectList(any())).thenReturn(List.of(
                assistant,
                event(
                        "embed-session-trace",
                        "user",
                        startedAt.plusMinutes(3))));

        List<EmbedTraceCandidate> candidates =
                new PlatformEmbedE2eEvidenceService(
                        sessionMapper,
                        eventMapper,
                        new ObjectMapper())
                        .successfulTraceCandidates(
                                "orders",
                                "orders.detail",
                                startedAt);

        assertTrue(candidates.isEmpty());
    }

    private static PlatformEmbedSessionEntity session(
            String sessionId,
            String projectCode,
            String sdkVersion,
            LocalDateTime createdAt) {
        PlatformEmbedSessionEntity session = new PlatformEmbedSessionEntity();
        session.setSessionId(sessionId);
        session.setProjectCode(projectCode);
        session.setSdkVersion(sdkVersion);
        session.setPageKey("orders.list");
        session.setRoute("/orders");
        session.setCreatedAt(createdAt);
        return session;
    }

    private static PlatformEmbedChatEventEntity event(
            String sessionId,
            String role,
            LocalDateTime createdAt) {
        PlatformEmbedChatEventEntity event =
                new PlatformEmbedChatEventEntity();
        event.setSessionId(sessionId);
        event.setEventType("MESSAGE");
        event.setRole(role);
        event.setCreatedAt(createdAt);
        return event;
    }
}
