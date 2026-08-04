package com.enterprise.ai.control.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformPageBridgeCommandServiceTest {

    @Test
    void usesPageInstanceReturnedByNavigationWithoutWaitingForRegistryFallback() {
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
        when(sessionMapper.selectOne(any())).thenReturn(session);

        AtomicLong ids = new AtomicLong();
        Map<Long, PlatformPageActionEventEntity> events = new LinkedHashMap<>();
        when(eventMapper.insert(any())).thenAnswer(invocation -> {
            PlatformPageActionEventEntity event = invocation.getArgument(0);
            long id = ids.incrementAndGet();
            event.setId(id);
            event.setStatus("SUCCESS");
            if (PlatformPageBridgeCommandService.NAVIGATE_ACTION.equals(event.getActionKey())) {
                event.setResultJson("{\"data\":{\"pageKey\":\"teamArchive.list\","
                        + "\"pageInstanceId\":\"team-page-1\","
                        + "\"route\":\"/team-build/depart-management\",\"ready\":true}}");
            } else {
                event.setResultJson("{\"data\":{\"matched\":true,\"record\":{\"id\":\"team-1\"}}}");
            }
            events.put(id, event);
            return 1;
        });
        when(eventMapper.selectById(any())).thenAnswer(invocation ->
                events.get(((Number) invocation.getArgument(0)).longValue()));
        doAnswer(invocation -> {
            session.setPageKey(invocation.getArgument(1));
            session.setPageInstanceId(invocation.getArgument(2));
            session.setRoute(invocation.getArgument(3));
            return null;
        }).when(sessionService).updateBridge(any(), any(), any(), any());

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
                        2_000));
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertTrue(result.success());
        assertEquals("PAGE_BRIDGE_COMPLETED", result.code());
        assertEquals(3, result.phases().size());
        assertEquals("team-page-1", session.getPageInstanceId());
        assertTrue(elapsedMs < 1_000, "navigation result should avoid the registry wait fallback");
    }
}
