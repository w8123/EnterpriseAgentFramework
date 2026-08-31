package com.enterprise.ai.control.platform;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchObservationPort.ActionObservation;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchObservationPort.SessionObservation;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchObservationPort.TraceCandidate;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformPageWorkbenchObservationAdapterTest {

    @Test
    void mapsConversationAndTraceEvidenceToImmutableWorkbenchSnapshots() {
        PlatformEmbedSessionMapper sessionMapper =
                mock(PlatformEmbedSessionMapper.class);
        PlatformPageActionEventMapper actionMapper =
                mock(PlatformPageActionEventMapper.class);
        PlatformEmbedE2eEvidenceService evidence =
                mock(PlatformEmbedE2eEvidenceService.class);
        LocalDateTime observedAt = LocalDateTime.of(2026, 8, 25, 10, 0);
        when(evidence.latestSuccessfulConversation(
                "orders",
                "orders.detail",
                observedAt))
                .thenReturn(PlatformEmbedE2eEvidenceService
                        .EmbedConversationEvidence.passed(
                                "observed",
                                "session=session-1"));
        when(evidence.successfulTraceCandidates(
                "orders",
                "orders.detail",
                observedAt))
                .thenReturn(List.of(new PlatformEmbedE2eEvidenceService
                        .EmbedTraceCandidate(
                                "orders",
                                "orders.detail",
                                "session-1",
                                "page-1",
                                "trace-1",
                                observedAt,
                                true,
                                "list_card")));
        PlatformPageWorkbenchObservationAdapter adapter =
                new PlatformPageWorkbenchObservationAdapter(
                        sessionMapper,
                        actionMapper,
                        evidence);

        var conversation = adapter.latestSuccessfulConversation(
                "orders",
                "orders.detail",
                observedAt);
        List<TraceCandidate> traces = adapter.successfulTraceCandidates(
                "orders",
                "orders.detail",
                observedAt);

        assertTrue(conversation.passed());
        assertEquals("session=session-1", conversation.evidence());
        assertEquals(1, traces.size());
        assertEquals("trace-1", traces.get(0).traceId());
        assertEquals("list_card", traces.get(0).uiRequestComponent());
    }

    @Test
    void exposesOnlyUnexpiredActiveSessionSnapshot() {
        PlatformEmbedSessionMapper sessionMapper =
                mock(PlatformEmbedSessionMapper.class);
        PlatformPageActionEventMapper actionMapper =
                mock(PlatformPageActionEventMapper.class);
        PlatformEmbedE2eEvidenceService evidence =
                mock(PlatformEmbedE2eEvidenceService.class);
        LocalDateTime now = LocalDateTime.of(2026, 8, 25, 10, 0);
        PlatformEmbedSessionEntity session = new PlatformEmbedSessionEntity();
        session.setSessionId("session-1");
        session.setPageInstanceId("page-1");
        session.setSdkVersion("1.0.0");
        session.setRoute("/orders/1");
        session.setBridgeActionsJson("[\"getPageState\"]");
        session.setCreatedAt(now.minusMinutes(2));
        session.setUpdatedAt(now.minusMinutes(1));
        session.setExpiresAt(now.plusMinutes(10));
        when(sessionMapper.selectOne(any(Wrapper.class)))
                .thenReturn(session);
        PlatformPageWorkbenchObservationAdapter adapter =
                new PlatformPageWorkbenchObservationAdapter(
                        sessionMapper,
                        actionMapper,
                        evidence);

        SessionObservation observed = adapter.latestActiveSession(
                "orders",
                "orders.detail",
                now);

        assertEquals("session-1", observed.sessionId());
        assertEquals("page-1", observed.pageInstanceId());

        session.setExpiresAt(now.minusSeconds(1));
        assertNull(adapter.latestActiveSession(
                "orders",
                "orders.detail",
                now));
    }

    @Test
    void mapsLatestObservablePageActionWithoutLeakingPersistenceEntity() {
        PlatformEmbedSessionMapper sessionMapper =
                mock(PlatformEmbedSessionMapper.class);
        PlatformPageActionEventMapper actionMapper =
                mock(PlatformPageActionEventMapper.class);
        PlatformEmbedE2eEvidenceService evidence =
                mock(PlatformEmbedE2eEvidenceService.class);
        LocalDateTime completedAt = LocalDateTime.of(2026, 8, 25, 10, 5);
        PlatformPageActionEventEntity event =
                new PlatformPageActionEventEntity();
        event.setRequestId("request-1");
        event.setActionKey("getPageState");
        event.setStatus("SUCCESS");
        event.setCompletedAt(completedAt);
        when(actionMapper.selectOne(any(Wrapper.class))).thenReturn(event);
        PlatformPageWorkbenchObservationAdapter adapter =
                new PlatformPageWorkbenchObservationAdapter(
                        sessionMapper,
                        actionMapper,
                        evidence);

        ActionObservation observed = adapter.latestPageActionEvent(
                "session-1",
                "orders.detail");

        assertEquals("request-1", observed.requestId());
        assertEquals("getPageState", observed.actionKey());
        assertEquals("SUCCESS", observed.status());
        assertEquals(completedAt, observed.completedAt());
        assertNull(adapter.latestPageActionEvent(" ", "orders.detail"));
    }
}
