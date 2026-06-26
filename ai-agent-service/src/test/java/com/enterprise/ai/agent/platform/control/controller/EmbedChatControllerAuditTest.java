package com.enterprise.ai.agent.platform.control.controller;

import com.enterprise.ai.agent.platform.control.context.ContextItemResponse;
import com.enterprise.ai.agent.platform.control.context.ContextItemType;
import com.enterprise.ai.agent.platform.control.context.ContextPackageResponse;
import com.enterprise.ai.agent.platform.control.context.ContextSearchResult;
import com.enterprise.ai.agent.platform.control.context.MemoryLane;
import com.enterprise.ai.agent.platform.control.context.memory.RuntimeMemoryCandidateResult;
import com.enterprise.ai.agent.platform.control.context.memory.RuntimeMemoryCandidateService;
import com.enterprise.ai.agent.platform.control.context.runtime.RuntimeContextInjectionResult;
import com.enterprise.ai.agent.platform.control.context.runtime.RuntimeContextPackageService;
import com.enterprise.ai.agent.platform.control.controller.EmbedChatController;
import com.enterprise.ai.agent.runtime.AgentRuntimeProfile;
import com.enterprise.ai.agent.workflow.AgentEntryService;
import com.enterprise.ai.agent.agentscope.AgentRouter;
import com.enterprise.ai.agent.platform.control.governance.GuardDecisionLogService;
import com.enterprise.ai.agent.platform.control.identity.BusinessPrincipal;
import com.enterprise.ai.agent.platform.control.identity.BusinessUserDirectoryService;
import com.enterprise.ai.agent.platform.control.identity.EmbedAuditEventService;
import com.enterprise.ai.agent.platform.control.identity.EmbedRendererAuthorizationService;
import com.enterprise.ai.agent.platform.control.identity.EmbedSessionEntity;
import com.enterprise.ai.agent.platform.control.identity.EmbedSessionService;
import com.enterprise.ai.agent.platform.control.identity.EmbedTokenClaims;
import com.enterprise.ai.agent.platform.control.identity.EmbedTokenException;
import com.enterprise.ai.agent.platform.control.identity.EmbedTokenIssueResult;
import com.enterprise.ai.agent.platform.control.identity.EmbedTokenService;
import com.enterprise.ai.agent.platform.control.identity.PageActionEventEntity;
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
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbedChatControllerAuditTest {

    private RuntimeContextPackageService runtimeContextService() {
        RuntimeContextPackageService service = mock(RuntimeContextPackageService.class);
        RuntimeContextInjectionResult skipped = RuntimeContextInjectionResult.builder()
                .enabled(false)
                .skippedReason("test-stub")
                .build();
        lenient().when(service.injectForEmbedAgent(any(), any(), any(), any(), any())).thenReturn(skipped);
        lenient().when(service.injectForEmbedWorkflow(any(), any(), any(), any(), any(), any())).thenReturn(skipped);
        return service;
    }

    private RuntimeMemoryCandidateService runtimeMemoryCandidateService() {
        RuntimeMemoryCandidateService service = mock(RuntimeMemoryCandidateService.class);
        lenient().when(service.tryCreateFromInteraction(any(), any(), any(), any(), any()))
                .thenReturn(RuntimeMemoryCandidateResult.builder()
                        .created(false)
                        .skippedReason("test-stub")
                        .build());
        return service;
    }

    @Test
    void deniedTokenExchangeIsWrittenToGuardAudit() {
        RegistrySecurityService registrySecurityService = mock(RegistrySecurityService.class);
        GuardDecisionLogService audit = mock(GuardDecisionLogService.class);
        EmbedChatController controller = new EmbedChatController(
                registrySecurityService,
                mock(EmbedTokenService.class),
                mock(BusinessUserDirectoryService.class),
                mock(EmbedSessionService.class),
                mock(AgentEntryService.class),
                mock(AgentRouter.class),
                new ObjectMapper(),
                audit,
                mock(EmbedAuditEventService.class),
                mock(EmbedRendererAuthorizationService.class),
                mock(EmbedWorkflowRuntimeService.class),
                mock(WorkflowRuntimeService.class),
                runtimeContextService(),
                runtimeMemoryCandidateService());

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
                mock(AgentEntryService.class),
                mock(AgentRouter.class),
                new ObjectMapper(),
                mock(GuardDecisionLogService.class),
                auditEventService,
                mock(EmbedRendererAuthorizationService.class),
                mock(EmbedWorkflowRuntimeService.class),
                mock(WorkflowRuntimeService.class),
                runtimeContextService(),
                runtimeMemoryCandidateService());

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
                mock(AgentEntryService.class),
                mock(AgentRouter.class),
                new ObjectMapper(),
                mock(GuardDecisionLogService.class),
                auditEventService,
                mock(EmbedRendererAuthorizationService.class),
                mock(EmbedWorkflowRuntimeService.class),
                mock(WorkflowRuntimeService.class),
                runtimeContextService(),
                runtimeMemoryCandidateService());

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
        EmbedWorkflowRuntimeService embedWorkflowRuntimeService = mock(EmbedWorkflowRuntimeService.class);
        when(embedWorkflowRuntimeService.resolveAgentEntry("agent-1")).thenReturn(Optional.of(agentEntry("agent-1", "demo", true)));
        EmbedChatController controller = new EmbedChatController(
                registrySecurityService,
                tokenService,
                mock(BusinessUserDirectoryService.class),
                mock(EmbedSessionService.class),
                mock(AgentEntryService.class),
                mock(AgentRouter.class),
                new ObjectMapper(),
                mock(GuardDecisionLogService.class),
                mock(EmbedAuditEventService.class),
                mock(EmbedRendererAuthorizationService.class),
                embedWorkflowRuntimeService,
                mock(WorkflowRuntimeService.class),
                runtimeContextService(),
                runtimeMemoryCandidateService());

        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setProjectCode("demo");
        credential.setAllowedOriginsJson("[\"https://*.corp.example.com\"]");
        when(registrySecurityService.verifyRequired(eq("demo"), any())).thenReturn(credential);
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
        EmbedWorkflowRuntimeService embedWorkflowRuntimeService = mock(EmbedWorkflowRuntimeService.class);
        when(embedWorkflowRuntimeService.resolveAgentEntry("agent-1")).thenReturn(Optional.of(agentEntry("agent-1", "demo", true)));
        EmbedChatController controller = new EmbedChatController(
                registrySecurityService,
                tokenService,
                mock(BusinessUserDirectoryService.class),
                mock(EmbedSessionService.class),
                mock(AgentEntryService.class),
                mock(AgentRouter.class),
                new ObjectMapper(),
                mock(GuardDecisionLogService.class),
                mock(EmbedAuditEventService.class),
                mock(EmbedRendererAuthorizationService.class),
                embedWorkflowRuntimeService,
                mock(WorkflowRuntimeService.class),
                runtimeContextService(),
                runtimeMemoryCandidateService());

        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setProjectCode("demo");
        when(registrySecurityService.verifyRequired(eq("demo"), any())).thenReturn(credential);
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
                mock(AgentEntryService.class),
                mock(AgentRouter.class),
                new ObjectMapper(),
                mock(GuardDecisionLogService.class),
                mock(EmbedAuditEventService.class),
                mock(EmbedRendererAuthorizationService.class),
                mock(EmbedWorkflowRuntimeService.class),
                mock(WorkflowRuntimeService.class),
                runtimeContextService(),
                runtimeMemoryCandidateService());

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
        EmbedWorkflowRuntimeService embedWorkflowRuntimeService = mock(EmbedWorkflowRuntimeService.class);
        when(embedWorkflowRuntimeService.resolveAgentEntry("agent-1")).thenReturn(Optional.of(agentEntry("agent-1", "demo", false)));
        EmbedChatController controller = new EmbedChatController(
                registrySecurityService,
                mock(EmbedTokenService.class),
                mock(BusinessUserDirectoryService.class),
                mock(EmbedSessionService.class),
                mock(AgentEntryService.class),
                mock(AgentRouter.class),
                new ObjectMapper(),
                mock(GuardDecisionLogService.class),
                mock(EmbedAuditEventService.class),
                mock(EmbedRendererAuthorizationService.class),
                embedWorkflowRuntimeService,
                mock(WorkflowRuntimeService.class),
                runtimeContextService(),
                runtimeMemoryCandidateService());

        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setProjectCode("demo");
        credential.setAllowedOriginsJson("[\"https://allowed.example\"]");
        when(registrySecurityService.verifyRequired(eq("demo"), any())).thenReturn(credential);

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
        AgentEntryService agentEntryService = mock(AgentEntryService.class);
        AgentRouter agentRouter = mock(AgentRouter.class);
        EmbedWorkflowRuntimeService embedWorkflowRuntimeService = mock(EmbedWorkflowRuntimeService.class);
        WorkflowRuntimeService workflowRuntimeService = mock(WorkflowRuntimeService.class);
        RuntimeContextPackageService runtimeContextPackageService = runtimeContextService();
        EmbedChatController controller = new EmbedChatController(
                mock(RegistrySecurityService.class),
                tokenService,
                mock(BusinessUserDirectoryService.class),
                sessionService,
                agentEntryService,
                agentRouter,
                new ObjectMapper(),
                mock(GuardDecisionLogService.class),
                mock(EmbedAuditEventService.class),
                mock(EmbedRendererAuthorizationService.class),
                embedWorkflowRuntimeService,
                workflowRuntimeService,
                runtimeContextPackageService,
                runtimeMemoryCandidateService());

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
        verify(agentRouter, never()).executeByProfile(any(), eq("embed-session-1"), eq("u-1"),
                eq("show orders"), anyList(), any(), any());
    }

    @Test
    void sendMessageMapsUnpublishedWorkflowRuntimeErrorToStudioPublishHint() {
        EmbedTokenService tokenService = mock(EmbedTokenService.class);
        EmbedSessionService sessionService = mock(EmbedSessionService.class);
        AgentRouter agentRouter = mock(AgentRouter.class);
        EmbedWorkflowRuntimeService embedWorkflowRuntimeService = mock(EmbedWorkflowRuntimeService.class);
        WorkflowRuntimeService workflowRuntimeService = mock(WorkflowRuntimeService.class);
        EmbedChatController controller = new EmbedChatController(
                mock(RegistrySecurityService.class),
                tokenService,
                mock(BusinessUserDirectoryService.class),
                sessionService,
                mock(AgentEntryService.class),
                agentRouter,
                new ObjectMapper(),
                mock(GuardDecisionLogService.class),
                mock(EmbedAuditEventService.class),
                mock(EmbedRendererAuthorizationService.class),
                embedWorkflowRuntimeService,
                workflowRuntimeService,
                runtimeContextService(),
                runtimeMemoryCandidateService());

        EmbedTokenClaims claims = new EmbedTokenClaims();
        claims.setProjectCode("orders");
        claims.setAgentId("global-agent");
        claims.setExternalUserId("u-1");
        when(tokenService.verify("token")).thenReturn(claims);
        EmbedSessionEntity session = new EmbedSessionEntity();
        session.setSessionId("embed-session-1");
        session.setAppId("orders");
        session.setProjectCode("orders");
        session.setAgentId("global-agent");
        session.setPageKey("orders.list");
        session.setRoute("/orders");
        session.setExternalUserId("u-1");
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

        when(embedWorkflowRuntimeService.resolveRunnableWorkflowContext(session, null))
                .thenReturn(Optional.of(new EmbedWorkflowRuntimeService.RunnableWorkflowContext(
                        agent, binding, workflow, null)));
        when(workflowRuntimeService.execute(any(WorkflowRuntimeRequest.class)))
                .thenThrow(new IllegalArgumentException("active workflow version is required"));

        ResponseEntity<ApiResult<ChatResponse>> response = controller.sendMessage(
                "embed-session-1",
                "Bearer token",
                new EmbedChatController.EmbedChatMessageRequest("show orders"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("工作流尚未发布，请到 Workflow Studio 发布", response.getBody().getMessage());
        verify(agentRouter, never()).executeByProfile(any(), eq("embed-session-1"), eq("u-1"),
                eq("show orders"), anyList(), any(), any());
    }

    @Test
    void sendMessageMapsExpiredEmbedSessionToRefreshHint() {
        EmbedTokenService tokenService = mock(EmbedTokenService.class);
        EmbedSessionService sessionService = mock(EmbedSessionService.class);
        AgentRouter agentRouter = mock(AgentRouter.class);
        EmbedChatController controller = new EmbedChatController(
                mock(RegistrySecurityService.class),
                tokenService,
                mock(BusinessUserDirectoryService.class),
                sessionService,
                mock(AgentEntryService.class),
                agentRouter,
                new ObjectMapper(),
                mock(GuardDecisionLogService.class),
                mock(EmbedAuditEventService.class),
                mock(EmbedRendererAuthorizationService.class),
                mock(EmbedWorkflowRuntimeService.class),
                mock(WorkflowRuntimeService.class),
                runtimeContextService(),
                runtimeMemoryCandidateService());

        EmbedTokenClaims claims = new EmbedTokenClaims();
        claims.setProjectCode("orders");
        claims.setAgentId("global-agent");
        claims.setExternalUserId("u-1");
        when(tokenService.verify("token")).thenReturn(claims);
        when(sessionService.requireActiveSession("embed-session-1", claims))
                .thenThrow(new EmbedTokenException("embed chat session is expired"));

        ResponseEntity<ApiResult<ChatResponse>> response = controller.sendMessage(
                "embed-session-1",
                "Bearer token",
                new EmbedChatController.EmbedChatMessageRequest("show orders"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("秘钥超时，请刷新页面后重试", response.getBody().getMessage());
        verify(agentRouter, never()).executeByProfile(any(), eq("embed-session-1"), eq("u-1"),
                eq("show orders"), anyList(), any(), any());
    }

    @Test
    void sendMessageAcceptsContentAliasFromEmbedClients() throws Exception {
        EmbedTokenService tokenService = mock(EmbedTokenService.class);
        EmbedSessionService sessionService = mock(EmbedSessionService.class);
        AgentRouter agentRouter = mock(AgentRouter.class);
        EmbedWorkflowRuntimeService embedWorkflowRuntimeService = mock(EmbedWorkflowRuntimeService.class);
        WorkflowRuntimeService workflowRuntimeService = mock(WorkflowRuntimeService.class);
        RuntimeContextPackageService runtimeContextPackageService = runtimeContextService();
        ObjectMapper objectMapper = new ObjectMapper();
        EmbedChatController controller = new EmbedChatController(
                mock(RegistrySecurityService.class),
                tokenService,
                mock(BusinessUserDirectoryService.class),
                sessionService,
                mock(AgentEntryService.class),
                agentRouter,
                objectMapper,
                mock(GuardDecisionLogService.class),
                mock(EmbedAuditEventService.class),
                mock(EmbedRendererAuthorizationService.class),
                embedWorkflowRuntimeService,
                workflowRuntimeService,
                runtimeContextPackageService,
                runtimeMemoryCandidateService());

        EmbedTokenClaims claims = new EmbedTokenClaims();
        claims.setProjectCode("orders");
        claims.setAgentId("global-agent");
        claims.setExternalUserId("u-1");
        when(tokenService.verify("token")).thenReturn(claims);
        EmbedSessionEntity session = new EmbedSessionEntity();
        session.setSessionId("embed-session-1");
        session.setAppId("orders");
        session.setProjectCode("orders");
        session.setAgentId("global-agent");
        session.setPageKey("orders.list");
        session.setRoute("/orders");
        session.setExternalUserId("u-1");
        when(sessionService.requireActiveSession("embed-session-1", claims)).thenReturn(session);

        AgentEntryEntity agent = new AgentEntryEntity();
        agent.setId("global-agent");
        agent.setKeySlug("orders-global-ai");
        agent.setProjectCode("orders");
        AgentWorkflowBindingEntity binding = new AgentWorkflowBindingEntity();
        binding.setId(7L);
        binding.setBindingType("PAGE");
        WorkflowDefinitionEntity workflow = new WorkflowDefinitionEntity();
        workflow.setId("workflow-1");
        workflow.setKeySlug("orders-list-assistant");
        workflow.setWorkflowType("PAGE_ASSISTANT");
        WorkflowVersionEntity activeVersion = new WorkflowVersionEntity();
        activeVersion.setId(3L);
        activeVersion.setWorkflowId("workflow-1");
        activeVersion.setVersion("v3");

        when(embedWorkflowRuntimeService.resolveRunnableWorkflowContext(session, null))
                .thenReturn(Optional.of(new EmbedWorkflowRuntimeService.RunnableWorkflowContext(
                        agent, binding, workflow, activeVersion)));
        when(workflowRuntimeService.execute(any(WorkflowRuntimeRequest.class)))
                .thenReturn(AgentResult.builder().answer("ok").metadata(Map.of()).build());

        EmbedChatController.EmbedChatMessageRequest request = objectMapper.readValue(
                "{\"content\":\"show orders\"}",
                EmbedChatController.EmbedChatMessageRequest.class);
        ResponseEntity<ApiResult<ChatResponse>> response = controller.sendMessage(
                "embed-session-1",
                "Bearer token",
                request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<WorkflowRuntimeRequest> runtimeRequest = ArgumentCaptor.forClass(WorkflowRuntimeRequest.class);
        verify(workflowRuntimeService).execute(runtimeRequest.capture());
        assertEquals("show orders", runtimeRequest.getValue().getMessage());
        verify(agentRouter, never()).executeByProfile(any(), any(), any(), any(), anyList(), any(), any());
    }

    @Test
    void sendMessageReturnsRuntimeContextHitSummariesInMetadata() {
        EmbedTokenService tokenService = mock(EmbedTokenService.class);
        EmbedSessionService sessionService = mock(EmbedSessionService.class);
        AgentRouter agentRouter = mock(AgentRouter.class);
        EmbedWorkflowRuntimeService embedWorkflowRuntimeService = mock(EmbedWorkflowRuntimeService.class);
        RuntimeContextPackageService runtimeContextPackageService = mock(RuntimeContextPackageService.class);
        EmbedChatController controller = new EmbedChatController(
                mock(RegistrySecurityService.class),
                tokenService,
                mock(BusinessUserDirectoryService.class),
                sessionService,
                mock(AgentEntryService.class),
                agentRouter,
                new ObjectMapper(),
                mock(GuardDecisionLogService.class),
                mock(EmbedAuditEventService.class),
                mock(EmbedRendererAuthorizationService.class),
                embedWorkflowRuntimeService,
                mock(WorkflowRuntimeService.class),
                runtimeContextPackageService,
                runtimeMemoryCandidateService());

        EmbedTokenClaims claims = new EmbedTokenClaims();
        claims.setProjectCode("orders");
        claims.setAgentId("global-agent");
        claims.setExternalUserId("u-1");
        claims.setRoles(List.of("USER"));
        when(tokenService.verify("token")).thenReturn(claims);
        EmbedSessionEntity session = new EmbedSessionEntity();
        session.setSessionId("embed-session-1");
        session.setAppId("orders");
        session.setProjectCode("orders");
        session.setAgentId("global-agent");
        session.setPageInstanceId("page-1");
        session.setExternalUserId("u-1");
        session.setGlobalUserId("g-1");
        when(sessionService.requireActiveSession("embed-session-1", claims)).thenReturn(session);
        when(embedWorkflowRuntimeService.resolveRunnableWorkflowContext(session, null)).thenReturn(Optional.empty());
        AgentEntryEntity agent = agentEntry("global-agent", "orders", true);
        agent.setKeySlug("orders-global-ai");
        agent.setEntryConfigJson("{\"intentType\":\"CHAT\",\"runtimePlacement\":\"CENTRAL\"}");
        when(embedWorkflowRuntimeService.resolveAgentEntry("global-agent")).thenReturn(Optional.of(agent));

        ContextSearchResult hit = ContextSearchResult.builder()
                .item(ContextItemResponse.builder()
                        .id(101L)
                        .itemType(ContextItemType.PREFERENCE.name())
                        .memoryLane(MemoryLane.RUNTIME_USER.name())
                        .title("视觉偏好")
                        .content("用户偏好使用深色模式")
                        .summary("偏好深色模式")
                        .confidence(new BigDecimal("0.9000"))
                        .trustLevel("HIGH")
                        .build())
                .rankScore(2.4)
                .hitReason("content token keyword match")
                .scoreBreakdown("tokens=0.6, trust=0.7")
                .build();
        ContextPackageResponse pkg = ContextPackageResponse.builder()
                .memoryLane(MemoryLane.RUNTIME_USER.name())
                .tenantId("default")
                .totalItems(1)
                .truncatedCount(0)
                .projectMemory(List.of())
                .userMemory(List.of(hit))
                .pageContext(List.of())
                .workflowContext(List.of())
                .apiContext(List.of())
                .rules(List.of())
                .evidenceSummary(List.of())
                .build();
        when(runtimeContextPackageService.injectForEmbedAgent(any(), any(), any(), any(), any()))
                .thenReturn(RuntimeContextInjectionResult.builder()
                        .enabled(true)
                        .packageResponse(pkg)
                        .itemCount(1)
                        .truncatedCount(0)
                        .build());
        when(agentRouter.executeByProfile(any(AgentRuntimeProfile.class), eq("embed-session-1"), eq("u-1"),
                eq("我想切换成深色主题"), anyList(), any(), any()))
                .thenReturn(AgentResult.builder()
                        .answer("已按你的偏好处理。")
                        .metadata(Map.of("traceId", "trace-1"))
                        .build());

        ResponseEntity<ApiResult<ChatResponse>> response = controller.sendMessage(
                "embed-session-1",
                "Bearer token",
                new EmbedChatController.EmbedChatMessageRequest("我想切换成深色主题"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> metadata = response.getBody().getData().getMetadata();
        assertEquals(true, metadata.get("runtimeContextEnabled"));
        assertEquals(1, metadata.get("runtimeContextItemCount"));
        List<?> hits = (List<?>) metadata.get("runtimeContextHits");
        assertEquals(1, hits.size());
        Map<?, ?> firstHit = (Map<?, ?>) hits.get(0);
        assertEquals(101L, firstHit.get("itemId"));
        assertEquals("PREFERENCE", firstHit.get("itemType"));
        assertEquals("视觉偏好", firstHit.get("title"));
        assertEquals("content token keyword match", firstHit.get("hitReason"));
        assertEquals("tokens=0.6, trust=0.7", firstHit.get("scoreBreakdown"));
        assertEquals(false, firstHit.containsKey("content"));
    }

    @Test
    void sendMessageRejectsPageActionMissingFromCurrentSessionBridgeActions() {
        EmbedTokenService tokenService = mock(EmbedTokenService.class);
        EmbedSessionService sessionService = mock(EmbedSessionService.class);
        AgentRouter agentRouter = mock(AgentRouter.class);
        EmbedWorkflowRuntimeService embedWorkflowRuntimeService = mock(EmbedWorkflowRuntimeService.class);
        GuardDecisionLogService guardAudit = mock(GuardDecisionLogService.class);
        EmbedChatController controller = new EmbedChatController(
                mock(RegistrySecurityService.class),
                tokenService,
                mock(BusinessUserDirectoryService.class),
                sessionService,
                mock(AgentEntryService.class),
                agentRouter,
                new ObjectMapper(),
                guardAudit,
                mock(EmbedAuditEventService.class),
                mock(EmbedRendererAuthorizationService.class),
                embedWorkflowRuntimeService,
                mock(WorkflowRuntimeService.class),
                runtimeContextService(),
                runtimeMemoryCandidateService());
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
        when(embedWorkflowRuntimeService.resolveRunnableWorkflowContext(session, null)).thenReturn(Optional.empty());
        AgentEntryEntity teamAgent = agentEntry("team-agent", "bzsdk", true);
        teamAgent.setEntryConfigJson("{\"intentType\":\"WORKFLOW\"}");
        when(embedWorkflowRuntimeService.resolveAgentEntry("team-agent")).thenReturn(Optional.of(teamAgent));
        UiRequestPayload uiRequest = UiRequestPayload.builder()
                .extension(Map.of("pageActionRequest", Map.of(
                        "type", "page.action.requested",
                        "requestId", "page-action-1",
                        "actionKey", "team.openDetail")))
                .build();
        when(agentRouter.executeByProfile(any(AgentRuntimeProfile.class), eq("embed-session-1"), eq("u-1"), eq("open"), anyList(), any(), any()))
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

    private AgentEntryEntity agentEntry(String id, String projectCode, boolean enabled) {
        AgentEntryEntity entry = new AgentEntryEntity();
        entry.setId(id);
        entry.setProjectCode(projectCode);
        entry.setEnabled(enabled);
        return entry;
    }
}
