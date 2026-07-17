package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolEntity;
import com.enterprise.ai.runtime.supervisor.SupervisorRuntimeAdapter.PolicyApprovalGrant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SupervisorToolPolicyServiceTest {

    private final SupervisorExecutionTraceService traceService = mock(SupervisorExecutionTraceService.class);
    private final SupervisorApprovalInteractionService approvalService = mock(SupervisorApprovalInteractionService.class);
    private final SupervisorToolPolicyService service =
            new SupervisorToolPolicyService(traceService, approvalService, new ObjectMapper());
    private SupervisorExecutionTraceService.TraceHandle trace;
    private RuntimeAgentView agent;
    private RuntimeAgentConfigVersionEntity config;

    @BeforeEach
    void setUp() {
        trace = new SupervisorExecutionTraceService.TraceHandle("trace-1", "span-1", 1L, LocalDateTime.now());
        agent = new RuntimeAgentView(
                "agent-1", 7L, "qmssmp", "team-assistant", "班组助手", null,
                "PROJECT", "[\"team:user\"]", true,
                7L, 7L, 1, "ACTIVE", "AGENTSCOPE", 1, null, null);
        config = new RuntimeAgentConfigVersionEntity();
        config.setId(7L);
        config.setAgentId("agent-1");
        config.setToolCatalogMode("ALLOW_LIST");
        config.setPolicyProfile("STANDARD");
        config.setConfigJson("""
                {"policy":{"allowedTenantIds":["tenant-a"],"permissionRoles":{"team:read":["team:user"]}}}
                """);
    }

    @Test
    void allowsReadToolWhenProjectTenantRoleAndPermissionMatch() {
        RuntimeAgentWorkflowToolEntity tool = tool("READ", "team:read", true);

        SupervisorToolPolicyService.PolicyDecision decision = service.evaluate(
                trace, agent, config, tool, input("查询第一条有效班组信息"), Map.of(), null);

        assertTrue(decision.allowed());
        assertEquals("ALLOW", decision.decision());
        verifyNoInteractions(approvalService);
    }

    @Test
    void pageActionRequiresExplicitPageIntent() {
        RuntimeAgentWorkflowToolEntity tool = tool("PAGE_ACTION", "team:read", false);

        SupervisorToolPolicyService.PolicyDecision denied = service.evaluate(
                trace, agent, config, tool, input("查询第一条有效班组信息"), Map.of(), null);
        SupervisorToolPolicyService.PolicyDecision allowed = service.evaluate(
                trace, agent, config, tool, input("打开班组档案页面并查询第一条信息"), Map.of(), null);

        assertFalse(denied.allowed());
        assertEquals("DENY", denied.decision());
        assertTrue(allowed.allowed());
    }

    @Test
    void writeToolPausesForExactOneTimeApproval() {
        RuntimeAgentWorkflowToolEntity tool = tool("WRITE", "team:write", false);
        config.setConfigJson("""
                {"policy":{"allowedTenantIds":["tenant-a"],"permissionRoles":{"team:write":["team:user"]}}}
                """);
        Map<String, Object> args = Map.of("teamId", "T-1", "name", "一班");
        when(approvalService.create(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new SupervisorApprovalInteractionService.ApprovalRequest(
                        "spv_1", Map.of("component", "confirm")));

        SupervisorToolPolicyService.PolicyDecision pending = service.evaluate(
                trace, agent, config, tool, input("把 T-1 班组名称改为一班"), args, null);
        PolicyApprovalGrant exactGrant = new PolicyApprovalGrant(
                "spv_1", "team:write", "query_team", args, "u-1");
        SupervisorToolPolicyService.PolicyDecision approved = service.evaluate(
                trace, agent, config, tool, input("把 T-1 班组名称改为一班"), args, exactGrant);
        SupervisorToolPolicyService.PolicyDecision changedArgs = service.evaluate(
                trace, agent, config, tool, input("把 T-1 班组名称改为二班"),
                Map.of("teamId", "T-1", "name", "二班"), exactGrant);

        assertTrue(pending.confirmationRequired());
        assertEquals("spv_1", pending.interactionId());
        assertNotNull(pending.uiRequest());
        assertTrue(approved.allowed());
        assertTrue(changedArgs.confirmationRequired());
    }

    @Test
    void irreversibleToolIsDeniedByDefault() {
        RuntimeAgentWorkflowToolEntity tool = tool("IRREVERSIBLE", "team:delete", false);

        SupervisorToolPolicyService.PolicyDecision decision = service.evaluate(
                trace, agent, config, tool, input("删除班组 T-1"), Map.of("teamId", "T-1"), null);

        assertFalse(decision.allowed());
        assertFalse(decision.confirmationRequired());
        assertEquals("DENY", decision.decision());
    }

    @Test
    void rejectsMismatchedProjectTenantRoleAndMissingPermission() {
        RuntimeAgentWorkflowToolEntity tool = tool("READ", "team:read", true);
        assertEquals("DENY", service.evaluate(trace, agent, config, tool,
                Map.of("message", "查询", "projectCode", "other", "tenantId", "tenant-a",
                        "roles", List.of("team:user")), Map.of(), null).decision());
        assertEquals("DENY", service.evaluate(trace, agent, config, tool,
                Map.of("message", "查询", "projectCode", "qmssmp", "tenantId", "tenant-b",
                        "roles", List.of("team:user")), Map.of(), null).decision());
        assertEquals("DENY", service.evaluate(trace, agent, config, tool,
                Map.of("message", "查询", "projectCode", "qmssmp", "tenantId", "tenant-a",
                        "roles", List.of("guest")), Map.of(), null).decision());
        tool.setPermissionKey(null);
        assertEquals("DENY", service.evaluate(trace, agent, config, tool,
                input("查询"), Map.of(), null).decision());
    }

    @Test
    void rejectsInvalidPolicyJsonInsteadOfSkippingTenantAndPermissionChecks() {
        config.setConfigJson("{invalid-json");

        SupervisorToolPolicyService.PolicyDecision decision = service.evaluate(
                trace, agent, config, tool("READ", "team:read", true), input("query"), Map.of(), null);

        assertFalse(decision.allowed());
        assertEquals("DENY", decision.decision());
        assertEquals("Supervisor policy configuration is invalid", decision.reason());
    }

    private RuntimeAgentWorkflowToolEntity tool(String risk, String permissionKey, boolean readOnly) {
        RuntimeAgentWorkflowToolEntity tool = new RuntimeAgentWorkflowToolEntity();
        tool.setToolName("query_team");
        tool.setWorkflowId("wf-team");
        tool.setRiskLevel(risk);
        tool.setPermissionKey(permissionKey);
        tool.setReadOnly(readOnly);
        tool.setEnabled(true);
        return tool;
    }

    private Map<String, Object> input(String message) {
        return Map.of(
                "message", message,
                "projectCode", "qmssmp",
                "tenantId", "tenant-a",
                "roles", List.of("team:user"),
                "sessionId", "session-1",
                "userId", "u-1");
    }
}
