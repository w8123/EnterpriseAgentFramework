package com.enterprise.ai.control.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformPageBridgeCommandServiceTest {

    @Test
    void resolvesOnlyTheTrustedActiveEmbedContextForMatchingWorkflowProject() {
        PlatformEmbedSessionMapper sessionMapper = mock(PlatformEmbedSessionMapper.class);
        PlatformEmbedSessionEntity session = new PlatformEmbedSessionEntity();
        session.setSessionId("embed-1");
        session.setProjectCode("qmssmp");
        session.setAgentId("team-agent");
        session.setPageKey("teamArchive.list");
        session.setPageInstanceId("team-page-1");
        session.setRoute("/team-build/depart-management");
        session.setStatus("ACTIVE");
        when(sessionMapper.selectOne(any())).thenReturn(session);

        PlatformPageBridgeCommandService service = new PlatformPageBridgeCommandService(
                sessionMapper,
                mock(PlatformEmbedSessionService.class),
                mock(PlatformPageActionEventMapper.class),
                new ObjectMapper());

        PlatformPageBridgeCommandService.PageBridgeContextResolution resolved = service.resolveContext(
                new PlatformPageBridgeCommandService.PageBridgeContextResolutionRequest("embed-1", "qmssmp"));
        PlatformPageBridgeCommandService.PageBridgeContextResolution mismatched = service.resolveContext(
                new PlatformPageBridgeCommandService.PageBridgeContextResolutionRequest("embed-1", "other-project"));

        assertTrue(resolved.resolved());
        assertEquals("team-agent", resolved.agentId());
        assertEquals("teamArchive.list", resolved.currentPageKey());
        assertTrue(!mismatched.resolved());
        assertEquals("PAGE_BRIDGE_SESSION_MISMATCH", mismatched.code());
    }

    @Test
    void waitsForTheTargetPageRebindInsteadOfTrustingNavigationAckPayload() {
        PlatformEmbedSessionMapper sessionMapper = mock(PlatformEmbedSessionMapper.class);
        PlatformEmbedSessionService sessionService = mock(PlatformEmbedSessionService.class);
        PlatformPageActionEventMapper eventMapper = mock(PlatformPageActionEventMapper.class);
        PlatformEmbedSessionEntity session = new PlatformEmbedSessionEntity();
        session.setSessionId("embed-1");
        session.setProjectCode("qmssmp");
        session.setAgentId("team-agent");
        session.setPageKey("qmssmp.global");
        session.setPageInstanceId("global-page-1");
        session.setRoute("/home");
        session.setStatus("ACTIVE");
        when(sessionMapper.selectOne(any())).thenAnswer(invocation -> session);

        AtomicLong ids = new AtomicLong();
        Map<Long, PlatformPageActionEventEntity> events = new LinkedHashMap<>();
        when(eventMapper.insert(any())).thenAnswer(invocation -> {
            PlatformPageActionEventEntity event = invocation.getArgument(0);
            long id = ids.incrementAndGet();
            event.setId(id);
            event.setStatus("SUCCESS");
            if (PlatformPageBridgeCommandService.NAVIGATE_ACTION.equals(event.getActionKey())) {
                event.setResultJson("{\"data\":{\"pageKey\":\"teamArchive.list\","
                        + "\"route\":\"/team-build/depart-management\",\"ready\":true}}");
                session.setPageKey("teamArchive.list");
                session.setPageInstanceId("team-page-1");
                session.setRoute("/team-build/depart-management");
            } else {
                event.setResultJson("{\"data\":{\"matched\":true,\"record\":{\"id\":\"team-1\"}}}");
            }
            events.put(id, event);
            return 1;
        });
        when(eventMapper.selectById(any())).thenAnswer(invocation ->
                events.get(((Number) invocation.getArgument(0)).longValue()));
        PlatformPageBridgeCommandService service = new PlatformPageBridgeCommandService(
                sessionMapper, sessionService, eventMapper, new ObjectMapper());
        long startedAt = System.nanoTime();
        PlatformPageBridgeCommandService.PageBridgeExecutionResponse result = service.execute(
                new PlatformPageBridgeCommandService.PageBridgeExecutionRequest(
                        "embed-1",
                        "qmssmp",
                        "team-agent",
                        "qmssmp.global",
                        "teamArchive.list",
                        "/team-build/depart-management",
                        "qmssmp.teamArchive.queryFirst",
                        Map.of("pageIndex", 1, "pageSize", 10),
                        false,
                        2_000,
                        2_000));
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertTrue(result.success());
        assertEquals("PAGE_BRIDGE_COMPLETED", result.code());
        assertEquals(3, result.phases().size());
        assertEquals("team-page-1", session.getPageInstanceId());
        assertTrue(elapsedMs < 1_000, "target rebinding should be observed without a timeout wait");
    }

    @Test
    void preservesBusinessTerminalPageActionOutcomeInsteadOfReportingBridgeFailure() {
        PlatformEmbedSessionMapper sessionMapper = mock(PlatformEmbedSessionMapper.class);
        PlatformPageActionEventMapper eventMapper = mock(PlatformPageActionEventMapper.class);
        PlatformEmbedSessionEntity session = new PlatformEmbedSessionEntity();
        session.setSessionId("embed-1");
        session.setProjectCode("qmssmp");
        session.setAgentId("team-agent");
        session.setPageKey("teamArchive.list");
        session.setPageInstanceId("team-page-1");
        session.setStatus("ACTIVE");
        when(sessionMapper.selectOne(any())).thenReturn(session);

        PlatformPageActionEventEntity event = new PlatformPageActionEventEntity();
        event.setId(1L);
        event.setStatus("PRECONDITION_FAILED");
        event.setResultJson("{\"message\":\"Select a team before opening details\","
                + "\"data\":{\"selectedCount\":0}}");
        when(eventMapper.insert(any())).thenAnswer(invocation -> {
            PlatformPageActionEventEntity requested = invocation.getArgument(0);
            requested.setId(1L);
            requested.setStatus("PRECONDITION_FAILED");
            requested.setResultJson(event.getResultJson());
            return 1;
        });
        when(eventMapper.selectById(1L)).thenReturn(event);

        PlatformPageBridgeCommandService service = new PlatformPageBridgeCommandService(
                sessionMapper,
                mock(PlatformEmbedSessionService.class),
                eventMapper,
                new ObjectMapper());

        PlatformPageBridgeCommandService.PageBridgeExecutionResponse result = service.execute(
                new PlatformPageBridgeCommandService.PageBridgeExecutionRequest(
                        "embed-1", "qmssmp", "team-agent", "teamArchive.list",
                        "teamArchive.list", null, "qmssmp.teamArchive.open", Map.of(), false,
                        2_000, 2_000));

        assertTrue(result.success());
        assertEquals("PAGE_BRIDGE_BUSINESS_TERMINAL", result.code());
        assertEquals("PRECONDITION_FAILED", result.status());
        assertTrue(result.data() instanceof Map<?, ?>);
        assertEquals(
                "Select a team before opening details",
                ((Map<?, ?>) result.data()).get("message"));
    }

    @Test
    void preservesVerifiedUserConfirmationWhenUnwrappingPageActionData() {
        PlatformEmbedSessionMapper sessionMapper = mock(PlatformEmbedSessionMapper.class);
        PlatformPageActionEventMapper eventMapper = mock(PlatformPageActionEventMapper.class);
        PlatformEmbedSessionEntity session = new PlatformEmbedSessionEntity();
        session.setSessionId("embed-confirm");
        session.setProjectCode("qmssmp");
        session.setAgentId("team-agent");
        session.setPageKey("teamArchive.list");
        session.setPageInstanceId("team-page-1");
        session.setStatus("ACTIVE");
        when(sessionMapper.selectOne(any())).thenReturn(session);

        PlatformPageActionEventEntity event = new PlatformPageActionEventEntity();
        event.setId(1L);
        event.setStatus("SUCCESS");
        event.setResultJson("{\"userConfirmed\":true,\"message\":\"Team enabled\","
                + "\"data\":{\"id\":\"team-1\",\"enabled\":true}}");
        when(eventMapper.insert(any())).thenAnswer(invocation -> {
            PlatformPageActionEventEntity requested = invocation.getArgument(0);
            requested.setId(1L);
            return 1;
        });
        when(eventMapper.selectById(1L)).thenReturn(event);

        PlatformPageBridgeCommandService service = new PlatformPageBridgeCommandService(
                sessionMapper,
                mock(PlatformEmbedSessionService.class),
                eventMapper,
                new ObjectMapper());

        PlatformPageBridgeCommandService.PageBridgeExecutionResponse result = service.execute(
                new PlatformPageBridgeCommandService.PageBridgeExecutionRequest(
                        "embed-confirm", "qmssmp", "team-agent", "teamArchive.list",
                        "teamArchive.list", null, "setEnabled", Map.of("enabled", true), true,
                        2_000, 2_000));

        assertTrue(result.success());
        assertEquals("PAGE_BRIDGE_COMPLETED", result.code());
        assertTrue(result.data() instanceof Map<?, ?>);
        Map<?, ?> data = (Map<?, ?>) result.data();
        assertEquals(true, data.get("userConfirmed"));
        assertEquals("Team enabled", data.get("message"));
        assertEquals(true, data.get("enabled"));
    }

    @Test
    void startsTheExecutionBudgetOnlyAfterUserConfirmation() {
        PlatformEmbedSessionMapper sessionMapper = mock(PlatformEmbedSessionMapper.class);
        PlatformPageActionEventMapper eventMapper = mock(PlatformPageActionEventMapper.class);
        PlatformEmbedSessionEntity session = new PlatformEmbedSessionEntity();
        session.setSessionId("embed-phased-timeout");
        session.setProjectCode("qmssmp");
        session.setAgentId("team-agent");
        session.setPageKey("teamArchive.list");
        session.setPageInstanceId("team-page-1");
        session.setStatus("ACTIVE");
        when(sessionMapper.selectOne(any())).thenReturn(session);

        PlatformPageActionEventEntity event = new PlatformPageActionEventEntity();
        event.setId(1L);
        event.setConfirmRequired(true);
        event.setStatus("REQUESTED");
        when(eventMapper.insert(any())).thenAnswer(invocation -> {
            PlatformPageActionEventEntity requested = invocation.getArgument(0);
            requested.setId(1L);
            requested.setConfirmRequired(true);
            return 1;
        });
        AtomicInteger reads = new AtomicInteger();
        when(eventMapper.selectById(1L)).thenAnswer(invocation -> {
            int read = reads.incrementAndGet();
            if (read <= 8) {
                event.setStatus("REQUESTED");
            } else if (read <= 12) {
                event.setStatus("EXECUTING");
            } else {
                event.setStatus("SUCCESS");
                event.setResultJson("{\"userConfirmed\":true,\"data\":{\"enabled\":true}}");
            }
            return event;
        });

        PlatformPageBridgeCommandService service = new PlatformPageBridgeCommandService(
                sessionMapper,
                mock(PlatformEmbedSessionService.class),
                eventMapper,
                new ObjectMapper());
        long startedAt = System.nanoTime();
        PlatformPageBridgeCommandService.PageBridgeExecutionResponse result = service.execute(
                new PlatformPageBridgeCommandService.PageBridgeExecutionRequest(
                        "embed-phased-timeout", "qmssmp", "team-agent", "teamArchive.list",
                        "teamArchive.list", null, "setEnabled", Map.of("enabled", true), true,
                        1_000, 1_000));
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertTrue(result.success());
        assertEquals("PAGE_BRIDGE_COMPLETED", result.code());
        assertTrue(elapsedMs >= 1_000,
                "execution time after confirmation must not consume the confirmation wait budget");
        assertTrue(elapsedMs < 2_500);
    }
}
