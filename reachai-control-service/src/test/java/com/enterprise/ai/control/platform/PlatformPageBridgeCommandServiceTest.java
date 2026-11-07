package com.enterprise.ai.control.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class PlatformPageBridgeCommandServiceTest {

    @Test
    void legacyRuntimeRequestWithoutTraceRemainsReadable() throws Exception {
        var request = new ObjectMapper().readValue("""
                {"sessionId":"s1","projectCode":"erp","agentId":"bot","targetPageKey":"orders",
                 "actionKey":"orders.open","args":{},"confirmRequired":true,
                 "confirmationTimeoutMs":2000,"executionTimeoutMs":2000,
                 "identitySource":"EMBED_SESSION","identityTenantId":"tenant-a","identityUserId":"user-a"}
                """, PlatformPageBridgeCommandService.PageBridgeExecutionRequest.class);
        assertEquals(null, request.supervisorTraceId());
        assertTrue(request.confirmRequired());
    }

    @Test
    void traceCorrelationIsScopedToVerifiedSessionAndPreservesUniqueBridgeGroups() {
        var sessions = mock(PlatformEmbedSessionMapper.class);
        var events = mock(PlatformPageActionEventMapper.class);
        var session = new PlatformEmbedSessionEntity();
        session.setSessionId("embed-1"); session.setTenantId("tenant-a"); session.setExternalUserId("user-a");
        session.setProjectCode("erp"); session.setAgentId("bot"); session.setPageKey("orders");
        session.setPageInstanceId("page-1"); session.setStatus("ACTIVE");
        when(sessions.selectOne(any())).thenReturn(session);
        var created = new java.util.ArrayList<PlatformPageActionEventEntity>();
        when(events.insert(any())).thenAnswer(invocation -> {
            PlatformPageActionEventEntity event = invocation.getArgument(0);
            event.setId((long) created.size() + 1); event.setStatus("USER_CANCELLED");
            created.add(event); return 1;
        });
        when(events.selectById(any())).thenAnswer(invocation ->
                created.get(((Number) invocation.getArgument(0)).intValue() - 1));
        var service = new PlatformPageBridgeCommandService(sessions, events, new ObjectMapper(), activeCatalog());
        String trace = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        for (String id : List.of(trace, trace, "bad-trace")) {
            service.execute(new PlatformPageBridgeCommandService.PageBridgeExecutionRequest(
                    "embed-1", "erp", "bot", "orders", "orders", "/orders", "change", Map.of(),
                    false, 2_000, 2_000, "EMBED_SESSION", "tenant-a", "user-a", id));
        }
        assertEquals(3, created.size());
        assertTrue(created.get(0).getParentRequestId().matches("bridge-t1-" + trace + "-[0-9a-f]{32}"));
        assertEquals(75, created.get(0).getParentRequestId().length());
        assertTrue(!created.get(0).getParentRequestId().equals(created.get(1).getParentRequestId()));
        assertTrue(created.get(2).getParentRequestId().matches("bridge-[0-9a-f-]{36}"));
        var forbidden = service.execute(new PlatformPageBridgeCommandService.PageBridgeExecutionRequest(
                "embed-1", "erp", "bot", "orders", "orders", "/orders", "change", Map.of(),
                false, 2_000, 2_000, "EMBED_SESSION", "tenant-b", "user-a", trace));
        assertEquals("PAGE_BRIDGE_IDENTITY_MISMATCH", forbidden.code());
        assertEquals(3, created.size());
    }

    @Test
    void catalogConfirmationIsTheMinimumForEveryRealBridgeCommand() {
        // The GraphSpec's missing and explicit false both arrive as false here.
        for (boolean catalogRequires : List.of(false, true)) {
            for (boolean graphRequires : List.of(false, true)) {
                var sessions = mock(PlatformEmbedSessionMapper.class);
                var events = mock(PlatformPageActionEventMapper.class);
                var session = new PlatformEmbedSessionEntity();
                session.setSessionId("embed-1"); session.setTenantId("tenant-a"); session.setExternalUserId("user-a");
                session.setProjectCode("erp"); session.setAgentId("bot"); session.setPageKey("orders");
                session.setPageInstanceId("page-1"); session.setStatus("ACTIVE");
                when(sessions.selectOne(any())).thenReturn(session);
                var catalog = activeCatalog();
                var action = catalog.findAction("erp", "orders", "change").orElseThrow();
                when(action.confirmRequired()).thenReturn(catalogRequires);
                var created = new java.util.ArrayList<PlatformPageActionEventEntity>();
                when(events.insert(any())).thenAnswer(invocation -> {
                    PlatformPageActionEventEntity event = invocation.getArgument(0);
                    created.add(event); event.setId(1L); event.setStatus("USER_CANCELLED"); return 1;
                });
                when(events.selectById(1L)).thenAnswer(invocation -> created.get(0));
                var result = new PlatformPageBridgeCommandService(sessions, events, new ObjectMapper(), catalog)
                        .execute(new PlatformPageBridgeCommandService.PageBridgeExecutionRequest(
                                "embed-1", "erp", "bot", "orders", "orders", "/orders", "change", Map.of(),
                                graphRequires, 2_000, 2_000, "EMBED_SESSION", "tenant-a", "user-a"));
                assertEquals(1, created.size());
                assertEquals(catalogRequires || graphRequires, created.get(0).getConfirmRequired());
                assertEquals("PAGE_ACTION", created.get(0).getCommandType());
                assertTrue(result.success()); // USER_CANCELLED is a business terminal result, not execution success.
                assertEquals("USER_CANCELLED", result.status());
            }
        }
    }

    @Test
    void missingInactiveOrUnauthorizedCatalogEntriesCannotEnqueue() {
        var sessions = mock(PlatformEmbedSessionMapper.class);
        var events = mock(PlatformPageActionEventMapper.class);
        var session = new PlatformEmbedSessionEntity();
        session.setSessionId("embed-1"); session.setTenantId("tenant-a"); session.setExternalUserId("user-a");
        session.setProjectCode("erp"); session.setAgentId("bot"); session.setPageKey("orders");
        session.setPageInstanceId("page-1"); session.setStatus("ACTIVE");
        when(sessions.selectOne(any())).thenReturn(session);
        var request = new PlatformPageBridgeCommandService.PageBridgeExecutionRequest(
                "embed-1", "erp", "bot", "orders", "orders", "/orders", "change", Map.of(),
                false, 2_000, 2_000, "EMBED_SESSION", "tenant-a", "user-a");
        var catalog = activeCatalog();
        var action = catalog.findAction("erp", "orders", "change").orElseThrow();
        var page = catalog.findPageForAction(1L).orElseThrow();
        var service = new PlatformPageBridgeCommandService(sessions, events, new ObjectMapper(), catalog);
        when(action.allowedAgentIds()).thenReturn(List.of("another-agent"));
        assertEquals("PAGE_BRIDGE_ACTION_FORBIDDEN", service.execute(request).code());
        when(action.allowedAgentIds()).thenReturn(List.of());
        when(action.status()).thenReturn("DISABLED");
        assertEquals("PAGE_BRIDGE_ACTION_UNAVAILABLE", service.execute(request).code());
        when(action.status()).thenReturn("ACTIVE");
        when(page.lifecycleStatus()).thenReturn("ARCHIVED");
        assertEquals("PAGE_BRIDGE_ACTION_UNAVAILABLE", service.execute(request).code());
        when(catalog.findAction("erp", "orders", "change")).thenReturn(java.util.Optional.empty());
        assertEquals("PAGE_BRIDGE_ACTION_UNAVAILABLE", service.execute(request).code());
        verifyNoInteractions(events);
    }

    @Test
    void crossPageNavigationRequiresConfirmationBeforeTheTargetAction() {
        var sessions = mock(PlatformEmbedSessionMapper.class);
        var events = mock(PlatformPageActionEventMapper.class);
        var session = new PlatformEmbedSessionEntity();
        session.setSessionId("embed-1"); session.setTenantId("tenant-a"); session.setExternalUserId("user-a");
        session.setProjectCode("erp"); session.setAgentId("bot"); session.setPageKey("orders");
        session.setPageInstanceId("page-1"); session.setStatus("ACTIVE");
        when(sessions.selectOne(any())).thenReturn(session);
        var created = new java.util.ArrayList<PlatformPageActionEventEntity>();
        when(events.insert(any())).thenAnswer(invocation -> {
            PlatformPageActionEventEntity event = invocation.getArgument(0);
            created.add(event);
            event.setId(1L);
            event.setStatus("USER_CANCELLED");
            return 1;
        });
        when(events.selectById(any())).thenAnswer(invocation -> created.get(0));
        var catalog = activeCatalog();
        when(catalog.findAction("erp", "products", "change").orElseThrow().confirmRequired()).thenReturn(true);
        var service = new PlatformPageBridgeCommandService(sessions, events, new ObjectMapper(), catalog);
        var result = service.execute(new PlatformPageBridgeCommandService.PageBridgeExecutionRequest(
                "embed-1", "erp", "bot", "orders", "products", "/products", "change", Map.of(), false,
                2_000, 30_000, "EMBED_SESSION", "tenant-a", "user-a"));
        assertEquals("PAGE_BRIDGE_NAVIGATION_FAILED", result.code());
        assertEquals(1, created.size());
        assertEquals(PlatformPageBridgeCommandService.NAVIGATE_ACTION, created.get(0).getActionKey());
        assertEquals(Boolean.TRUE, created.get(0).getConfirmRequired());
    }

    @Test
    void cannotDispatchPageActionToAnotherUsersOrTenantsSession() {
        var sessions = mock(PlatformEmbedSessionMapper.class);
        var events = mock(PlatformPageActionEventMapper.class);
        var session = new PlatformEmbedSessionEntity();
        session.setSessionId("embed-1"); session.setTenantId("tenant-a"); session.setExternalUserId("Alice");
        session.setProjectCode("erp"); session.setAgentId("bot"); session.setPageKey("orders"); session.setStatus("ACTIVE");
        when(sessions.selectOne(any())).thenReturn(session);
        var service = new PlatformPageBridgeCommandService(sessions, events, new ObjectMapper(), activeCatalog());
        for (var identity : java.util.List.of(
                java.util.List.of("EMBED_SESSION", "tenant-b", "Alice"),
                java.util.List.of("EMBED_SESSION", "tenant-a", "alice"),
                java.util.List.of("AGENT", "tenant-a", "Alice"),
                java.util.List.of("", "", ""))) {
            var result = service.execute(new PlatformPageBridgeCommandService.PageBridgeExecutionRequest(
                    "embed-1", "erp", "bot", "orders", "orders", "/orders", "open", Map.of(), false,
                    2_000, 2_000, identity.get(0), identity.get(1), identity.get(2)));
            assertEquals("PAGE_BRIDGE_IDENTITY_MISMATCH", result.code());
        }
        verifyNoInteractions(events);
    }

    @Test
    void pureChatSessionRejectsPageExecutionWithoutEnqueuingUnreachableNavigation() {
        PlatformEmbedSessionMapper sessions = mock(PlatformEmbedSessionMapper.class);
        PlatformPageActionEventMapper events = mock(PlatformPageActionEventMapper.class);
        PlatformEmbedSessionEntity session = new PlatformEmbedSessionEntity();
        session.setSessionId("embed-global"); session.setTenantId("default"); session.setExternalUserId("user-1");
        session.setProjectCode("qmssmp");
        session.setAgentId("team-agent");
        session.setPageInstanceId("chat-only");
        session.setRoute("/");
        session.setStatus("ACTIVE");
        when(sessions.selectOne(any())).thenReturn(session);
        var service = new PlatformPageBridgeCommandService(sessions, events, new ObjectMapper(), activeCatalog());
        for (String pageKey : new String[]{null, "", "  "}) {
            session.setPageKey(pageKey);
            var result = service.execute(new PlatformPageBridgeCommandService.PageBridgeExecutionRequest(
                    "embed-global", "qmssmp", "team-agent", null, "teamArchive.list",
                    "/team-build/depart-management", "dictionary.showChildren", Map.of("parentId", "DT0000000008"),
                    false, 2_000, 30_000, "EMBED_SESSION", "default", "user-1"));
            assertTrue(!result.success());
            assertEquals("PAGE_BRIDGE_PAGE_NOT_BOUND", result.code());
            assertTrue(result.status().contains("打开目标业务页面"));
            assertTrue(result.phases().isEmpty());
        }
        verifyNoInteractions(events);
    }

    @Test
    void resolvesOnlyTheTrustedActiveEmbedContextForMatchingWorkflowProject() {
        PlatformEmbedSessionMapper sessionMapper = mock(PlatformEmbedSessionMapper.class);
        PlatformEmbedSessionEntity session = new PlatformEmbedSessionEntity();
        session.setSessionId("embed-1"); session.setTenantId("default"); session.setExternalUserId("user-1");
        session.setProjectCode("qmssmp");
        session.setAgentId("team-agent");
        session.setPageKey("teamArchive.list");
        session.setPageInstanceId("team-page-1");
        session.setRoute("/team-build/depart-management");
        session.setStatus("ACTIVE");
        when(sessionMapper.selectOne(any())).thenReturn(session);

        PlatformPageBridgeCommandService service = new PlatformPageBridgeCommandService(
                sessionMapper,
                mock(PlatformPageActionEventMapper.class),
                new ObjectMapper(), activeCatalog());

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
        PlatformPageActionEventMapper eventMapper = mock(PlatformPageActionEventMapper.class);
        PlatformEmbedSessionEntity session = new PlatformEmbedSessionEntity();
        session.setSessionId("embed-1"); session.setTenantId("default"); session.setExternalUserId("user-1");
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
                event.setResultJson("{\"data\":{\"pageInstanceId\":\"team-page-1\",\"pageKey\":\"teamArchive.list\","
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
                sessionMapper, eventMapper, new ObjectMapper(), activeCatalog());
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
                        2_000, "EMBED_SESSION", "default", "user-1"));
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertTrue(result.success());
        assertEquals("PAGE_BRIDGE_COMPLETED", result.code());
        assertEquals(3, result.phases().size());
        assertEquals("team-page-1", session.getPageInstanceId());
        assertTrue(elapsedMs < 1_000, "target rebinding should be observed without a timeout wait");
    }

    @Test
    void distinguishesUnconfirmedPageActionFromAnUnavailableAction() {
        for (String status : List.of("TIMEOUT", "FAILED", "ACTION_NOT_FOUND", "TIMEOUT_BEFORE_CLAIM")) {
            PlatformEmbedSessionMapper sessions = mock(PlatformEmbedSessionMapper.class);
            PlatformPageActionEventMapper events = mock(PlatformPageActionEventMapper.class);
            PlatformEmbedSessionEntity session = new PlatformEmbedSessionEntity();
            session.setSessionId("embed-1"); session.setTenantId("default"); session.setExternalUserId("user-1");
            session.setProjectCode("qmssmp"); session.setAgentId("team-agent");
            session.setPageKey("teamArchive.list"); session.setPageInstanceId("page-1"); session.setStatus("ACTIVE");
            when(sessions.selectOne(any())).thenReturn(session);
            when(events.insert(any())).thenAnswer(invocation -> {
                PlatformPageActionEventEntity event = invocation.getArgument(0);
                event.setId(1L);
                return 1;
            });
            PlatformPageActionEventEntity completed = new PlatformPageActionEventEntity();
            completed.setId(1L); completed.setStatus("TIMEOUT_BEFORE_CLAIM".equals(status) ? "TIMEOUT" : status);
            if ("TIMEOUT_BEFORE_CLAIM".equals(status)) {
                completed.setErrorMessage("Page Bridge command was not claimed before its wait timeout");
            }
            when(events.selectById(1L)).thenReturn(completed);
            var result = new PlatformPageBridgeCommandService(sessions, events, new ObjectMapper(), activeCatalog()).execute(
                    new PlatformPageBridgeCommandService.PageBridgeExecutionRequest(
                            "embed-1", "qmssmp", "team-agent", "teamArchive.list", "teamArchive.list",
                            null, "setEnabled", Map.of(), false, 2_000, 2_000,
                            "EMBED_SESSION", "default", "user-1"));
            assertFalse(result.success());
            assertEquals(("ACTION_NOT_FOUND".equals(status) || "TIMEOUT_BEFORE_CLAIM".equals(status)) ? "PAGE_BRIDGE_ACTION_FAILED"
                    : "PAGE_BRIDGE_RESULT_UNCONFIRMED", result.code());
        }
    }

    @Test
    void preservesBusinessTerminalPageActionOutcomeInsteadOfReportingBridgeFailure() {
        PlatformEmbedSessionMapper sessionMapper = mock(PlatformEmbedSessionMapper.class);
        PlatformPageActionEventMapper eventMapper = mock(PlatformPageActionEventMapper.class);
        PlatformEmbedSessionEntity session = new PlatformEmbedSessionEntity();
        session.setSessionId("embed-1"); session.setTenantId("default"); session.setExternalUserId("user-1");
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
                eventMapper,
                new ObjectMapper(), activeCatalog());

        PlatformPageBridgeCommandService.PageBridgeExecutionResponse result = service.execute(
                new PlatformPageBridgeCommandService.PageBridgeExecutionRequest(
                        "embed-1", "qmssmp", "team-agent", "teamArchive.list",
                        "teamArchive.list", null, "qmssmp.teamArchive.open", Map.of(), false,
                        2_000, 2_000, "EMBED_SESSION", "default", "user-1"));

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
        session.setSessionId("embed-confirm"); session.setTenantId("default"); session.setExternalUserId("user-1");
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
                eventMapper,
                new ObjectMapper(), activeCatalog());

        PlatformPageBridgeCommandService.PageBridgeExecutionResponse result = service.execute(
                new PlatformPageBridgeCommandService.PageBridgeExecutionRequest(
                        "embed-confirm", "qmssmp", "team-agent", "teamArchive.list",
                        "teamArchive.list", null, "setEnabled", Map.of("enabled", true), true,
                        2_000, 2_000, "EMBED_SESSION", "default", "user-1"));

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
        session.setSessionId("embed-phased-timeout"); session.setTenantId("default"); session.setExternalUserId("user-1");
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
                eventMapper,
                new ObjectMapper(), activeCatalog());
        long startedAt = System.nanoTime();
        PlatformPageBridgeCommandService.PageBridgeExecutionResponse result = service.execute(
                new PlatformPageBridgeCommandService.PageBridgeExecutionRequest(
                        "embed-phased-timeout", "qmssmp", "team-agent", "teamArchive.list",
                        "teamArchive.list", null, "setEnabled", Map.of("enabled", true), true,
                        1_000, 1_000, "EMBED_SESSION", "default", "user-1"));
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertTrue(result.success());
        assertEquals("PAGE_BRIDGE_COMPLETED", result.code());
        assertTrue(elapsedMs >= 1_000,
                "execution time after confirmation must not consume the confirmation wait budget");
        assertTrue(elapsedMs < 2_500);
    }
    private static com.enterprise.ai.control.pageworkbench.application.PageCatalogApplicationService activeCatalog() {
        var catalog = mock(com.enterprise.ai.control.pageworkbench.application.PageCatalogApplicationService.class);
        var action = mock(com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.ActionView.class);
        var page = mock(com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageView.class);
        when(action.id()).thenReturn(1L);
        when(action.status()).thenReturn("ACTIVE");
        when(page.lifecycleStatus()).thenReturn("ACTIVE");
        when(catalog.findAction(any(), any(), any())).thenReturn(java.util.Optional.of(action));
        when(catalog.findPageForAction(1L)).thenReturn(java.util.Optional.of(page));
        return catalog;
    }

}
