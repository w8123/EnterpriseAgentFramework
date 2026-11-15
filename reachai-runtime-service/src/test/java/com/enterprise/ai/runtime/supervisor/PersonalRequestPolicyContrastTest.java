package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigSnapshot;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolSnapshot;
import com.enterprise.ai.runtime.execution.RuntimeSupervisorApprovalPort.PolicyApprovalGrant;
import com.enterprise.ai.runtime.runops.RuntimeGuardDecisionWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Compares policy decisions only; this is not an end-to-end page action test. */
class PersonalRequestPolicyContrastTest {
    @Test void originalRequestIsDeniedAsLegacyPageActionButWriteNeedsMatchingApproval() {
        var approvals = mock(SupervisorApprovalInteractionService.class);
        var service = new SupervisorToolPolicyService(mock(RuntimeGuardDecisionWriter.class), approvals, new ObjectMapper());
        var trace = new SupervisorExecutionTraceService.TraceHandle("trace", "span", 1L, LocalDateTime.now());
        var agent = new RuntimeAgentView("agent", 7L, "mall", "assistant", "商城助手", null,
                "PROJECT", "[\"user\"]", true, 7L, 7L, 1, "ACTIVE", "AGENTSCOPE", 1, null, null);
        var config = RuntimeAgentConfigSnapshot.builder().id(7L).agentId("agent")
                .toolCatalogMode("ALLOW_LIST").policyProfile("STANDARD")
                .configJson("{\"policy\":{\"allowedTenantIds\":[\"tenant\"],\"permissionRoles\":{\"profile:edit\":[\"user\"]}}}").build();
        var input = Map.<String, Object>of("message", "帮我改一下个人信息\n昵称 小黑\n个性签名 你好世界\n地址 山东省青岛市崂山区xxx路xx号\n电话17888888008",
                "projectCode", "mall", "tenantId", "tenant", "roles", List.of("user"), "pageKey", "home");
        var args = Map.<String, Object>of("nickName", "小黑", "introduceSign", "你好世界", "address", "山东省青岛市崂山区xxx路xx号");
        var page = tool("PAGE_ACTION");
        var denied = service.evaluate(trace, agent, config, page, input, args, null);
        assertFalse(denied.allowed());
        assertFalse(denied.confirmationRequired());
        var write = tool("WRITE");
        when(approvals.create(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new SupervisorApprovalInteractionService.ApprovalRequest("approval", Map.of("component", "confirm")));
        var pending = service.evaluate(trace, agent, config, write, input, args, null);
        assertFalse(pending.allowed()); assertTrue(pending.confirmationRequired());
        var exact = new PolicyApprovalGrant("approval", "profile:edit", "profile", args, "user");
        assertTrue(service.evaluate(trace, agent, config, write, input, args, exact).allowed());
        assertTrue(service.evaluate(trace, agent, config, write, input, Map.of("nickName", "另一个值"), exact).confirmationRequired());
    }

    private RuntimeAgentWorkflowToolSnapshot tool(String risk) {
        return RuntimeAgentWorkflowToolSnapshot.builder().toolName("profile").workflowId("wf")
                .riskLevel(risk).permissionKey("profile:edit").readOnly(false).enabled(true).build();
    }
}
