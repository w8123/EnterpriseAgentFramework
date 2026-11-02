package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.identity.PlatformAiConnectionService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformAuthorizationService;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import com.enterprise.ai.control.mcp.api.protocol.McpAuditPayloadSanitizer;
import com.enterprise.ai.control.mcp.api.protocol.McpEndpointController;
import com.enterprise.ai.control.mcp.api.protocol.McpProtocolRequestGuard;
import com.enterprise.ai.control.mcp.api.protocol.McpRequestBodyLimitFilter;
import com.enterprise.ai.control.mcp.application.employee.McpEmployeeProtocolService;
import com.enterprise.ai.control.mcp.application.platform.McpPlatformProtocolService;
import com.enterprise.ai.control.mcp.application.port.McpPlatformWorkflowAuthoringPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformProjectCatalogPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformCapabilityReadPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformCapabilityInvocationPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgePort;
import com.enterprise.ai.control.mcp.application.protocol.McpProtocolApplicationService;
import com.enterprise.ai.control.mcp.infrastructure.McpHubProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** JSON-RPC -> delegated identity -> actual console authorization -> Runtime client. */
class PlatformMcpReadFlowTest {
    @Test
    void workflowTrialErrorsExposeOnlyTheVettedNodeDiagnostics() throws Exception {
        var connections = mock(PlatformAiConnectionService.class);
        var trials = mock(com.enterprise.ai.control.mcp.application.port.McpPlatformWorkflowTrialPort.class);
        var json = new ObjectMapper();
        var audit = mock(PlatformAuthAuditService.class);
        var platform = new McpPlatformProtocolService(connections,
                mock(com.enterprise.ai.control.mcp.application.port.McpPlatformReadPort.class), mock(McpPlatformProjectCatalogPort.class),
                mock(McpPlatformCapabilityReadPort.class), mock(McpPlatformCapabilityInvocationPort.class), mock(McpPlatformKnowledgePort.class),
                mock(McpPlatformWorkflowAuthoringPort.class), trials,
                mock(com.enterprise.ai.control.mcp.application.port.McpPlatformAgentAuthoringPort.class),
                mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAgentReleaseService.class),
                mock(com.enterprise.ai.control.identity.PlatformAiApprovalService.class), json, audit, org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformWorkflowReleaseService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformModelCatalogPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformSkillCatalogPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeCatalogPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeSettingsPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformKnowledgeSettingsApprovalService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformPageAssistantSyncService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformWorkflowRollbackService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformKnowledgeImportService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformSkillGovernanceService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformSkillMarketPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformBusinessSystemReadPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.governance.ControlToolAclPlatformOperations.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformToolAclService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformBusinessSystemChangeService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformKnowledgeChunkService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformUploadToolsService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformSkillCapturePort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformModelWritePort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformModelChangeService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformModelAccountService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformPublicationPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformPublicationService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformAutomationPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAutomationService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformRunOpsService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAccountService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformBusinessIndexService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformA2aReadPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformOpsReadPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAgentLifecycleService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformHttpConnectionService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformIdentityMappingService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformKnowledgeBaseService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformCapabilityReviewService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformMemoryPort.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.identity.PlatformAiCallRecords.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformEmbedService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformApiMarketService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformCatalogResourceService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformProjectPagesService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformWorkflowCredentialService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformDebugSessionService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformHttpApiAcceptService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformSystemStatusService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAiCodingService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformMemoryErasureService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAgentWorkflowService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformPageWorkflowDeliveryService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformRunCandidateService.class));
        var business = mock(McpProtocolApplicationService.class);
        var properties = new McpHubProperties(); properties.setEnabled(true);
        var guard = new McpProtocolRequestGuard(properties);
        var endpoint = new McpEndpointController(guard, business, properties, json,
                new McpAuditPayloadSanitizer(json, properties), mock(McpEmployeeProtocolService.class), platform);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(endpoint)
                .addFilters(new McpRequestBodyLimitFilter(properties, guard, business)).build();
        var user = new PlatformUserEntity(); user.setId(7L);
        var session = new PlatformAuthenticatedSession(PlatformPrincipal.fromUser(user), "pai_19", null,
                List.of("OPERATOR"), List.of("workflow:debug", "capability:invoke"), List.of());
        var actor = new ConnectionIdentity(19L, "CODEX", session);
        String token = "rpi_" + "a".repeat(64);
        when(connections.authenticate(token)).thenReturn(Optional.of(actor));
        org.mockito.Mockito.doReturn(ResponseEntity.status(409).body(Map.of("errorCode", "WORKFLOW_TRIAL_GRAPH_UNSUPPORTED",
                "errorMessage", "草稿包含页面动作", "blockedNodes", List.of(Map.of("nodeId", "page", "nodeName", "打开单据",
                        "nodeType", "PAGE_ACTION", "reason", "可能操作页面", "credential", "private-secret")), "secret", "private-secret")))
                .when(trials).run(any(), any(), any());
        mvc.perform(request(token, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"reachai_workflow_trial_read_only",
                "arguments":{"workflowId":"wf-orders","trialId":"00000000-0000-0000-0000-000000000007",
                "expectedRevision":"saved-r1","inputParams":{}}}}
                """, true)).andExpect(status().isOk()).andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.structuredContent.errorCode").value("WORKFLOW_TRIAL_GRAPH_UNSUPPORTED"))
                .andExpect(jsonPath("$.result.structuredContent.details.blockedNodes[0].nodeName").value("打开单据"))
                .andExpect(jsonPath("$.result.structuredContent.details.secret").doesNotExist())
                .andExpect(jsonPath("$.result.structuredContent.details.blockedNodes[0].credential").doesNotExist());
        verify(audit).record(eq(session), eq("PLATFORM_AI_TOOL_STARTED"), eq("PLATFORM_AI_CONNECTION"), eq("19"), any());
    }

    @Test
    void workflowReadUsesTheSameProjectAuthorizationAsTheConsole() throws Exception {
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        var controller = new ControlRuntimePublicController(runtimeClient);
        var auth = new PlatformAuthorizationService(null, null, null, null);
        controller.setRuntimeManagementAccess(new RuntimeManagementAccess(new PlatformRequestAuthorization(auth),
                mock(CapabilityProjectOnboardingClient.class)));
        var adapter = new RuntimeMcpPlatformReadAdapter(controller, new RuntimeMcpActorScope());
        var connections = mock(PlatformAiConnectionService.class);
        var audit = mock(PlatformAuthAuditService.class);
        var protocol = new McpPlatformProtocolService(connections, adapter,
                mock(McpPlatformProjectCatalogPort.class), mock(McpPlatformCapabilityReadPort.class),
                mock(McpPlatformCapabilityInvocationPort.class), mock(McpPlatformKnowledgePort.class),
                mock(McpPlatformWorkflowAuthoringPort.class), mock(com.enterprise.ai.control.mcp.application.port.McpPlatformWorkflowTrialPort.class),
                mock(com.enterprise.ai.control.mcp.application.port.McpPlatformAgentAuthoringPort.class),
                mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAgentReleaseService.class),
                mock(com.enterprise.ai.control.identity.PlatformAiApprovalService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), audit, org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformWorkflowReleaseService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformModelCatalogPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformSkillCatalogPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeCatalogPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeSettingsPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformKnowledgeSettingsApprovalService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformPageAssistantSyncService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformWorkflowRollbackService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformKnowledgeImportService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformSkillGovernanceService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformSkillMarketPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformBusinessSystemReadPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.governance.ControlToolAclPlatformOperations.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformToolAclService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformBusinessSystemChangeService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformKnowledgeChunkService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformUploadToolsService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformSkillCapturePort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformModelWritePort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformModelChangeService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformModelAccountService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformPublicationPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformPublicationService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformAutomationPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAutomationService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformRunOpsService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAccountService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformBusinessIndexService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformA2aReadPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformOpsReadPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAgentLifecycleService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformHttpConnectionService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformIdentityMappingService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformKnowledgeBaseService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformCapabilityReviewService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformMemoryPort.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.identity.PlatformAiCallRecords.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformEmbedService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformApiMarketService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformCatalogResourceService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformProjectPagesService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformWorkflowCredentialService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformDebugSessionService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformHttpApiAcceptService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformSystemStatusService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAiCodingService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformMemoryErasureService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAgentWorkflowService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformPageWorkflowDeliveryService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformRunCandidateService.class));
        var businessProtocol = mock(McpProtocolApplicationService.class);
        var properties = new McpHubProperties();
        properties.setEnabled(true);
        var guard = new McpProtocolRequestGuard(properties);
        var json = new ObjectMapper();
        var endpoint = new McpEndpointController(guard, businessProtocol, properties, json,
                new McpAuditPayloadSanitizer(json, properties), mock(McpEmployeeProtocolService.class), protocol);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(endpoint)
                .addFilters(new McpRequestBodyLimitFilter(properties, guard, businessProtocol)).build();
        String token = "rpi_" + "a".repeat(64);
        ConnectionIdentity identity = identity();
        when(connections.authenticate(token)).thenReturn(Optional.of(identity));
        when(runtimeClient.getWorkflow("wf-orders"))
                .thenReturn(ResponseEntity.ok(Map.of("id", "wf-orders", "projectCode", "orders")));
        when(runtimeClient.getWorkflow("wf-billing"))
                .thenReturn(ResponseEntity.ok(Map.of("id", "wf-billing", "projectCode", "billing")));

        mvc.perform(request(token, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}
                """, false)).andExpect(status().isOk()).andExpect(jsonPath("$.result.protocolVersion").value("2025-11-25"));
        mvc.perform(request(token, """
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                """, true)).andExpect(status().isOk()).andExpect(jsonPath("$.result.tools[0].name").value("reachai_platform_profile"));
        mvc.perform(request(token, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"reachai_workflow_get","arguments":{"workflowId":"wf-orders"}}}
                """, true)).andExpect(status().isOk()).andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.structuredContent.data.id").value("wf-orders"));
        mvc.perform(request(token, """
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"reachai_workflow_get","arguments":{"workflowId":"wf-billing"}}}
                """, true)).andExpect(status().isOk()).andExpect(jsonPath("$.error.code").value(-32002));
        verify(audit, times(2)).record(eq(identity.session()), eq("PLATFORM_AI_TOOL_CALLED"),
                eq("PLATFORM_AI_CONNECTION"), eq("19"), any());
    }

    @Test
    void agentCreationUsesRealConsoleProjectAuthorizationAndAuditsBeforeDispatch() throws Exception {
        var runtimeClient = mock(RuntimeProxyClient.class);
        var projects = mock(CapabilityProjectOnboardingClient.class);
        var controller = new ControlRuntimePublicController(runtimeClient);
        controller.setRuntimeManagementAccess(new RuntimeManagementAccess(
                new PlatformRequestAuthorization(new PlatformAuthorizationService(null, null, null, null)), projects));
        var actorScope = new RuntimeMcpActorScope();
        var connections = mock(PlatformAiConnectionService.class);
        var audit = mock(PlatformAuthAuditService.class);
        var platform = new McpPlatformProtocolService(connections, new RuntimeMcpPlatformReadAdapter(controller, actorScope),
                mock(McpPlatformProjectCatalogPort.class), mock(McpPlatformCapabilityReadPort.class),
                mock(McpPlatformCapabilityInvocationPort.class), mock(McpPlatformKnowledgePort.class),
                mock(McpPlatformWorkflowAuthoringPort.class), mock(com.enterprise.ai.control.mcp.application.port.McpPlatformWorkflowTrialPort.class),
                new RuntimeMcpPlatformAgentAuthoringAdapter(controller, actorScope),
                mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAgentReleaseService.class),
                mock(com.enterprise.ai.control.identity.PlatformAiApprovalService.class), new ObjectMapper(), audit, org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformWorkflowReleaseService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformModelCatalogPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformSkillCatalogPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeCatalogPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeSettingsPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformKnowledgeSettingsApprovalService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformPageAssistantSyncService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformWorkflowRollbackService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformKnowledgeImportService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformSkillGovernanceService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformSkillMarketPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformBusinessSystemReadPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.governance.ControlToolAclPlatformOperations.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformToolAclService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformBusinessSystemChangeService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformKnowledgeChunkService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformUploadToolsService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformSkillCapturePort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformModelWritePort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformModelChangeService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformModelAccountService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformPublicationPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformPublicationService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformAutomationPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAutomationService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformRunOpsService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAccountService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformBusinessIndexService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformA2aReadPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformOpsReadPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAgentLifecycleService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformHttpConnectionService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformIdentityMappingService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformKnowledgeBaseService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformCapabilityReviewService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformMemoryPort.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.identity.PlatformAiCallRecords.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformEmbedService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformApiMarketService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformCatalogResourceService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformProjectPagesService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformWorkflowCredentialService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformDebugSessionService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformHttpApiAcceptService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformSystemStatusService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAiCodingService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformMemoryErasureService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAgentWorkflowService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformPageWorkflowDeliveryService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformRunCandidateService.class));
        var business = mock(McpProtocolApplicationService.class);
        var properties = new McpHubProperties(); properties.setEnabled(true);
        var guard = new McpProtocolRequestGuard(properties); var json = new ObjectMapper();
        var endpoint = new McpEndpointController(guard, business, properties, json,
                new McpAuditPayloadSanitizer(json, properties), mock(McpEmployeeProtocolService.class), platform);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(endpoint)
                .addFilters(new McpRequestBodyLimitFilter(properties, guard, business)).build();
        String token = "rpi_" + "a".repeat(64);
        PlatformUserEntity user = new PlatformUserEntity(); user.setId(7L); user.setUsername("operator");
        var session = new PlatformAuthenticatedSession(PlatformPrincipal.fromUser(user), "pai_19", LocalDateTime.now().plusDays(1),
                List.of("OPERATOR"), List.of("agent:write"), List.of(new PlatformPermissionGrant("agent:write", "PROJECT", "orders")));
        when(connections.authenticate(token)).thenReturn(Optional.of(new ConnectionIdentity(19L, "CODEX", session)));
        when(projects.getProjectById(12L)).thenReturn(Map.of("id", 12L, "projectCode", "orders"));
        when(projects.getProjectById(13L)).thenReturn(Map.of("id", 13L, "projectCode", "billing"));
        when(runtimeClient.createAgent(any())).thenAnswer(invocation -> ResponseEntity.ok(invocation.getArgument(0)));
        mvc.perform(request(token, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}
                """, false)).andExpect(status().isOk());
        mvc.perform(request(token, """
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"reachai_agent_create","arguments":{
                "agentId":"1234567890abcdef1234567890abcdef","name":"订单助手","keySlug":"orders-assistant","projectId":12,"projectCode":"orders"}}}
                """, true)).andExpect(status().isOk()).andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.structuredContent.data.name").value("订单助手"));
        mvc.perform(request(token, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"reachai_agent_create","arguments":{
                "agentId":"1234567890abcdef1234567890abcde0","name":"other","keySlug":"other-agent","projectId":13,"projectCode":"billing"}}}
                """, true)).andExpect(status().isOk()).andExpect(jsonPath("$.error.code").value(-32002));
        verify(runtimeClient, times(1)).createAgent(any());
        verify(audit, times(2)).record(eq(session), eq("PLATFORM_AI_TOOL_STARTED"),
                eq("PLATFORM_AI_CONNECTION"), eq("19"), any());
    }

    private static MockHttpServletRequestBuilder request(String token, String body, boolean version) {
        var request = post("/mcp/platform").contentType(MediaType.APPLICATION_JSON)
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + token).content(body);
        if (version) request.header("MCP-Protocol-Version", "2025-11-25");
        return request;
    }

    private static ConnectionIdentity identity() {
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(7L);
        user.setUsername("operator");
        var grant = new PlatformPermissionGrant("workflow:read", "PROJECT", "orders");
        var session = new PlatformAuthenticatedSession(PlatformPrincipal.fromUser(user), "pai_19",
                LocalDateTime.now().plusDays(1), List.of("OPERATOR"), List.of("workflow:read"), List.of(grant));
        return new ConnectionIdentity(19L, "CODEX", session);
    }
}
