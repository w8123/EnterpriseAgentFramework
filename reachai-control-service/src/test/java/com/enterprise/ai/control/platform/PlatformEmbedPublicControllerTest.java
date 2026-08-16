package com.enterprise.ai.control.platform;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.control.client.capability.CapabilityProxyClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
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
                mock(com.enterprise.ai.control.client.runtime.RuntimeTrustedAgentExecutionGateway.class),
                mock(PlatformEmbedStreamRelay.class),
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
                mock(com.enterprise.ai.control.client.runtime.RuntimeTrustedAgentExecutionGateway.class),
                mock(PlatformEmbedStreamRelay.class),
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
        when(runtimeProxyClient.listAgents(null, "bzjs12")).thenReturn(ResponseEntity.ok((Object) List.of(Map.of(
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
                mock(com.enterprise.ai.control.client.runtime.RuntimeTrustedAgentExecutionGateway.class),
                mock(PlatformEmbedStreamRelay.class),
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
        com.enterprise.ai.control.client.runtime.RuntimeTrustedAgentExecutionGateway trustedExecutionClient =
                mock(com.enterprise.ai.control.client.runtime.RuntimeTrustedAgentExecutionGateway.class);
        PlatformEmbedPublicController controller = new PlatformEmbedPublicController(
                tokenService,
                sessionService,
                chatEventService,
                mock(CapabilityProxyClient.class),
                runtimeProxyClient,
                trustedExecutionClient,
                mock(PlatformEmbedStreamRelay.class),
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
                        Map.of("deptId", "dept-1", "deptName", "Operations", "region", "east")))
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
        when(trustedExecutionClient.executeTrusted(any(), eq("EMBED_SESSION"), eq("user-1"))).thenReturn(ResponseEntity.ok(Map.of(
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
        verify(trustedExecutionClient).executeTrusted(argThat(body -> body != null && "orders-bot".equals(body.get("agentId")) && "embed-1".equals(body.get("sessionId")) && "查订单".equals(body.get("message")) && "EMBED_CHAT".equals(body.get("intentHint"))), eq("EMBED_SESSION"), eq("user-1"));
        verify(trustedExecutionClient).executeTrusted(argThat(body -> body != null
                        && "Alice".equals(body.get("userName"))
                        && List.of("operator").equals(body.get("roles"))
                        && "dept-1".equals(body.get("deptId"))
                        && "Operations".equals(body.get("deptName"))
                        && Map.of("deptId", "dept-1", "deptName", "Operations", "region", "east")
                        .equals(body.get("attributes"))),
                eq("EMBED_SESSION"), eq("user-1"));
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
    void submitsSupervisorConfirmationThroughTheAuthenticatedEmbedSession() {
        ObjectMapper objectMapper = new ObjectMapper();
        PlatformEmbedTokenProperties tokenProperties = new PlatformEmbedTokenProperties();
        tokenProperties.setSecret("test-embed-token-secret");
        PlatformEmbedTokenService tokenService = new PlatformEmbedTokenService(objectMapper, tokenProperties);
        PlatformEmbedSessionService sessionService = mock(PlatformEmbedSessionService.class);
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        com.enterprise.ai.control.client.runtime.RuntimeTrustedAgentExecutionGateway trustedExecutionClient =
                mock(com.enterprise.ai.control.client.runtime.RuntimeTrustedAgentExecutionGateway.class);
        PlatformEmbedPublicController controller = new PlatformEmbedPublicController(
                tokenService,
                sessionService,
                mock(PlatformEmbedChatEventService.class),
                mock(CapabilityProxyClient.class),
                runtimeProxyClient,
                trustedExecutionClient,
                mock(PlatformEmbedStreamRelay.class),
                mock(PlatformPageActionEventMapper.class));
        String token = tokenService.issue(PlatformEmbedTokenIssueCommand.builder()
                .tenantId("default")
                .appId("bzjs12")
                .projectCode("bzjs12")
                .agentId("orders-bot")
                .pageInstanceId("page-1")
                .origin("http://localhost:5173")
                .principal(new PlatformEmbedTokenIssueCommand.BusinessPrincipal(
                        "user-1", "global-1", "Alice", List.of("operator"), Map.of()))
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
        when(trustedExecutionClient.executeTrusted(any(), eq("EMBED_SESSION"), eq("user-1"))).thenReturn(ResponseEntity.ok(Map.of(
                "success", true,
                "sessionId", "embed-1",
                "answer", "订单已更新",
                "metadata", Map.of("traceId", "trace-2", "code", "SUPERVISOR_COMPLETED"))));

        ResponseEntity<ApiResult<PlatformEmbedPublicController.EmbedChatMessageResponse>> response =
                controller.submitInteraction(
                        "embed-1",
                        "spv_1",
                        "Bearer " + token,
                        new PlatformEmbedPublicController.EmbedInteractionSubmitRequest(
                                "confirm", Map.of("confirm", true)));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("订单已更新", response.getBody().getData().answer());
        verify(trustedExecutionClient).executeTrusted(argThat(body -> body != null && "orders-bot".equals(body.get("agentId")) && "embed-1".equals(body.get("sessionId")) && "spv_1".equals(body.get("interactionId")) && "EMBED_INTERACTION_RESUME".equals(body.get("intentHint")) && "confirm".equals(((Map<?, ?>) body.get("uiSubmit")).get("action"))), eq("EMBED_SESSION"), eq("user-1"));
    }

    @Test
    void streamsEmbedChatMessageThroughRuntimeProxyAndRecordsUserMessage() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        PlatformEmbedTokenProperties tokenProperties = new PlatformEmbedTokenProperties();
        tokenProperties.setSecret("test-embed-token-secret");
        PlatformEmbedTokenService tokenService = new PlatformEmbedTokenService(objectMapper, tokenProperties);
        PlatformEmbedSessionService sessionService = mock(PlatformEmbedSessionService.class);
        PlatformEmbedChatEventMapper chatEventMapper = mock(PlatformEmbedChatEventMapper.class);
        PlatformEmbedChatEventService chatEventService = new PlatformEmbedChatEventService(chatEventMapper, objectMapper);
        PlatformEmbedStreamRelay embedStreamRelay = mock(PlatformEmbedStreamRelay.class);
        PlatformEmbedPublicController controller = new PlatformEmbedPublicController(
                tokenService,
                sessionService,
                chatEventService,
                mock(CapabilityProxyClient.class),
                mock(RuntimeProxyClient.class),
                mock(com.enterprise.ai.control.client.runtime.RuntimeTrustedAgentExecutionGateway.class),
                embedStreamRelay,
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
        PlatformEmbedSessionEntity session = activeSession();
        when(sessionService.requireActiveSession(eq("embed-1"), any())).thenReturn(session);

        ResponseEntity<StreamingResponseBody> response = controller.streamMessage(
                "embed-1",
                "Bearer " + token,
                new PlatformEmbedPublicController.EmbedChatMessageRequest("查订单"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.TEXT_EVENT_STREAM, response.getHeaders().getContentType());
        assertEquals("no-cache", response.getHeaders().getCacheControl());
        assertEquals("no", response.getHeaders().getFirst("X-Accel-Buffering"));
        verify(embedStreamRelay).streamMessage(eq(session), argThat(body -> {
            if (!"orders-bot".equals(body.get("agentId"))
                    || !"embed-1".equals(body.get("sessionId"))
                    || !"查订单".equals(body.get("message"))
                    || !"EMBED_CHAT".equals(body.get("intentHint"))
                    || !"EMBED".equals(body.get("entryType"))) {
                return false;
            }
            Object timing = body.get("controlTiming");
            if (!(timing instanceof Map<?, ?> map)) {
                return false;
            }
            return map.get("control.sessionLookupMs") instanceof Number
                    && map.get("control.userMessageAuditMs") instanceof Number
                    && map.get("control.preRuntimeMs") instanceof Number;
        }), eq(output));
        verify(chatEventMapper).insert(argThat(event ->
                "MESSAGE".equals(event.getEventType())
                        && "user".equals(event.getRole())
                        && "查订单".equals(event.getContent())));
    }

    @Test
    void streamsEmbedInteractionSubmitThroughRuntimeProxy() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        PlatformEmbedTokenProperties tokenProperties = new PlatformEmbedTokenProperties();
        tokenProperties.setSecret("test-embed-token-secret");
        PlatformEmbedTokenService tokenService = new PlatformEmbedTokenService(objectMapper, tokenProperties);
        PlatformEmbedSessionService sessionService = mock(PlatformEmbedSessionService.class);
        PlatformEmbedStreamRelay embedStreamRelay = mock(PlatformEmbedStreamRelay.class);
        PlatformEmbedPublicController controller = new PlatformEmbedPublicController(
                tokenService,
                sessionService,
                mock(PlatformEmbedChatEventService.class),
                mock(CapabilityProxyClient.class),
                mock(RuntimeProxyClient.class),
                mock(com.enterprise.ai.control.client.runtime.RuntimeTrustedAgentExecutionGateway.class),
                embedStreamRelay,
                mock(PlatformPageActionEventMapper.class));
        String token = embedToken(tokenService);
        PlatformEmbedSessionEntity session = activeSession();
        when(sessionService.requireActiveSession(eq("embed-1"), any())).thenReturn(session);

        ResponseEntity<StreamingResponseBody> response = controller.streamSubmitInteraction(
                "embed-1",
                "spv_1",
                "Bearer " + token,
                new PlatformEmbedPublicController.EmbedInteractionSubmitRequest(
                        "confirm", Map.of("confirm", true)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(embedStreamRelay).streamMessage(eq(session), argThat(body ->
                "spv_1".equals(body.get("interactionId"))
                        && "EMBED_INTERACTION_RESUME".equals(body.get("intentHint"))
                        && "EMBED".equals(body.get("entryType"))), eq(output));
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
                mock(com.enterprise.ai.control.client.runtime.RuntimeTrustedAgentExecutionGateway.class),
                mock(PlatformEmbedStreamRelay.class),
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
                mock(com.enterprise.ai.control.client.runtime.RuntimeTrustedAgentExecutionGateway.class),
                mock(PlatformEmbedStreamRelay.class),
                pageActionEventMapper);
        String token = embedToken(tokenService);
        PlatformEmbedSessionEntity session = activeSession();
        when(sessionService.requireActiveSession(eq("embed-1"), any())).thenReturn(session);
        PlatformPageActionEventEntity event = new PlatformPageActionEventEntity();
        event.setRequestId("req-1");
        event.setSessionId("embed-1");
        event.setActionKey("orders.open");
        event.setStatus("EXECUTING");
        when(pageActionEventMapper.selectOne(any())).thenReturn(event);
        when(pageActionEventMapper.update(any(), any())).thenReturn(1);

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
                                null,
                                null,
                                false));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("SUCCESS", response.getBody().getData().status());
        verify(pageActionEventMapper).update(eq(null), any());
    }

    @Test
    void claimsARequestedPageActionBeforeBrowserSideEffects() {
        ObjectMapper objectMapper = new ObjectMapper();
        PlatformEmbedTokenProperties tokenProperties = new PlatformEmbedTokenProperties();
        tokenProperties.setSecret("test-embed-token-secret");
        PlatformEmbedTokenService tokenService = new PlatformEmbedTokenService(objectMapper, tokenProperties);
        PlatformEmbedSessionService sessionService = mock(PlatformEmbedSessionService.class);
        PlatformPageActionEventMapper pageActionEventMapper = mock(PlatformPageActionEventMapper.class);
        PlatformEmbedPublicController controller = new PlatformEmbedPublicController(
                tokenService, sessionService, mock(PlatformEmbedChatEventService.class),
                mock(CapabilityProxyClient.class), mock(RuntimeProxyClient.class),
                mock(com.enterprise.ai.control.client.runtime.RuntimeTrustedAgentExecutionGateway.class),
                mock(PlatformEmbedStreamRelay.class), pageActionEventMapper);
        String token = embedToken(tokenService);
        when(sessionService.requireActiveSession(eq("embed-1"), any())).thenReturn(activeSession());
        PlatformPageActionEventEntity event = new PlatformPageActionEventEntity();
        event.setId(42L);
        event.setRequestId("req-claim");
        event.setSessionId("embed-1");
        event.setActionKey("orders.enable");
        event.setStatus("REQUESTED");
        when(pageActionEventMapper.selectOne(any())).thenReturn(event);
        when(pageActionEventMapper.update(any(), any())).thenReturn(1);

        ResponseEntity<ApiResult<PlatformEmbedPublicController.PageActionClaimResponse>> response =
                controller.claimPageAction(
                        "embed-1", "req-claim", "Bearer " + token,
                        new PlatformEmbedPublicController.PageActionClaimRequest(
                                "req-claim", "orders.enable"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(true, response.getBody().getData().claimed());
        assertEquals("EXECUTING", response.getBody().getData().status());
    }

    @Test
    void rejectsALateResultWithoutOverwritingTheTimeoutTerminalState() {
        ObjectMapper objectMapper = new ObjectMapper();
        PlatformEmbedTokenProperties tokenProperties = new PlatformEmbedTokenProperties();
        tokenProperties.setSecret("test-embed-token-secret");
        PlatformEmbedTokenService tokenService = new PlatformEmbedTokenService(objectMapper, tokenProperties);
        PlatformEmbedSessionService sessionService = mock(PlatformEmbedSessionService.class);
        PlatformPageActionEventMapper pageActionEventMapper = mock(PlatformPageActionEventMapper.class);
        PlatformEmbedPublicController controller = new PlatformEmbedPublicController(
                tokenService, sessionService, mock(PlatformEmbedChatEventService.class),
                mock(CapabilityProxyClient.class), mock(RuntimeProxyClient.class),
                mock(com.enterprise.ai.control.client.runtime.RuntimeTrustedAgentExecutionGateway.class),
                mock(PlatformEmbedStreamRelay.class), pageActionEventMapper);
        String token = embedToken(tokenService);
        when(sessionService.requireActiveSession(eq("embed-1"), any())).thenReturn(activeSession());
        PlatformPageActionEventEntity event = new PlatformPageActionEventEntity();
        event.setId(43L);
        event.setRequestId("req-timeout");
        event.setSessionId("embed-1");
        event.setActionKey("orders.enable");
        event.setStatus("TIMEOUT");
        when(pageActionEventMapper.selectOne(any())).thenReturn(event);

        ResponseEntity<ApiResult<PlatformEmbedPublicController.PageActionResultResponse>> response =
                controller.submitPageActionResult(
                        "embed-1", "req-timeout", "Bearer " + token,
                        new PlatformEmbedPublicController.PageActionResultRequest(
                                "1.0", "page.action.result", "req-timeout", "orders.enable",
                                "SUCCESS", Map.of("enabled", true), null, null, true));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("TIMEOUT", event.getStatus());
    }

    @Test
    void rebindsAnActiveSessionAfterTheSourceNavigationAcknowledgement() {
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
                mock(com.enterprise.ai.control.client.runtime.RuntimeTrustedAgentExecutionGateway.class),
                mock(PlatformEmbedStreamRelay.class),
                pageActionEventMapper);
        String token = tokenService.issue(PlatformEmbedTokenIssueCommand.builder()
                .tenantId("default")
                .appId("bzjs12")
                .projectCode("bzjs12")
                .agentId("orders-bot")
                .pageKey("orders.audit")
                .pageInstanceId("page-2")
                .route("/orders/audit")
                .origin("http://localhost:5173")
                .principal(new PlatformEmbedTokenIssueCommand.BusinessPrincipal(
                        "user-1", "global-1", "Alice", List.of("operator"), Map.of()))
                .build()).token();
        PlatformEmbedSessionEntity session = activeSession();
        session.setPageKey("orders.audit");
        session.setPageInstanceId("page-2");
        session.setRoute("/orders/audit");
        PlatformPageActionEventEntity navigation = new PlatformPageActionEventEntity();
        navigation.setRequestId("navigate-1");
        navigation.setSessionId("embed-1");
        navigation.setCommandType("NAVIGATE");
        navigation.setActionKey(PlatformPageBridgeCommandService.NAVIGATE_ACTION);
        navigation.setTargetPageKey("orders.audit");
        navigation.setTargetRoute("/orders/audit");
        // The source page can acknowledge router navigation before the target
        // component has registered its Page Bridge. The target rebind remains
        // valid while the navigation event is already SUCCESS.
        navigation.setStatus("SUCCESS");
        when(sessionService.requireActiveSessionForNavigationRebind(eq("embed-1"), any())).thenReturn(session);
        when(pageActionEventMapper.selectOne(any())).thenReturn(navigation);

        ResponseEntity<ApiResult<PlatformEmbedPublicController.PageBridgeRebindResponse>> response =
                controller.rebindPageBridge(
                        "embed-1",
                        "Bearer " + token,
                        new PlatformEmbedPublicController.PageBridgeRebindRequest(
                                "navigate-1",
                                "orders.audit",
                                "page-2",
                                "/orders/audit",
                                List.of("orders.audit.search"),
                                "1.0.0-SNAPSHOT"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("SUCCESS", navigation.getStatus());
        verify(sessionService).rebindBridgeAfterNavigation(
                eq(session), any(), eq("orders.audit"), eq("page-2"), eq("/orders/audit"),
                eq(List.of("orders.audit.search")), eq("1.0.0-SNAPSHOT"));
        verify(pageActionEventMapper).updateById(argThat(updated ->
                "SUCCESS".equals(updated.getStatus())
                        && updated.getResultJson().contains("\"pageInstanceId\":\"page-2\"")));
    }

    @Test
    void acceptsAConcreteTargetRouteForTheRegisteredParameterizedRoutePattern() {
        ObjectMapper objectMapper = new ObjectMapper();
        PlatformEmbedTokenProperties tokenProperties = new PlatformEmbedTokenProperties();
        tokenProperties.setSecret("test-embed-token-secret");
        PlatformEmbedTokenService tokenService = new PlatformEmbedTokenService(objectMapper, tokenProperties);
        PlatformEmbedSessionService sessionService = mock(PlatformEmbedSessionService.class);
        PlatformPageActionEventMapper pageActionEventMapper = mock(PlatformPageActionEventMapper.class);
        PlatformEmbedPublicController controller = new PlatformEmbedPublicController(
                tokenService, sessionService, mock(PlatformEmbedChatEventService.class),
                mock(CapabilityProxyClient.class), mock(RuntimeProxyClient.class),
                mock(com.enterprise.ai.control.client.runtime.RuntimeTrustedAgentExecutionGateway.class),
                mock(PlatformEmbedStreamRelay.class), pageActionEventMapper);
        String targetRoute = "/team-build/cycle-management/audit/dept-1/cycle-2";
        String token = tokenService.issue(PlatformEmbedTokenIssueCommand.builder()
                .tenantId("default").appId("bzjs12").projectCode("bzjs12").agentId("orders-bot")
                .pageKey("team.cycle-audit").pageInstanceId("page-2").route(targetRoute)
                .origin("http://localhost:5173")
                .principal(new PlatformEmbedTokenIssueCommand.BusinessPrincipal(
                        "user-1", "global-1", "Alice", List.of(), Map.of()))
                .build()).token();
        PlatformEmbedSessionEntity session = activeSession();
        session.setPageKey("team.cycle-audit");
        session.setPageInstanceId("page-2");
        session.setRoute(targetRoute);
        PlatformPageActionEventEntity navigation = new PlatformPageActionEventEntity();
        navigation.setRequestId("navigate-2");
        navigation.setSessionId("embed-1");
        navigation.setCommandType("NAVIGATE");
        navigation.setActionKey(PlatformPageBridgeCommandService.NAVIGATE_ACTION);
        navigation.setTargetPageKey("team.cycle-audit");
        navigation.setTargetRoute("/team-build/cycle-management/audit/:depart/:cycle");
        navigation.setStatus("REQUESTED");
        when(sessionService.requireActiveSessionForNavigationRebind(eq("embed-1"), any())).thenReturn(session);
        when(pageActionEventMapper.selectOne(any())).thenReturn(navigation);

        ResponseEntity<ApiResult<PlatformEmbedPublicController.PageBridgeRebindResponse>> response =
                controller.rebindPageBridge("embed-1", "Bearer " + token,
                        new PlatformEmbedPublicController.PageBridgeRebindRequest(
                                "navigate-2", "team.cycle-audit", "page-2", targetRoute, List.of(), "1.0.0"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("SUCCESS", navigation.getStatus());
    }

    @Test
    void acceptsStructuredBusinessTerminalPageActionResult() {
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
                mock(com.enterprise.ai.control.client.runtime.RuntimeTrustedAgentExecutionGateway.class),
                mock(PlatformEmbedStreamRelay.class),
                pageActionEventMapper);
        String token = embedToken(tokenService);
        PlatformEmbedSessionEntity session = activeSession();
        when(sessionService.requireActiveSession(eq("embed-1"), any())).thenReturn(session);
        PlatformPageActionEventEntity event = new PlatformPageActionEventEntity();
        event.setRequestId("req-1");
        event.setSessionId("embed-1");
        event.setActionKey("orders.open");
        event.setStatus("EXECUTING");
        when(pageActionEventMapper.selectOne(any())).thenReturn(event);
        when(pageActionEventMapper.update(any(), any())).thenReturn(1);

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
                                "PRECONDITION_FAILED",
                                Map.of("selectedCount", 0),
                                "Select a row before opening details",
                                null,
                                false));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("PRECONDITION_FAILED", response.getBody().getData().status());
        verify(pageActionEventMapper).update(eq(null), any());
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
