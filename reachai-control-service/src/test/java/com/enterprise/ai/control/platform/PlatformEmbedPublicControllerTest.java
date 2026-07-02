package com.enterprise.ai.control.platform;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.control.client.capability.CapabilityProxyClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformEmbedPublicControllerTest {

    @Test
    void createsEmbedChatSessionOnPublicEmbedRouteWithoutRetiredProxy() {
        PlatformEmbedSessionMapper sessionMapper = mock(PlatformEmbedSessionMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        PlatformEmbedTokenProperties tokenProperties = new PlatformEmbedTokenProperties();
        tokenProperties.setSecret("test-embed-token-secret");
        PlatformEmbedTokenService tokenService = new PlatformEmbedTokenService(objectMapper, tokenProperties);
        PlatformEmbedSessionService sessionService = new PlatformEmbedSessionService(sessionMapper, objectMapper);
        PlatformEmbedPublicController controller = new PlatformEmbedPublicController(
                tokenService,
                sessionService,
                mock(PlatformEmbedChatEventService.class),
                mock(CapabilityProxyClient.class),
                mock(RuntimeProxyClient.class),
                mock(PlatformPageActionEventMapper.class));
        String token = tokenService.issue(PlatformEmbedTokenIssueCommand.builder()
                .tenantId("default")
                .appId("bzjs12")
                .projectCode("bzjs12")
                .agentId("orders-bot")
                .pageKey("orders.list")
                .pageInstanceId("page-1")
                .route("/orders")
                .origin("http://localhost:5173")
                .principal(new PlatformEmbedTokenIssueCommand.BusinessPrincipal(
                        "user-1",
                        "global-1",
                        "Alice",
                        List.of("operator"),
                        Map.of("dept", "ops")))
                .build()).token();

        ResponseEntity<ApiResult<PlatformEmbedPublicController.EmbedChatSessionResponse>> response =
                controller.createSession(
                        "Bearer " + token,
                        new PlatformEmbedPublicController.EmbedChatSessionCreateRequest(
                                "orders.list",
                                "page-1",
                                "/orders",
                                List.of("search", "open"),
                                "1.0.0"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(200, response.getBody().getCode());
        assertNotNull(response.getBody().getData().sessionId());
        assertEquals("orders-bot", response.getBody().getData().agentId());
        assertEquals("user-1", response.getBody().getData().principal().get("externalUserId"));
        verify(sessionMapper).insert(argThat(session ->
                session.getSessionId() != null
                        && session.getSessionId().startsWith("embed-")
                        && "bzjs12".equals(session.getProjectCode())
                        && "orders-bot".equals(session.getAgentId())
                        && "orders.list".equals(session.getPageKey())
                        && "page-1".equals(session.getPageInstanceId())
                        && "ACTIVE".equals(session.getStatus())
                        && session.getBridgeActionsJson().contains("search")));
    }

    @Test
    void exchangesEmbedTokenThroughCapabilityVerificationAndRuntimeAgentCheck() {
        ObjectMapper objectMapper = new ObjectMapper();
        PlatformEmbedTokenProperties tokenProperties = new PlatformEmbedTokenProperties();
        tokenProperties.setSecret("test-embed-token-secret");
        PlatformEmbedTokenService tokenService = new PlatformEmbedTokenService(objectMapper, tokenProperties);
        CapabilityProxyClient capabilityProxyClient = mock(CapabilityProxyClient.class);
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        PlatformEmbedPublicController controller = new PlatformEmbedPublicController(
                tokenService,
                mock(PlatformEmbedSessionService.class),
                mock(PlatformEmbedChatEventService.class),
                capabilityProxyClient,
                runtimeProxyClient,
                mock(PlatformPageActionEventMapper.class));
        when(capabilityProxyClient.verifyEmbedTokenExchange(
                eq("app-bzjs12"),
                eq("1700000000000"),
                eq("nonce-1"),
                eq("signature-1"),
                any())).thenReturn(ResponseEntity.ok(Map.of(
                "projectCode", "bzjs12",
                "appKey", "app-bzjs12",
                "tokenTtlSeconds", 900)));
        when(runtimeProxyClient.listAgents(null, "bzjs12", null)).thenReturn(ResponseEntity.ok((Object) List.of(Map.of(
                "id", "agent-1",
                "keySlug", "orders-bot",
                "projectCode", "bzjs12",
                "enabled", true))));

        ResponseEntity<ApiResult<PlatformEmbedPublicController.EmbedTokenExchangeResponse>> response =
                controller.exchangeToken(
                        "app-bzjs12",
                        null,
                        "1700000000000",
                        null,
                        "nonce-1",
                        null,
                        "signature-1",
                        null,
                        new PlatformEmbedPublicController.EmbedTokenExchangeRequest(
                                "bzjs12",
                                "orders-bot",
                                "orders.list",
                                "page-1",
                                "/orders",
                                "http://localhost:5173",
                                new PlatformEmbedPublicController.BusinessPrincipal(
                                        "user-1",
                                        "global-1",
                                        "Alice",
                                        List.of("operator"),
                                        Map.of("dept", "ops"))));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(200, response.getBody().getCode());
        PlatformEmbedPublicController.EmbedTokenExchangeResponse data = response.getBody().getData();
        assertNotNull(data.token());
        assertEquals(900, data.expiresIn());
        assertEquals("orders-bot", data.sessionHint().get("agentId"));
        PlatformEmbedTokenClaims claims = tokenService.verify(data.token());
        assertEquals("bzjs12", claims.getProjectCode());
        assertEquals("orders-bot", claims.getAgentId());
        assertEquals("user-1", claims.getExternalUserId());
        assertEquals("page-1", claims.getPageInstanceId());
        verify(capabilityProxyClient).verifyEmbedTokenExchange(
                eq("app-bzjs12"),
                eq("1700000000000"),
                eq("nonce-1"),
                eq("signature-1"),
                argThat(body -> "bzjs12".equals(body.get("projectCode"))
                        && "orders-bot".equals(body.get("agentId"))
                        && "http://localhost:5173".equals(body.get("origin"))));
    }

    @Test
    void returnsForbiddenWhenCapabilityRejectsEmbedTokenExchange() {
        PlatformEmbedTokenProperties tokenProperties = new PlatformEmbedTokenProperties();
        tokenProperties.setSecret("test-embed-token-secret");
        CapabilityProxyClient capabilityProxyClient = mock(CapabilityProxyClient.class);
        PlatformEmbedPublicController controller = new PlatformEmbedPublicController(
                new PlatformEmbedTokenService(new ObjectMapper(), tokenProperties),
                mock(PlatformEmbedSessionService.class),
                mock(PlatformEmbedChatEventService.class),
                capabilityProxyClient,
                mock(RuntimeProxyClient.class),
                mock(PlatformPageActionEventMapper.class));
        when(capabilityProxyClient.verifyEmbedTokenExchange(
                eq("app-bzjs12"),
                eq("1700000000000"),
                eq("nonce-1"),
                eq("bad-signature"),
                any())).thenThrow(new RuntimeException("forbidden"));

        ResponseEntity<ApiResult<PlatformEmbedPublicController.EmbedTokenExchangeResponse>> response =
                controller.exchangeToken(
                        "app-bzjs12",
                        null,
                        "1700000000000",
                        null,
                        "nonce-1",
                        null,
                        "bad-signature",
                        null,
                        new PlatformEmbedPublicController.EmbedTokenExchangeRequest(
                                "bzjs12",
                                "orders-bot",
                                "orders.list",
                                "page-1",
                                "/orders",
                                "http://localhost:5173",
                                new PlatformEmbedPublicController.BusinessPrincipal(
                                        "user-1",
                                        null,
                                        "Alice",
                                        List.of(),
                                        Map.of())));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(403, response.getBody().getCode());
    }

    @Test
    void sendsEmbedChatMessageThroughRuntimeAndRecordsChatEvents() {
        ObjectMapper objectMapper = new ObjectMapper();
        PlatformEmbedTokenProperties tokenProperties = new PlatformEmbedTokenProperties();
        tokenProperties.setSecret("test-embed-token-secret");
        PlatformEmbedTokenService tokenService = new PlatformEmbedTokenService(objectMapper, tokenProperties);
        PlatformEmbedSessionService sessionService = mock(PlatformEmbedSessionService.class);
        PlatformEmbedChatEventMapper chatEventMapper = mock(PlatformEmbedChatEventMapper.class);
        PlatformEmbedChatEventService chatEventService = new PlatformEmbedChatEventService(chatEventMapper, objectMapper);
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        PlatformEmbedPublicController controller = new PlatformEmbedPublicController(
                tokenService,
                sessionService,
                chatEventService,
                mock(CapabilityProxyClient.class),
                runtimeProxyClient,
                mock(PlatformPageActionEventMapper.class));
        String token = tokenService.issue(PlatformEmbedTokenIssueCommand.builder()
                .tenantId("default")
                .appId("bzjs12")
                .projectCode("bzjs12")
                .agentId("orders-bot")
                .pageKey("orders.list")
                .pageInstanceId("page-1")
                .route("/orders")
                .origin("http://localhost:5173")
                .principal(new PlatformEmbedTokenIssueCommand.BusinessPrincipal(
                        "user-1",
                        "global-1",
                        "Alice",
                        List.of("operator"),
                        Map.of("dept", "ops")))
                .build()).token();
        PlatformEmbedSessionEntity session = new PlatformEmbedSessionEntity();
        session.setSessionId("embed-1");
        session.setTenantId("default");
        session.setAppId("bzjs12");
        session.setProjectCode("bzjs12");
        session.setAgentId("orders-bot");
        session.setExternalUserId("user-1");
        session.setGlobalUserId("global-1");
        session.setPageKey("orders.list");
        session.setPageInstanceId("page-1");
        session.setRoute("/orders");
        session.setOrigin("http://localhost:5173");
        session.setStatus("ACTIVE");
        when(sessionService.requireActiveSession(eq("embed-1"), any())).thenReturn(session);
        when(runtimeProxyClient.executeAgent(any())).thenReturn(ResponseEntity.ok(Map.of(
                "success", true,
                "sessionId", "embed-1",
                "answer", "订单已找到",
                "toolCalls", List.of("orders.search"),
                "metadata", Map.of("traceId", "trace-1"))));

        ResponseEntity<ApiResult<PlatformEmbedPublicController.EmbedChatMessageResponse>> response =
                controller.sendMessage(
                        "embed-1",
                        "Bearer " + token,
                        new PlatformEmbedPublicController.EmbedChatMessageRequest("查订单"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(200, response.getBody().getCode());
        assertEquals("订单已找到", response.getBody().getData().answer());
        assertEquals("embed-1", response.getBody().getData().sessionId());
        verify(runtimeProxyClient).executeAgent(argThat(body ->
                "orders-bot".equals(body.get("agentDefinitionId"))
                        && "embed-1".equals(body.get("sessionId"))
                        && "查订单".equals(body.get("message"))
                        && "EMBED_CHAT".equals(body.get("intentHint"))));
        verify(chatEventMapper, times(2)).insert(any());
        verify(chatEventMapper).insert(argThat(event ->
                "MESSAGE".equals(event.getEventType())
                        && "user".equals(event.getRole())
                        && "查订单".equals(event.getContent())));
        verify(chatEventMapper).insert(argThat(event ->
                "MESSAGE".equals(event.getEventType())
                        && "assistant".equals(event.getRole())
                        && "订单已找到".equals(event.getContent())
                        && "trace-1".equals(event.getTraceId())));
    }

    @Test
    void streamsEmbedChatMessageThroughRuntimeAndRecordsChatEvents() {
        ObjectMapper objectMapper = new ObjectMapper();
        PlatformEmbedTokenProperties tokenProperties = new PlatformEmbedTokenProperties();
        tokenProperties.setSecret("test-embed-token-secret");
        PlatformEmbedTokenService tokenService = new PlatformEmbedTokenService(objectMapper, tokenProperties);
        PlatformEmbedSessionService sessionService = mock(PlatformEmbedSessionService.class);
        PlatformEmbedChatEventMapper chatEventMapper = mock(PlatformEmbedChatEventMapper.class);
        PlatformEmbedChatEventService chatEventService = new PlatformEmbedChatEventService(chatEventMapper, objectMapper);
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        PlatformEmbedPublicController controller = new PlatformEmbedPublicController(
                tokenService,
                sessionService,
                chatEventService,
                mock(CapabilityProxyClient.class),
                runtimeProxyClient,
                mock(PlatformPageActionEventMapper.class));
        String token = tokenService.issue(PlatformEmbedTokenIssueCommand.builder()
                .tenantId("default")
                .appId("bzjs12")
                .projectCode("bzjs12")
                .agentId("orders-bot")
                .pageKey("orders.list")
                .pageInstanceId("page-1")
                .route("/orders")
                .origin("http://localhost:5173")
                .principal(new PlatformEmbedTokenIssueCommand.BusinessPrincipal(
                        "user-1",
                        "global-1",
                        "Alice",
                        List.of("operator"),
                        Map.of("dept", "ops")))
                .build()).token();
        PlatformEmbedSessionEntity session = new PlatformEmbedSessionEntity();
        session.setSessionId("embed-1");
        session.setTenantId("default");
        session.setAppId("bzjs12");
        session.setProjectCode("bzjs12");
        session.setAgentId("orders-bot");
        session.setExternalUserId("user-1");
        session.setGlobalUserId("global-1");
        session.setPageKey("orders.list");
        session.setPageInstanceId("page-1");
        session.setRoute("/orders");
        session.setOrigin("http://localhost:5173");
        session.setStatus("ACTIVE");
        when(sessionService.requireActiveSession(eq("embed-1"), any())).thenReturn(session);
        when(runtimeProxyClient.executeAgent(any())).thenReturn(ResponseEntity.ok(Map.of(
                "success", true,
                "sessionId", "embed-1",
                "answer", "订单已找到",
                "metadata", Map.of("traceId", "trace-1"))));

        ResponseEntity<SseEmitter> response = controller.streamMessage(
                "embed-1",
                "Bearer " + token,
                new PlatformEmbedPublicController.EmbedChatMessageRequest("查订单"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.TEXT_EVENT_STREAM, response.getHeaders().getContentType());
        assertNotNull(response.getBody());
        verify(runtimeProxyClient).executeAgent(argThat(body ->
                "orders-bot".equals(body.get("agentDefinitionId"))
                        && "embed-1".equals(body.get("sessionId"))
                        && "查订单".equals(body.get("message"))
                        && "EMBED_CHAT".equals(body.get("intentHint"))));
        verify(chatEventMapper, times(2)).insert(any());
    }

    @Test
    void listsPendingPageActionsForActiveEmbedSessionWithoutRetiredProxy() {
        ObjectMapper objectMapper = new ObjectMapper();
        PlatformEmbedTokenProperties tokenProperties = new PlatformEmbedTokenProperties();
        tokenProperties.setSecret("test-embed-token-secret");
        PlatformEmbedTokenService tokenService = new PlatformEmbedTokenService(objectMapper, tokenProperties);
        PlatformEmbedSessionService sessionService = mock(PlatformEmbedSessionService.class);
        PlatformPageActionEventMapper pageActionEventMapper = mock(PlatformPageActionEventMapper.class);
        PlatformEmbedPublicController controller = new PlatformEmbedPublicController(
                tokenService,
                sessionService,
                mock(PlatformEmbedChatEventService.class),
                mock(CapabilityProxyClient.class),
                mock(RuntimeProxyClient.class),
                pageActionEventMapper);
        String token = embedToken(tokenService);
        PlatformEmbedSessionEntity session = activeSession();
        when(sessionService.requireActiveSession(eq("embed-1"), any())).thenReturn(session);
        PlatformPageActionEventEntity event = new PlatformPageActionEventEntity();
        event.setRequestId("req-1");
        event.setSessionId("embed-1");
        event.setNodeId("node-1");
        event.setActionKey("orders.open");
        event.setTitle("打开订单");
        event.setArgsJson("{\"orderId\":12}");
        event.setTargetPageInstanceId("page-1");
        event.setConfirmRequired(true);
        event.setStatus("REQUESTED");
        when(pageActionEventMapper.selectList(any())).thenReturn(List.of(event));

        ResponseEntity<ApiResult<List<PlatformEmbedPublicController.PageActionDispatchRequest>>> response =
                controller.listPendingPageActions("embed-1", "Bearer " + token, 10);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        PlatformEmbedPublicController.PageActionDispatchRequest request = response.getBody().getData().get(0);
        assertEquals("page.action.requested", request.type());
        assertEquals("req-1", request.requestId());
        assertEquals("orders.open", request.actionKey());
        assertEquals("page-1", request.target().get("pageInstanceId"));
        assertEquals(12, request.args().get("orderId"));
        assertEquals("orders.list", request.metadata().get("pageKey"));
    }

    @Test
    void recordsPageActionResultForActiveEmbedSessionWithoutRetiredProxy() {
        ObjectMapper objectMapper = new ObjectMapper();
        PlatformEmbedTokenProperties tokenProperties = new PlatformEmbedTokenProperties();
        tokenProperties.setSecret("test-embed-token-secret");
        PlatformEmbedTokenService tokenService = new PlatformEmbedTokenService(objectMapper, tokenProperties);
        PlatformEmbedSessionService sessionService = mock(PlatformEmbedSessionService.class);
        PlatformPageActionEventMapper pageActionEventMapper = mock(PlatformPageActionEventMapper.class);
        PlatformEmbedPublicController controller = new PlatformEmbedPublicController(
                tokenService,
                sessionService,
                mock(PlatformEmbedChatEventService.class),
                mock(CapabilityProxyClient.class),
                mock(RuntimeProxyClient.class),
                pageActionEventMapper);
        String token = embedToken(tokenService);
        PlatformEmbedSessionEntity session = activeSession();
        when(sessionService.requireActiveSession(eq("embed-1"), any())).thenReturn(session);
        PlatformPageActionEventEntity event = new PlatformPageActionEventEntity();
        event.setRequestId("req-1");
        event.setSessionId("embed-1");
        event.setActionKey("orders.open");
        event.setStatus("REQUESTED");
        when(pageActionEventMapper.selectOne(any())).thenReturn(event);

        ResponseEntity<ApiResult<PlatformEmbedPublicController.PageActionResultResponse>> response =
                controller.submitPageActionResult(
                        "embed-1",
                        "req-1",
                        "Bearer " + token,
                        new PlatformEmbedPublicController.PageActionResultRequest(
                                "1.0",
                                "page.action.result",
                                "req-1",
                                "orders.open",
                                "SUCCESS",
                                Map.of("opened", true),
                                null));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("SUCCESS", response.getBody().getData().status());
        verify(pageActionEventMapper).updateById(argThat(updated ->
                "SUCCESS".equals(updated.getStatus())
                        && updated.getResultJson().contains("\"opened\":true")
                        && updated.getCompletedAt() != null));
    }

    private String embedToken(PlatformEmbedTokenService tokenService) {
        return tokenService.issue(PlatformEmbedTokenIssueCommand.builder()
                .tenantId("default")
                .appId("bzjs12")
                .projectCode("bzjs12")
                .agentId("orders-bot")
                .pageKey("orders.list")
                .pageInstanceId("page-1")
                .route("/orders")
                .origin("http://localhost:5173")
                .principal(new PlatformEmbedTokenIssueCommand.BusinessPrincipal(
                        "user-1",
                        "global-1",
                        "Alice",
                        List.of("operator"),
                        Map.of("dept", "ops")))
                .build()).token();
    }

    private PlatformEmbedSessionEntity activeSession() {
        PlatformEmbedSessionEntity session = new PlatformEmbedSessionEntity();
        session.setSessionId("embed-1");
        session.setTenantId("default");
        session.setAppId("bzjs12");
        session.setProjectCode("bzjs12");
        session.setAgentId("orders-bot");
        session.setExternalUserId("user-1");
        session.setGlobalUserId("global-1");
        session.setPageKey("orders.list");
        session.setPageInstanceId("page-1");
        session.setRoute("/orders");
        session.setOrigin("http://localhost:5173");
        session.setStatus("ACTIVE");
        return session;
    }
}
