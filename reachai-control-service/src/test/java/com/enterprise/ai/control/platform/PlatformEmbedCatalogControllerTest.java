package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.pageworkbench.application.PageCatalogApplicationService;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.ActionView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageReport;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformEmbedCatalogControllerTest {

    @Test
    void exposesPublicPageCatalogReadRoutesFromControl() throws Exception {
        Method method = PlatformEmbedCatalogController.class.getDeclaredMethod(
                "listPages", String.class, int.class);
        GetMapping mapping = method.getAnnotation(GetMapping.class);

        assertArrayEquals(
                new String[]{"/api/platform/embed/pages", "/api/platform/embed/pages/catalog"},
                mapping.value());
    }

    @Test
    void listsCanonicalPagesAndActions() {
        Fixture fixture = fixture();
        PageView page = page();
        when(fixture.pageCatalog.listPages("bzjs12", false)).thenReturn(List.of(page));
        when(fixture.pageCatalog.listActions("bzjs12", null, null, 500))
                .thenReturn(page.actions());
        when(fixture.sessionMapper.selectOne(any())).thenReturn(null);

        var pages = fixture.controller.listPages("bzjs12", 200);
        var actions = fixture.controller.listPageActions("bzjs12", 500);

        assertEquals("contract-list", pages.getBody().get(0).pageKey());
        assertEquals("search", actions.getBody().get(0).actionKey());
        verify(fixture.pageCatalog).listPages("bzjs12", false);
        verify(fixture.pageCatalog).listActions("bzjs12", null, null, 500);
    }

    @Test
    void deletesCanonicalPageAndControlOwnedEmbedArtifacts() {
        Fixture fixture = fixture();
        when(fixture.pageCatalog.findPage(11L)).thenReturn(Optional.of(page()));
        when(fixture.pageCatalog.deletePage(11L)).thenReturn(true);
        PlatformEmbedSessionEntity session = new PlatformEmbedSessionEntity();
        session.setSessionId("sess-1");
        when(fixture.sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(fixture.sessionMapper.delete(any())).thenReturn(1);
        when(fixture.eventMapper.delete(any())).thenReturn(3);
        when(fixture.chatMapper.delete(any())).thenReturn(4);

        var response = fixture.controller.deletePageRegistry(11L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().deletedPages());
        assertEquals(1, response.getBody().deletedActions());
        assertEquals(1, response.getBody().deletedEmbedSessions());
        assertEquals(3, response.getBody().deletedPageActionEvents());
        assertEquals(4, response.getBody().deletedEmbedChatEvents());
        verify(fixture.pageCatalog).deletePage(11L);
    }

    @Test
    void listsEmbedOperationalDataAndManagesRenderers() {
        Fixture fixture = fixture();
        PlatformEmbedSessionEntity session = new PlatformEmbedSessionEntity();
        session.setSessionId("sess-1");
        session.setStatus("ACTIVE");
        when(fixture.sessionMapper.selectList(any())).thenReturn(List.of(session));
        PlatformPageActionEventEntity event = new PlatformPageActionEventEntity();
        event.setRequestId("req-1");
        event.setStatus("REQUESTED");
        when(fixture.eventMapper.selectList(any())).thenReturn(List.of(event));
        PlatformEmbedChatEventEntity chat = new PlatformEmbedChatEventEntity();
        chat.setEventType("message");
        when(fixture.chatMapper.selectList(any())).thenReturn(List.of(chat));
        PlatformEmbedRendererEntity renderer = new PlatformEmbedRendererEntity();
        renderer.setId(61L);
        renderer.setAppId("bzjs12");
        renderer.setRendererKey("table");
        renderer.setName("Table");
        renderer.setVersion("1.0");
        renderer.setStatus("ACTIVE");
        when(fixture.rendererMapper.selectList(any())).thenReturn(List.of(renderer));
        when(fixture.rendererMapper.selectById(61L)).thenReturn(renderer);

        assertEquals(
                "sess-1",
                fixture.controller.listSessions("bzjs12", null, null, null, 100)
                        .getBody().get(0).sessionId());
        assertEquals(
                "req-1",
                fixture.controller.listPageActionEvents("sess-1", null, null, null, 100)
                        .getBody().get(0).requestId());
        assertEquals(
                "message",
                fixture.controller.listChatEvents("sess-1", 200).getBody().get(0).eventType());
        assertEquals(
                "table",
                fixture.controller.listRenderers("bzjs12", null, null, 100)
                        .getBody().get(0).rendererKey());

        fixture.controller.createRenderer(new PlatformEmbedCatalogController.EmbedRendererPayload(
                "bzjs12", "chart", "Chart", "1.0", Map.of(), List.of(), "ACTIVE"));
        fixture.controller.updateRenderer(61L, new PlatformEmbedCatalogController.EmbedRendererPayload(
                "bzjs12", "table", "Table v2", "1.1", Map.of(), List.of(), "ACTIVE"));
        fixture.controller.disableRenderer(61L);

        verify(fixture.rendererMapper).insert(any());
        verify(fixture.rendererMapper, times(2)).updateById(any());
    }

    @Test
    void manualAndSdkRegistrationDelegateToSingleCanonicalCatalog() {
        Fixture fixture = fixture();
        when(fixture.capabilityClient.getProjectByCode("bzjs12")).thenReturn(Map.of("id", 7L));
        when(fixture.pageCatalog.upsertPageCatalog(
                eq(7L), eq("bzjs12"), any(PageReport.class), any(), eq(false)))
                .thenReturn(page());
        when(fixture.pageCatalog.upsertPageCatalog(
                eq(7L), eq("bzjs12"), any(PageReport.class), any(), eq(true)))
                .thenReturn(page());
        when(fixture.sessionMapper.selectOne(any())).thenReturn(null);

        var manual = fixture.controller.declarePageActionCatalog(
                new PlatformEmbedCatalogController.PageActionManualDeclarePayload(
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
        var sdk = fixture.controller.registerPageCatalog(
                "bzjs12",
                new PlatformEmbedCatalogController.PageCatalogRegisterPayload(
                        "contract-list",
                        "Contract List",
                        "/contracts",
                        "http://localhost:5173",
                        "page-instance-1",
                        true,
                        List.of(),
                        Map.of()));

        assertEquals("MANUAL", manual.getBody().source());
        assertEquals("SDK", sdk.getBody().source());
        verify(fixture.pageCatalog, times(2)).upsertPageCatalog(
                eq(7L), eq("bzjs12"), any(PageReport.class), any(), any(Boolean.class));
    }

    @Test
    void sdkRegistrationAcceptsCanonicalCapabilityProjectIdField() {
        Fixture fixture = fixture();
        when(fixture.capabilityClient.getProjectByCode("mall")).thenReturn(Map.of("projectId", 33L));
        when(fixture.pageCatalog.upsertPageCatalog(
                eq(33L), eq("mall"), any(PageReport.class), any(), eq(true)))
                .thenReturn(page());
        when(fixture.sessionMapper.selectOne(any())).thenReturn(null);

        fixture.controller.registerPageCatalog(
                "mall",
                new PlatformEmbedCatalogController.PageCatalogRegisterPayload(
                        "mall.oms.order", "订单列表", "/oms/order", "http://localhost:5174",
                        "page-1", true, List.of(), Map.of()));

        ArgumentCaptor<PageReport> reportCaptor = ArgumentCaptor.forClass(PageReport.class);
        verify(fixture.pageCatalog).upsertPageCatalog(
                eq(33L), eq("mall"), reportCaptor.capture(), any(), eq(true));
        assertEquals(
                "http://localhost:5174/oms/order",
                reportCaptor.getValue().businessPageUrl());
    }

    @Test
    void debugPageActionPersistsReadinessRoutingMetadata() {
        Fixture fixture = fixture();
        PageView page = page();
        when(fixture.pageCatalog.findAction(21L))
                .thenReturn(Optional.of(page.actions().get(0)));
        when(fixture.pageCatalog.findPageForAction(21L))
                .thenReturn(Optional.of(page));
        PlatformEmbedSessionEntity session = new PlatformEmbedSessionEntity();
        session.setSessionId("sess-debug");
        session.setTenantId("tenant-1");
        session.setAppId("bzjs12");
        session.setAgentId("agent-1");
        session.setPageInstanceId("page-instance-1");
        session.setStatus("ACTIVE");
        when(fixture.sessionMapper.selectOne(any())).thenReturn(session);

        var response = fixture.controller.debugPageActionCatalog(
                21L,
                new PlatformEmbedCatalogController.PageActionDebugRequest(
                        null,
                        Map.of("keyword", "contract")));

        assertEquals("REQUESTED", response.getBody().status());
        ArgumentCaptor<PlatformPageActionEventEntity> eventCaptor =
                ArgumentCaptor.forClass(PlatformPageActionEventEntity.class);
        verify(fixture.eventMapper).insert(eventCaptor.capture());
        PlatformPageActionEventEntity event = eventCaptor.getValue();
        assertEquals("PAGE_ACTION", event.getCommandType());
        assertEquals("contract-list", event.getTargetPageKey());
        assertEquals("/contracts", event.getTargetRoute());
        assertEquals("search", event.getActionKey());
    }

    private Fixture fixture() {
        PageCatalogApplicationService pageCatalog = mock(PageCatalogApplicationService.class);
        CapabilityProjectOnboardingClient capabilityClient =
                mock(CapabilityProjectOnboardingClient.class);
        PlatformEmbedSessionMapper sessionMapper = mock(PlatformEmbedSessionMapper.class);
        PlatformPageActionEventMapper eventMapper = mock(PlatformPageActionEventMapper.class);
        PlatformEmbedChatEventMapper chatMapper = mock(PlatformEmbedChatEventMapper.class);
        PlatformEmbedRendererMapper rendererMapper = mock(PlatformEmbedRendererMapper.class);
        PlatformEmbedCatalogController controller = new PlatformEmbedCatalogController(
                pageCatalog,
                capabilityClient,
                sessionMapper,
                eventMapper,
                chatMapper,
                rendererMapper,
                new ObjectMapper());
        return new Fixture(
                controller,
                pageCatalog,
                capabilityClient,
                sessionMapper,
                eventMapper,
                chatMapper,
                rendererMapper);
    }

    private PageView page() {
        ActionView action = new ActionView(
                21L,
                11L,
                7L,
                "bzjs12",
                "contract-list",
                "search",
                "Search",
                "Search contracts",
                "PAGE_ACTION",
                "READ",
                false,
                null,
                JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode(),
                List.of(),
                null,
                "SDK",
                "ACTIVE",
                JsonNodeFactory.instance.objectNode(),
                LocalDateTime.of(2026, 7, 1, 9, 0));
        return new PageView(
                11L,
                7L,
                "bzjs12",
                "contract-list",
                "contract",
                "Contract",
                "Contract List",
                null,
                "/contracts",
                "http://localhost:5173/contracts",
                "src/views/ContractList.vue",
                "SDK",
                "ACTIVE",
                LocalDateTime.of(2026, 7, 1, 9, 0),
                null,
                List.of(),
                List.of(action));
    }

    private record Fixture(
            PlatformEmbedCatalogController controller,
            PageCatalogApplicationService pageCatalog,
            CapabilityProjectOnboardingClient capabilityClient,
            PlatformEmbedSessionMapper sessionMapper,
            PlatformPageActionEventMapper eventMapper,
            PlatformEmbedChatEventMapper chatMapper,
            PlatformEmbedRendererMapper rendererMapper) {
    }
}
