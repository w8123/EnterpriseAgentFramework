package com.enterprise.ai.control.platform;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.when;

class PlatformEmbedCatalogControllerTest {

    @Test
    void keepsHistoricalPageCatalogReadRouteOnControlService() throws Exception {
        Method method = PlatformEmbedCatalogController.class.getDeclaredMethod("listPages", String.class, int.class);
        GetMapping mapping = method.getAnnotation(GetMapping.class);

        assertArrayEquals(
                new String[]{"/api/platform/embed/pages", "/api/platform/embed/pages/catalog"},
                mapping.value());
    }

    @Test
    void listsRegisteredPagesForProjectCode() {
        PlatformPageRegistryMapper pageMapper = mock(PlatformPageRegistryMapper.class);
        PlatformPageActionRegistryMapper actionMapper = mock(PlatformPageActionRegistryMapper.class);
        PlatformEmbedCatalogController controller = controller(pageMapper, actionMapper);
        PlatformPageRegistryEntity page = new PlatformPageRegistryEntity();
        page.setId(11L);
        page.setProjectCode("bzjs12");
        page.setAppId("bzjs12");
        page.setPageKey("contract-list");
        page.setName("合同列表");
        page.setStatus("ACTIVE");
        page.setLastSeenAt(LocalDateTime.of(2026, 6, 30, 10, 0));
        when(pageMapper.selectList(any())).thenReturn(List.of(page));

        ResponseEntity<List<PlatformEmbedCatalogController.PageRegistryView>> response =
                controller.listPages("bzjs12", 200);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        assertEquals("contract-list", response.getBody().get(0).pageKey());
        verify(pageMapper).selectList(any());
    }

