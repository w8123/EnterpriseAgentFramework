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
import com.enterprise.ai.agent.identity.PageActionEventEntity;
import com.enterprise.ai.agent.model.AgentResult;
import com.enterprise.ai.agent.model.ChatResponse;
import com.enterprise.ai.agent.model.interactive.UiRequestPayload;
import com.enterprise.ai.agent.registry.RegistryCredentialEntity;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import com.enterprise.ai.agent.workflow.AgentEntryEntity;
import com.enterprise.ai.agent.workflow.AgentWorkflowBindingEntity;
import com.enterprise.ai.agent.workflow.EmbedWorkflowRuntimeService;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionEntity;
import com.enterprise.ai.agent.workflow.WorkflowRuntimeRequest;
import com.enterprise.ai.agent.workflow.WorkflowRuntimeService;
import com.enterprise.ai.agent.workflow.WorkflowVersionEntity;
import com.enterprise.ai.common.dto.ApiResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.never;
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
                mock(EmbedRendererAuthorizationService.class),
                mock(EmbedWorkflowRuntimeService.class),
                mock(WorkflowRuntimeService.class));

        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setProjectCode("demo");
        credential.setAllowedOriginsJson("[\"https://allowed.example\"]");
        when(registrySecurityService.verifyRequired(eq("demo"), any()))
                .thenReturn(credential);

        EmbedChatController.EmbedTokenExchangeRequest request = new EmbedChatController.EmbedTokenExchangeRequest(
                "demo",
                "agent-1",
                "orders.list",
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
                mock(EmbedRendererAuthorizationService.class),
                mock(EmbedWorkflowRuntimeService.class),
                mock(WorkflowRuntimeService.class));

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
    void pendingPageActionsReturnsDispatchRequestsForCurrentSession() {
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
                mock(EmbedRendererAuthorizationService.class),
                mock(EmbedWorkflowRuntimeService.class),
                mock(WorkflowRuntimeService.class));

        EmbedTokenClaims claims = new EmbedTokenClaims();
        claims.setProjectCode("bzsdk");
        claims.setAgentId("team-agent");
        claims.setExternalUserId("u-1");
        when(tokenService.verify("token")).thenReturn(claims);
        EmbedSessionEntity session = new EmbedSessionEntity();
        session.setSessionId("embed-session-1");
        session.setPageInstanceId("page-1");
        when(sessionService.requireActiveSession("embed-session-1", claims)).thenReturn(session);
        PageActionEventEntity event = new PageActionEventEntity();
        event.setRequestId("debug-1");
        event.setActionKey("team.search");
        event.setTitle("Search team");
        event.setArgsJson("{\"teamName\":\"一班\"}");
        event.setTargetPageInstanceId("page-1");
        when(auditEventService.pendingPageActionRequests(session, 10)).thenReturn(List.of(event));

        ResponseEntity<ApiResult<List<EmbedChatController.PageActionDispatchResponse>>> response =
                controller.pendingPageActions("embed-session-1", "Bearer token", 10);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        EmbedChatController.PageActionDispatchResponse dispatch = response.getBody().getData().get(0);
        assertEquals("debug-1", dispatch.requestId());
        assertEquals("team.search", dispatch.actionKey());
        assertEquals("一班", dispatch.args().get("teamName"));
        assertEquals("page-1", dispatch.target().get("pageInstanceId"));
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
                mock(EmbedRendererAuthorizationService.class),
                mock(EmbedWorkflowRuntimeService.class),
                mock(WorkflowRuntimeService.class));

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
                "orders.list",
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
    void tokenExchangeAllowsLocalDevelopmentOriginWhenPolicyIsEmpty() {
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
                mock(EmbedRendererAuthorizationService.class),
                mock(EmbedWorkflowRuntimeService.class),
                mock(WorkflowRuntimeService.class));

        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setProjectCode("demo");
        when(registrySecurityService.verifyRequired(eq("demo"), any())).thenReturn(credential);
        when(agentDefinitionService.findById("agent-1")).thenReturn(Optional.of(AgentDefinition.builder()
                .id("agent-1")
                .projectCode("demo")
                .build()));
        when(tokenService.issue(any())).thenReturn(new EmbedTokenIssueResult("jwt", 600, Instant.now().plusSeconds(600)));

        EmbedChatController.EmbedTokenExchangeRequest request = new EmbedChatController.EmbedTokenExchangeRequest(
                "demo",
                "agent-1",
                "orders.list",
                "page-1",
                "/orders",
                "http://localhost:9200",
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
    void tokenExchangeRejectsNonLocalOriginWhenPolicyIsEmpty() {
        RegistrySecurityService registrySecurityService = mock(RegistrySecurityService.class);
        EmbedChatController controller = new EmbedChatController(
                registrySecurityService,
                mock(EmbedTokenService.class),
                mock(BusinessUserDirectoryService.class),
                mock(EmbedSessionService.class),
                mock(AgentDefinitionService.class),
                mock(AgentRouter.class),
                new ObjectMapper(),
                mock(GuardDecisionLogService.class),
                mock(EmbedAuditEventService.class),
                mock(EmbedRendererAuthorizationService.class),
                mock(EmbedWorkflowRuntimeService.class),
                mock(WorkflowRuntimeService.class));

        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setProjectCode("demo");
        when(registrySecurityService.verifyRequired(eq("demo"), any())).thenReturn(credential);

        EmbedChatController.EmbedTokenExchangeRequest request = new EmbedChatController.EmbedTokenExchangeRequest(
                "demo",
                "agent-1",
                "orders.list",
                "page-1",
                "/orders",
                "https://app.example.com",
                BusinessPrincipal.builder()
                        .externalUserId("u-1")
                        .globalUserId("g-1")
                        .build());

        ResponseEntity<ApiResult<EmbedChatController.EmbedTokenExchangeResponse>> response =
                controller.exchangeToken(request, new MockHttpServletRequest("POST", "/api/embed/token/exchange"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
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
                mock(EmbedRendererAuthorizationService.class),
                mock(EmbedWorkflowRuntimeService.class),
                mock(WorkflowRuntimeService.class));

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
                "orders.list",
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
    void sendMessageExecutesResolvedWorkflowDefinitionWhenBindingExists() {
        EmbedTokenService tokenService = mock(EmbedTokenService.class);
        EmbedSessionService sessionService = mock(EmbedSessionService.class);
        AgentDefinitionService agentDefinitionService = mock(AgentDefinitionService.class);
        AgentRouter agentRouter = mock(AgentRouter.class);
        EmbedWorkflowRuntimeService embedWorkflowRuntimeService = mock(EmbedWorkflowRuntimeService.class);
        WorkflowRuntimeService workflowRuntimeService = mock(WorkflowRuntimeService.class);
        EmbedChatController controller = new EmbedChatController(
                mock(RegistrySecurityService.class),
                tokenService,
                mock(BusinessUserDirectoryService.class),
                sessionService,
                agentDefinitionService,
                agentRouter,
                new ObjectMapper(),
                mock(GuardDecisionLogService.class),
                mock(EmbedAuditEventService.class),
                mock(EmbedRendererAuthorizationService.class),
                embedWorkflowRuntimeService,
                workflowRuntimeService);

        EmbedTokenClaims claims = new EmbedTokenClaims();
        claims.setProjectCode("orders");
        claims.setAgentId("global-agent");
        claims.setExternalUserId("u-1");
        claims.setPageInstanceId("page-1");
        claims.setRoles(List.of("ORDER_USER"));
        when(tokenService.verify("token")).thenReturn(claims);
        EmbedSessionEntity session = new EmbedSessionEntity();
        session.setSessionId("embed-session-1");
        session.setAppId("orders");
        session.setProjectCode("orders");
        session.setAgentId("global-agent");
        session.setPageKey("orders.list");
        session.setRoute("/orders");
        session.setExternalUserId("u-1");
        session.setPageInstanceId("page-1");
        when(sessionService.requireActiveSession("embed-session-1", claims)).thenReturn(session);

        AgentEntryEntity agent = new AgentEntryEntity();
        agent.setId("global-agent");
        agent.setKeySlug("orders-global-ai");
        agent.setProjectCode("orders");

        AgentWorkflowBindingEntity binding = new AgentWorkflowBindingEntity();
        binding.setId(7L);
        binding.setAgentId("global-agent");
        binding.setWorkflowId("workflow-1");
        binding.setBindingType("PAGE");
        binding.setPageKey("orders.list");
        binding.setEnabled(true);

        WorkflowDefinitionEntity workflow = new WorkflowDefinitionEntity();
        workflow.setId("workflow-1");
        workflow.setKeySlug("orders-list-assistant");
        workflow.setName("Orders List Assistant");
        workflow.setWorkflowType("PAGE_ASSISTANT");
        workflow.setRuntimeType("LANGGRAPH4J");

        WorkflowVersionEntity activeVersion = new WorkflowVersionEntity();
        activeVersion.setId(3L);
        activeVersion.setWorkflowId("workflow-1");
        activeVersion.setVersion("v3");
        activeVersion.setStatus("ACTIVE");

        when(embedWorkflowRuntimeService.resolveRunnableWorkflowContext(session, null))
                .thenReturn(Optional.of(new EmbedWorkflowRuntimeService.RunnableWorkflowContext(
                        agent, binding, workflow, activeVersion)));
        when(workflowRuntimeService.execute(any(WorkflowRuntimeRequest.class)))
                .thenReturn(AgentResult.builder()
                        .answer("Here are the orders.")
                        .metadata(Map.of(
                                "entryAgentId", "global-agent",
                                "entryAgentKeySlug", "orders-global-ai",
                                "resolvedWorkflowId", "workflow-1",
                                "workflowKeySlug", "orders-list-assistant",
                                "workflowVersionId", 3L,
                                "bindingId", 7L,
                                "bindingType", "PAGE",
                                "pageKey", "orders.list",
                                "route", "/orders"))
                        .build());
        when(workflowRuntimeService.toExecutionShell(eq(agent), eq(workflow), eq(activeVersion), any()))
                .thenReturn(AgentDefinition.builder()
                        .id("workflow-1")
                        .keySlug("orders-list-assistant")
                        .intentType("PAGE_ASSISTANT")
                        .build());

        ResponseEntity<ApiResult<ChatResponse>> response = controller.sendMessage(
                "embed-session-1",
                "Bearer token",
                new EmbedChatController.EmbedChatMessageRequest("show orders"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Here are the orders.", response.getBody().getData().getAnswer());
        Map<String, Object> responseMetadata = response.getBody().getData().getMetadata();
        assertEquals("global-agent", responseMetadata.get("entryAgentId"));
        assertEquals("orders-global-ai", responseMetadata.get("entryAgentKeySlug"));
        assertEquals("workflow-1", responseMetadata.get("resolvedWorkflowId"));
        assertEquals("orders-list-assistant", responseMetadata.get("workflowKeySlug"));
        assertEquals(3L, responseMetadata.get("workflowVersionId"));
        assertEquals(7L, responseMetadata.get("bindingId"));
        assertEquals("PAGE", responseMetadata.get("bindingType"));
        assertEquals("orders.list", responseMetadata.get("pageKey"));
        assertEquals("/orders", responseMetadata.get("route"));

        ArgumentCaptor<WorkflowRuntimeRequest> runtimeRequest = ArgumentCaptor.forClass(WorkflowRuntimeRequest.class);
        verify(embedWorkflowRuntimeService).resolveRunnableWorkflowContext(session, null);
        verify(workflowRuntimeService).execute(runtimeRequest.capture());
        assertEquals("embed-session-1", runtimeRequest.getValue().getSessionId());
        assertEquals("show orders", runtimeRequest.getValue().getMessage());
        assertEquals(agent, runtimeRequest.getValue().getAgent());
        assertEquals(workflow, runtimeRequest.getValue().getWorkflow());
        assertEquals(activeVersion, runtimeRequest.getValue().getActiveVersion());
        assertEquals("orders.list", runtimeRequest.getValue().getPageContext().get("pageKey"));
        assertEquals("/orders", runtimeRequest.getValue().getPageContext().get("route"));
        assertEquals(7L, runtimeRequest.getValue().getMetadata().get("bindingId"));
        assertEquals("PAGE", runtimeRequest.getValue().getMetadata().get("bindingType"));
        verify(agentRouter, never()).executeByDefinition(any(), eq("embed-session-1"), eq("u-1"),
                eq("show orders"), anyList(), any());
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
                mock(EmbedRendererAuthorizationService.class),
                mock(EmbedWorkflowRuntimeService.class),
                mock(WorkflowRuntimeService.class));
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
