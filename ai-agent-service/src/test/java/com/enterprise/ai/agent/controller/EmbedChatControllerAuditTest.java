package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.agent.AgentDefinition;
import com.enterprise.ai.agent.agent.AgentDefinitionService;
import com.enterprise.ai.agent.agentscope.AgentRouter;
import com.enterprise.ai.agent.governance.GuardDecisionLogService;
import com.enterprise.ai.agent.identity.BusinessPrincipal;
import com.enterprise.ai.agent.identity.BusinessUserDirectoryService;
import com.enterprise.ai.agent.identity.EmbedAuditEventService;
import com.enterprise.ai.agent.identity.EmbedRendererAuthorizationService;
import com.enterprise.ai.agent.identity.EmbedSessionEntity;
import com.enterprise.ai.agent.identity.EmbedSessionService;
import com.enterprise.ai.agent.identity.EmbedTokenClaims;
import com.enterprise.ai.agent.identity.EmbedTokenIssueResult;
import com.enterprise.ai.agent.identity.EmbedTokenService;
import com.enterprise.ai.agent.model.AgentResult;
import com.enterprise.ai.agent.model.ChatResponse;
import com.enterprise.ai.agent.model.interactive.UiRequestPayload;
import com.enterprise.ai.agent.registry.RegistryCredentialEntity;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import com.enterprise.ai.common.dto.ApiResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbedChatControllerAuditTest {

    @Test
    void deniedTokenExchangeIsWrittenToGuardAudit() {
        RegistrySecurityService registrySecurityService = mock(RegistrySecurityService.class);
        GuardDecisionLogService audit = mock(GuardDecisionLogService.class);
        EmbedChatController controller = new EmbedChatController(
                registrySecurityService,
                mock(EmbedTokenService.class),
                mock(BusinessUserDirectoryService.class),
                mock(EmbedSessionService.class),
                mock(AgentDefinitionService.class),
                mock(AgentRouter.class),
                new ObjectMapper(),
                audit,
                mock(EmbedAuditEventService.class),
                mock(EmbedRendererAuthorizationService.class));

        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setProjectCode("demo");
        credential.setAllowedOriginsJson("[\"https://allowed.example\"]");
        when(registrySecurityService.verifyRequired(eq("demo"), any()))
                .thenReturn(credential);

        EmbedChatController.EmbedTokenExchangeRequest request = new EmbedChatController.EmbedTokenExchangeRequest(
                "demo",
                "agent-1",
                "page-1",
                "/orders",
                "https://denied.example",
                BusinessPrincipal.builder()
                        .externalUserId("u-1")
                        .globalUserId("g-1")
                        .build());
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/api/embed/token/exchange");

        ResponseEntity<ApiResult<EmbedChatController.EmbedTokenExchangeResponse>> response =
                controller.exchangeToken(request, servletRequest);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(audit).record(
                eq(null),
                eq("EMBED_TOKEN"),
                eq("AGENT"),
                eq("agent-1"),
                eq("DENY"),
                eq("embed origin is not allowed: https://denied.example"),
                any(Map.class));
    }

    @Test
    void pageActionResultIsRecordedAgainstEmbedSession() {
        EmbedTokenService tokenService = mock(EmbedTokenService.class);
        EmbedSessionService sessionService = mock(EmbedSessionService.class);
        EmbedAuditEventService auditEventService = mock(EmbedAuditEventService.class);
        EmbedChatController controller = new EmbedChatController(
                mock(RegistrySecurityService.class),
                tokenService,
                mock(BusinessUserDirectoryService.class),
                sessionService,
                mock(AgentDefinitionService.class),
                mock(AgentRouter.class),
                new ObjectMapper(),
                mock(GuardDecisionLogService.class),
                auditEventService,
                mock(EmbedRendererAuthorizationService.class));

        EmbedTokenClaims claims = new EmbedTokenClaims();
        claims.setProjectCode("bzsdk");
        claims.setAgentId("team-agent");
        claims.setExternalUserId("u-1");
        claims.setPageInstanceId("page-1");
        when(tokenService.verify("token")).thenReturn(claims);
        EmbedSessionEntity session = new EmbedSessionEntity();
        session.setSessionId("embed-session-1");
        session.setAgentId("team-agent");
        session.setPageInstanceId("page-1");
        when(sessionService.requireActiveSession("embed-session-1", claims)).thenReturn(session);

        EmbedChatController.PageActionResultRequest request =
                new EmbedChatController.PageActionResultRequest(
                        "page.action.result",
                        "1.0",
                        "page-action-1",
                        "team.openDetail",
                        "SUCCESS",
                        Map.of("opened", true),
                        null);

        ResponseEntity<ApiResult<EmbedChatController.PageActionResultResponse>> response =
                controller.pageActionResult("embed-session-1", "page-action-1", "Bearer token", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(auditEventService).recordPageActionResult(eq(session), eq("page-action-1"), any(Map.class));
    }

    @Test
    void tokenExchangeAllowsBoundedWildcardOrigin() {
        RegistrySecurityService registrySecurityService = mock(RegistrySecurityService.class);
        EmbedTokenService tokenService = mock(EmbedTokenService.class);
        AgentDefinitionService agentDefinitionService = mock(AgentDefinitionService.class);
        EmbedChatController controller = new EmbedChatController(
                registrySecurityService,
                tokenService,
                mock(BusinessUserDirectoryService.class),
                mock(EmbedSessionService.class),
                agentDefinitionService,
                mock(AgentRouter.class),
                new ObjectMapper(),
                mock(GuardDecisionLogService.class),
                mock(EmbedAuditEventService.class),
                mock(EmbedRendererAuthorizationService.class));

        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setProjectCode("demo");
        credential.setAllowedOriginsJson("[\"https://*.corp.example.com\"]");
        when(registrySecurityService.verifyRequired(eq("demo"), any())).thenReturn(credential);
        when(agentDefinitionService.findById("agent-1")).thenReturn(Optional.of(AgentDefinition.builder()
                .id("agent-1")
                .projectCode("demo")
                .build()));
        when(tokenService.issue(any())).thenReturn(new EmbedTokenIssueResult("jwt", 600, Instant.now().plusSeconds(600)));

        EmbedChatController.EmbedTokenExchangeRequest request = new EmbedChatController.EmbedTokenExchangeRequest(
                "demo",
                "agent-1",
                "page-1",
                "/orders",
                "https://orders.corp.example.com",
                BusinessPrincipal.builder()
                        .externalUserId("u-1")
                        .globalUserId("g-1")
                        .build());

        ResponseEntity<ApiResult<EmbedChatController.EmbedTokenExchangeResponse>> response =
                controller.exchangeToken(request, new MockHttpServletRequest("POST", "/api/embed/token/exchange"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("jwt", response.getBody().getData().token());
    }

    @Test
    void tokenExchangeRejectsDisabledAgent() {
        RegistrySecurityService registrySecurityService = mock(RegistrySecurityService.class);
        AgentDefinitionService agentDefinitionService = mock(AgentDefinitionService.class);
        EmbedChatController controller = new EmbedChatController(
                registrySecurityService,
                mock(EmbedTokenService.class),
                mock(BusinessUserDirectoryService.class),
                mock(EmbedSessionService.class),
                agentDefinitionService,
                mock(AgentRouter.class),
                new ObjectMapper(),
                mock(GuardDecisionLogService.class),
                mock(EmbedAuditEventService.class),
                mock(EmbedRendererAuthorizationService.class));

        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setProjectCode("demo");
        credential.setAllowedOriginsJson("[\"https://allowed.example\"]");
        when(registrySecurityService.verifyRequired(eq("demo"), any())).thenReturn(credential);
        when(agentDefinitionService.findById("agent-1")).thenReturn(Optional.of(AgentDefinition.builder()
                .id("agent-1")
                .projectCode("demo")
                .enabled(false)
                .build()));

        EmbedChatController.EmbedTokenExchangeRequest request = new EmbedChatController.EmbedTokenExchangeRequest(
                "demo",
                "agent-1",
                "page-1",
                "/orders",
                "https://allowed.example",
                BusinessPrincipal.builder()
                        .externalUserId("u-1")
                        .globalUserId("g-1")
                        .build());

        ResponseEntity<ApiResult<EmbedChatController.EmbedTokenExchangeResponse>> response =
                controller.exchangeToken(request, new MockHttpServletRequest("POST", "/api/embed/token/exchange"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void sendMessageRejectsPageActionMissingFromCurrentSessionBridgeActions() {
        EmbedTokenService tokenService = mock(EmbedTokenService.class);
        EmbedSessionService sessionService = mock(EmbedSessionService.class);
        AgentDefinitionService agentDefinitionService = mock(AgentDefinitionService.class);
        AgentRouter agentRouter = mock(AgentRouter.class);
        GuardDecisionLogService guardAudit = mock(GuardDecisionLogService.class);
        EmbedChatController controller = new EmbedChatController(
                mock(RegistrySecurityService.class),
                tokenService,
                mock(BusinessUserDirectoryService.class),
                sessionService,
                agentDefinitionService,
                agentRouter,
                new ObjectMapper(),
                guardAudit,
                mock(EmbedAuditEventService.class),
                mock(EmbedRendererAuthorizationService.class));
        EmbedTokenClaims claims = new EmbedTokenClaims();
        claims.setProjectCode("bzsdk");
        claims.setAgentId("team-agent");
        claims.setExternalUserId("u-1");
        claims.setPageInstanceId("page-1");
        claims.setRoles(List.of("TEAM_ADMIN"));
        when(tokenService.verify("token")).thenReturn(claims);
        EmbedSessionEntity session = new EmbedSessionEntity();
        session.setSessionId("embed-session-1");
        session.setAppId("bzsdk");
        session.setProjectCode("bzsdk");
        session.setAgentId("team-agent");
        session.setExternalUserId("u-1");
        session.setPageInstanceId("page-1");
        session.setBridgeActionsJson("[\"team.refreshList\"]");
        when(sessionService.requireActiveSession("embed-session-1", claims)).thenReturn(session);
        when(agentDefinitionService.findById("team-agent")).thenReturn(Optional.of(AgentDefinition.builder()
                .id("team-agent")
                .intentType("WORKFLOW")
                .build()));
        UiRequestPayload uiRequest = UiRequestPayload.builder()
                .extension(Map.of("pageActionRequest", Map.of(
                        "type", "page.action.requested",
                        "requestId", "page-action-1",
                        "actionKey", "team.openDetail")))
                .build();
        when(agentRouter.executeByDefinition(any(), eq("embed-session-1"), eq("u-1"), eq("open"), anyList(), any()))
                .thenReturn(AgentResult.builder().answer("ok").uiRequest(uiRequest).build());

        ResponseEntity<ApiResult<ChatResponse>> response = controller.sendMessage(
                "embed-session-1",
                "Bearer token",
                new EmbedChatController.EmbedChatMessageRequest("open"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(guardAudit).record(
                eq(null),
                eq("EMBED_PAGE_ACTION"),
                eq("PAGE_ACTION"),
                eq("team.openDetail"),
                eq("DENY"),
                eq("page action is not registered in current session"),
                any(Map.class));
    }
}