    @Test
    void deletesRegisteredPageAndControlOwnedEmbedArtifactsWithoutFallingThroughToRetiredProxy() {
        PlatformPageRegistryMapper pageMapper = mock(PlatformPageRegistryMapper.class);
        PlatformPageActionRegistryMapper actionMapper = mock(PlatformPageActionRegistryMapper.class);
        PlatformEmbedSessionMapper sessionMapper = mock(PlatformEmbedSessionMapper.class);
        PlatformPageActionEventMapper eventMapper = mock(PlatformPageActionEventMapper.class);
        PlatformEmbedChatEventMapper chatMapper = mock(PlatformEmbedChatEventMapper.class);
        PlatformEmbedCatalogController controller = new PlatformEmbedCatalogController(
                pageMapper,
                actionMapper,
                sessionMapper,
                eventMapper,
                chatMapper,
                mock(PlatformEmbedRendererMapper.class));
        PlatformPageRegistryEntity page = new PlatformPageRegistryEntity();
        page.setId(11L);
        page.setProjectCode("bzjs12");
        page.setAppId("bzjs12");
        page.setPageKey("contract-list");
        when(pageMapper.selectById(11L)).thenReturn(page);
        PlatformEmbedSessionEntity session = new PlatformEmbedSessionEntity();
        session.setSessionId("sess-1");
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(pageMapper.deleteById(11L)).thenReturn(1);
        when(actionMapper.delete(any())).thenReturn(2);
        when(sessionMapper.delete(any())).thenReturn(1);
        when(eventMapper.delete(any())).thenReturn(3);
        when(chatMapper.delete(any())).thenReturn(4);

        ResponseEntity<PlatformEmbedCatalogController.PageRegistryDeleteResult> response =
                controller.deletePageRegistry(11L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(11L, response.getBody().pageId());
        assertEquals("contract-list", response.getBody().pageKey());
        assertEquals(1, response.getBody().deletedPages());
        assertEquals(2, response.getBody().deletedActions());
        assertEquals(1, response.getBody().deletedEmbedSessions());
        assertEquals(3, response.getBody().deletedPageActionEvents());
        assertEquals(4, response.getBody().deletedEmbedChatEvents());
        verify(pageMapper).deleteById(11L);
    }

    @Test
    void listsRegisteredPageActionsForProjectCode() {
        PlatformPageRegistryMapper pageMapper = mock(PlatformPageRegistryMapper.class);
        PlatformPageActionRegistryMapper actionMapper = mock(PlatformPageActionRegistryMapper.class);
        PlatformEmbedCatalogController controller = controller(pageMapper, actionMapper);
        PlatformPageActionRegistryEntity action = new PlatformPageActionRegistryEntity();
        action.setId(21L);
        action.setProjectCode("bzjs12");
        action.setAppId("bzjs12");
        action.setPageKey("contract-list");
        action.setActionKey("search");
        action.setTitle("搜索");
        action.setDescription("按条件搜索合同");
        action.setConfirmRequired(false);
        action.setStatus("ACTIVE");
        when(actionMapper.selectList(any())).thenReturn(List.of(action));

        ResponseEntity<List<PlatformEmbedCatalogController.PageActionRegistryView>> response =
                controller.listPageActions("bzjs12", 500);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        assertEquals("search", response.getBody().get(0).actionKey());
        verify(actionMapper).selectList(any());
    }

    @Test
    void listsEmbedSessionsAndPageActionEventsWithoutFallingThroughToRetiredProxy() {
        PlatformEmbedSessionMapper sessionMapper = mock(PlatformEmbedSessionMapper.class);
        PlatformPageActionEventMapper eventMapper = mock(PlatformPageActionEventMapper.class);
        PlatformEmbedCatalogController controller = controller(sessionMapper, eventMapper);
        PlatformEmbedSessionEntity session = new PlatformEmbedSessionEntity();
        session.setId(31L);
        session.setSessionId("sess-1");
        session.setProjectCode("bzjs12");
        session.setAppId("bzjs12");
        session.setAgentId("agent-1");
        session.setExternalUserId("jsh");
        session.setPageInstanceId("page-1");
        session.setStatus("ACTIVE");
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        PlatformPageActionEventEntity event = new PlatformPageActionEventEntity();
        event.setId(41L);
        event.setRequestId("req-1");
        event.setSessionId("sess-1");
        event.setAppId("bzjs12");
        event.setAgentId("agent-1");
        event.setActionKey("search");
        event.setStatus("REQUESTED");
        when(eventMapper.selectList(any())).thenReturn(List.of(event));

        assertEquals("sess-1", controller.listSessions("bzjs12", null, null, null, 100).getBody().get(0).sessionId());
        assertEquals("req-1", controller.listPageActionEvents("sess-1", null, null, null, 100).getBody().get(0).requestId());
    }

    @Test
    void listsChatEventsAndManagesRenderersWithoutFallingThroughToRetiredProxy() {
        PlatformEmbedChatEventMapper chatMapper = mock(PlatformEmbedChatEventMapper.class);
        PlatformEmbedRendererMapper rendererMapper = mock(PlatformEmbedRendererMapper.class);
        PlatformEmbedCatalogController controller = controller(chatMapper, rendererMapper);
        PlatformEmbedChatEventEntity chat = new PlatformEmbedChatEventEntity();
        chat.setId(51L);
        chat.setSessionId("sess-1");
        chat.setEventType("message");
        chat.setRole("assistant");
        when(chatMapper.selectList(any())).thenReturn(List.of(chat));
        PlatformEmbedRendererEntity renderer = new PlatformEmbedRendererEntity();
        renderer.setId(61L);
        renderer.setAppId("bzjs12");
        renderer.setRendererKey("table");
        renderer.setName("Table");
        renderer.setVersion("1.0");
        renderer.setStatus("ACTIVE");
        when(rendererMapper.selectList(any())).thenReturn(List.of(renderer));
        when(rendererMapper.selectById(61L)).thenReturn(renderer);

        assertEquals("message", controller.listChatEvents("sess-1", 200).getBody().get(0).eventType());
        assertEquals("table", controller.listRenderers("bzjs12", null, null, 100).getBody().get(0).rendererKey());
        ResponseEntity<PlatformEmbedCatalogController.EmbedRendererView> created =
                controller.createRenderer(new PlatformEmbedCatalogController.EmbedRendererPayload(
                        "bzjs12",
                        "chart",
                        "Chart",
                        "1.0",
                        Map.of("type", "object"),
                        List.of("agent-1"),
                        "ACTIVE"));
        ResponseEntity<PlatformEmbedCatalogController.EmbedRendererView> updated =
                controller.updateRenderer(61L, new PlatformEmbedCatalogController.EmbedRendererPayload(
                        "bzjs12",
                        "table",
                        "Table v2",
                        "1.1",
                        Map.of(),
                        List.of(),
                        "ACTIVE"));
        ResponseEntity<Void> disabled = controller.disableRenderer(61L);

        assertEquals(HttpStatus.OK, created.getStatusCode());
        assertEquals("chart", created.getBody().rendererKey());
        assertEquals("1.1", updated.getBody().version());
        assertEquals(HttpStatus.OK, disabled.getStatusCode());
        verify(rendererMapper).insert(any());
        verify(rendererMapper, times(2)).updateById(any());
    }

    @Test
    void declaresAndDebugsPageActionCatalogWithoutFallingThroughToRetiredProxy() {
        PlatformPageRegistryMapper pageMapper = mock(PlatformPageRegistryMapper.class);
        PlatformPageActionRegistryMapper actionMapper = mock(PlatformPageActionRegistryMapper.class);
        PlatformEmbedSessionMapper sessionMapper = mock(PlatformEmbedSessionMapper.class);
        PlatformPageActionEventMapper eventMapper = mock(PlatformPageActionEventMapper.class);
        PlatformEmbedCatalogController controller = controller(pageMapper, actionMapper, sessionMapper, eventMapper);
        PlatformPageActionRegistryEntity action = new PlatformPageActionRegistryEntity();
        action.setId(21L);
        action.setProjectCode("bzjs12");
        action.setAppId("bzjs12");
        action.setPageKey("contract-list");
        action.setActionKey("search");
        action.setTitle("search");
        action.setStatus("ACTIVE");
        when(actionMapper.selectById(21L)).thenReturn(action);
        when(pageMapper.selectOne(any())).thenReturn(null);
        PlatformPageActionEventEntity debugEvent = new PlatformPageActionEventEntity();
        debugEvent.setRequestId("debug-1");
        debugEvent.setStatus("REQUESTED");
        when(eventMapper.selectOne(any())).thenReturn(debugEvent);

        ResponseEntity<PlatformEmbedCatalogController.PageActionManualDeclareResponse> declared =
                controller.declarePageActionCatalog(new PlatformEmbedCatalogController.PageActionManualDeclarePayload(
                        "bzjs12",
                        null,
                        "contract-list",
                        "Contract List",
                        "/contracts",
                        "search",
                        "Search",
                        "Search contracts",
                        false,
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        List.of(),
                        "ACTIVE"));
        ResponseEntity<PlatformEmbedCatalogController.PageActionDebugResponse> debug =
                controller.debugPageActionCatalog(21L, new PlatformEmbedCatalogController.PageActionDebugRequest(null, Map.of()));
        ResponseEntity<PlatformPageActionEventEntity> debugResult = controller.getPageActionDebugResult("debug-1");
        ResponseEntity<List<PlatformEmbedCatalogController.PageActionReferenceView>> references =
                controller.listPageActionReferences(21L);

        assertEquals(HttpStatus.OK, declared.getStatusCode());
        assertEquals("MANUAL_DRAFT", declared.getBody().source());
        assertEquals("NO_ACTIVE_SESSION", debug.getBody().status());
        assertEquals("REQUESTED", debugResult.getBody().getStatus());
        assertEquals(List.of(), references.getBody());
        verify(pageMapper).insert(any());
        verify(actionMapper).insert(any());
    }

    @Test
    void registersPageCatalogFromPublicRegistryRouteWithoutFallingThroughToRetiredProxy() {
        PlatformPageRegistryMapper pageMapper = mock(PlatformPageRegistryMapper.class);
        PlatformPageActionRegistryMapper actionMapper = mock(PlatformPageActionRegistryMapper.class);
        PlatformEmbedCatalogController controller = controller(pageMapper, actionMapper);

        ResponseEntity<PlatformEmbedCatalogController.PageCatalogRegisterResponse> response =
                controller.registerPageCatalog(
                        "bzjs12",
                        new PlatformEmbedCatalogController.PageCatalogRegisterPayload(
                                "contract-list",
                                "Contract List",
                                "/contracts",
                                "http://localhost:5173",
                                "page-instance-1",
                                true,
                                List.of(new PlatformEmbedCatalogController.PageCatalogActionPayload(
                                        "search",
                                        "Search",
                                        "Search contracts",
                                        false,
                                        Map.of("type", "object"),
                                        Map.of("type", "object"),
                                        Map.of("teamName", "Team A"),
                                        List.of("agent-1"),
                                        Map.of("source", "sdk"))),
                                Map.of("sdkVersion", "1.0.0")));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("SDK", response.getBody().source());
        assertEquals("bzjs12", response.getBody().page().projectCode());
        assertEquals("contract-list", response.getBody().page().pageKey());
        assertEquals(1, response.getBody().actions().size());
        assertEquals("search", response.getBody().actions().get(0).actionKey());
        verify(pageMapper).insert(argThat(page ->
                "bzjs12".equals(page.getProjectCode())
                        && "contract-list".equals(page.getPageKey())
                        && "page-instance-1".equals(page.getCurrentPageInstanceId())));
        verify(actionMapper).delete(any());
        verify(actionMapper).insert(argThat(action ->
                "bzjs12".equals(action.getProjectCode())
                        && "contract-list".equals(action.getPageKey())
                        && "search".equals(action.getActionKey())));
    }

    private PlatformEmbedCatalogController controller(PlatformPageRegistryMapper pageMapper,
                                                      PlatformPageActionRegistryMapper actionMapper) {
        return new PlatformEmbedCatalogController(
                pageMapper,
                actionMapper,
                mock(PlatformEmbedSessionMapper.class),
                mock(PlatformPageActionEventMapper.class),
                mock(PlatformEmbedChatEventMapper.class),
                mock(PlatformEmbedRendererMapper.class));
    }

    private PlatformEmbedCatalogController controller(PlatformEmbedSessionMapper sessionMapper,
                                                      PlatformPageActionEventMapper eventMapper) {
        return new PlatformEmbedCatalogController(
                mock(PlatformPageRegistryMapper.class),
                mock(PlatformPageActionRegistryMapper.class),
                sessionMapper,
                eventMapper,
                mock(PlatformEmbedChatEventMapper.class),
                mock(PlatformEmbedRendererMapper.class));
    }

    private PlatformEmbedCatalogController controller(PlatformEmbedChatEventMapper chatMapper,
                                                      PlatformEmbedRendererMapper rendererMapper) {
        return new PlatformEmbedCatalogController(
                mock(PlatformPageRegistryMapper.class),
                mock(PlatformPageActionRegistryMapper.class),
                mock(PlatformEmbedSessionMapper.class),
                mock(PlatformPageActionEventMapper.class),
                chatMapper,
                rendererMapper);
    }

    private PlatformEmbedCatalogController controller(PlatformPageRegistryMapper pageMapper,
                                                      PlatformPageActionRegistryMapper actionMapper,
                                                      PlatformEmbedSessionMapper sessionMapper,
                                                      PlatformPageActionEventMapper eventMapper) {
        return new PlatformEmbedCatalogController(
                pageMapper,
                actionMapper,
                sessionMapper,
                eventMapper,
                mock(PlatformEmbedChatEventMapper.class),
                mock(PlatformEmbedRendererMapper.class));
    }
}
