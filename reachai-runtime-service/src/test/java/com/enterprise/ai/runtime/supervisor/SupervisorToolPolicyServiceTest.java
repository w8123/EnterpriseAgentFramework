package com.enterprise.ai.runtime.supervisor;


import com.enterprise.ai.runtime.agent.RuntimeAgentConfigSnapshot;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolSnapshot;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.execution.policy.RuntimeEvalExecutionContext;
import com.enterprise.ai.runtime.execution.RuntimeSupervisorApprovalPort.PolicyApprovalGrant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SupervisorToolPolicyServiceTest {

    private final SupervisorApprovalInteractionService approvalService = mock(SupervisorApprovalInteractionService.class);
    private final SupervisorToolPolicyService service =
            new SupervisorToolPolicyService(org.mockito.Mockito.mock(com.enterprise.ai.runtime.runops.RuntimeGuardDecisionWriter.class), approvalService, new ObjectMapper());
    private SupervisorExecutionTraceService.TraceHandle trace;
    private RuntimeAgentView agent;
    private RuntimeAgentConfigSnapshot config;

    @BeforeEach
    void setUp() {
        trace = new SupervisorExecutionTraceService.TraceHandle("trace-1", "span-1", 1L, LocalDateTime.now());
        agent = new RuntimeAgentView(
                "agent-1", 7L, "qmssmp", "team-assistant", "班组助手", null,
                "PROJECT", "[\"team:user\"]", true,
                7L, 7L, 1, "ACTIVE", "AGENTSCOPE", 1, null, null);
        config = RuntimeAgentConfigSnapshot.builder().id(7L).agentId("agent-1")
                .toolCatalogMode("ALLOW_LIST").policyProfile("STANDARD").configJson("""
                {"policy":{"allowedTenantIds":["tenant-a"],"permissionRoles":{"team:read":["team:user"]}}}
                """).build();
    }

    @Test
    void allowsReadToolWhenProjectTenantRoleAndPermissionMatch() {
        RuntimeAgentWorkflowToolSnapshot tool = tool("READ", "team:read", true);

        SupervisorToolPolicyService.PolicyDecision decision = service.evaluate(
                trace, agent, config, tool, input("查询第一条有效班组信息"), Map.of(), null);

        assertTrue(decision.allowed());
        assertEquals("ALLOW", decision.decision());
        verifyNoInteractions(approvalService);
    }

    @Test
    void pageActionRequiresExplicitPageIntent() {
        RuntimeAgentWorkflowToolSnapshot tool = tool("PAGE_ACTION", "team:read", false);

        SupervisorToolPolicyService.PolicyDecision denied = service.evaluate(
                trace, agent, config, tool, input("查询第一条有效班组信息"), Map.of(), null);
        SupervisorToolPolicyService.PolicyDecision allowed = service.evaluate(
                trace, agent, config, tool, input("打开班组档案页面并查询第一条信息"), Map.of(), null);
        SupervisorToolPolicyService.PolicyDecision operateCurrentPage = service.evaluate(
                trace, agent, config, tool, input("请只操作当前班组档案页面，查询负责人为管理员的班组"), Map.of(), null);
        SupervisorToolPolicyService.PolicyDecision readCurrentPageState = service.evaluate(
                trace, agent, config, tool,
                input("请读取并告诉我当前页面状态，包括页面标识、筛选值和当前可见行数"), Map.of(), null);
        SupervisorToolPolicyService.PolicyDecision readCurrentPageStateInEnglish = service.evaluate(
                trace, agent, config, tool, input("Read the current page state and visible rows"), Map.of(), null);
        Map<String, Object> naturalPageQueryInput = new LinkedHashMap<>(input("请帮我查一下已关闭订单有多少条，只读查询，不要修改数据"));
        naturalPageQueryInput.put("pageKey", "mall.oms.order");
        SupervisorToolPolicyService.PolicyDecision naturalPageQuery = service.evaluate(
                trace, agent, config, tool, naturalPageQueryInput, Map.of(), null);
        Map<String, Object> mixedReadWriteInput = new LinkedHashMap<>(input("查询后删除订单"));
        mixedReadWriteInput.put("pageKey", "mall.oms.order");
        SupervisorToolPolicyService.PolicyDecision mixedReadWrite = service.evaluate(
                trace, agent, config, tool, mixedReadWriteInput, Map.of(), null);
        Map<String, Object> embeddedDisableInput = new LinkedHashMap<>(
                input("请停用班组 test，但在真正执行前必须先让我确认；现在不要直接修改数据"));
        embeddedDisableInput.put("pageKey", "qmssmp.team.archive");
        SupervisorToolPolicyService.PolicyDecision embeddedDisable = service.evaluate(
                trace, agent, config, tool, embeddedDisableInput, Map.of(), null);
        Map<String, Object> negatedThenWriteInput = new LinkedHashMap<>(input("查询订单，不要删除 A，但删除 B"));
        negatedThenWriteInput.put("pageKey", "mall.oms.order");
        SupervisorToolPolicyService.PolicyDecision negatedThenWrite = service.evaluate(
                trace, agent, config, tool, negatedThenWriteInput, Map.of(), null);
        Map<String, Object> englishWriteInput = new LinkedHashMap<>(input("查询 orders and then delete one"));
        englishWriteInput.put("pageKey", "mall.oms.order");
        SupervisorToolPolicyService.PolicyDecision englishWrite = service.evaluate(
                trace, agent, config, tool, englishWriteInput, Map.of(), null);
        Map<String, Object> negatedOnlyInput = new LinkedHashMap<>(input("不要停用班组 test"));
        negatedOnlyInput.put("pageKey", "qmssmp.team.archive");
        SupervisorToolPolicyService.PolicyDecision negatedOnly = service.evaluate(
                trace, agent, config, tool, negatedOnlyInput, Map.of(), null);

        assertFalse(denied.allowed());
        assertEquals("DENY", denied.decision());
        assertTrue(allowed.allowed());
        assertTrue(operateCurrentPage.allowed());
        assertTrue(readCurrentPageState.allowed());
        assertTrue(readCurrentPageStateInEnglish.allowed());
        assertTrue(naturalPageQuery.allowed());
        assertTrue(mixedReadWrite.allowed());
        assertTrue(embeddedDisable.allowed());
        assertTrue(negatedThenWrite.allowed());
        assertTrue(englishWrite.allowed());
        assertFalse(negatedOnly.allowed());
    }

    @Test
    void recognizesPositiveControlOperationsWithoutTreatingNegatedCommandsAsConsent() {
        RuntimeAgentWorkflowToolSnapshot tool = tool("PAGE_ACTION", "team:read", false);
        for (String message : List.of(
                "请将首页搜索框填写手机，仅填写，不要搜索",
                "把搜索框填入手机",
                "请清空当前搜索框",
                "不要搜索，但是在搜索框输入手机",
                "Please fill the search box with phone")) {
            var request = new LinkedHashMap<>(input(message));
            request.put("pageKey", "mall-home");
            assertTrue(service.evaluate(trace, agent, config, tool, request, Map.of(), null).allowed(), message);
        }
        for (String message : List.of(
                "不要在页面上输入手机",
                "我不想你在页面上输入手机",
                "我不在页面上输入手机",
                "我不会在页面上输入手机",
                "不用在页面上填写手机",
                "不把手机填入搜索框",
                "不要清空搜索框，请查询我的订单",
                "查询订单，不要操作页面",
                "查询订单，别动页面",
                "查看订单，不要改搜索框",
                "查询订单，不要删除页面上的内容",
                "请查询订单，但是不要点击按钮",
                "如果我说打开页面，请不要操作",
                "说明里写着\u201c打开页面\u201d，请不要操作",
                "我没有要求你在页面上填写手机",
                "请勿填写搜索框",
                "别清空搜索框",
                "不输入搜索框",
                "禁止打开页面",
                "Do not fill the search box on the page",
                "Without clearing the search box, open the page",
                "please inspect data, do not click the button",
                "show data, do not touch the page",
                "页面上现在有手机")) {
            var request = new LinkedHashMap<>(input(message));
            request.put("pageKey", "mall-home");
            assertFalse(service.evaluate(trace, agent, config, tool, request, Map.of(), null).allowed(), message);
        }
        assertFalse(service.evaluate(trace, agent, config, tool,
                input("请将首页搜索框填写手机，仅填写，不要搜索"), Map.of(), null).allowed());
        var unauthorized = new LinkedHashMap<>(input("请将首页搜索框填写手机"));
        unauthorized.put("pageKey", "mall-home");
        unauthorized.put("tenantId", "other-tenant");
        assertFalse(service.evaluate(trace, agent, config, tool, unauthorized, Map.of(), null).allowed());
    }

    @Test
    void writeToolPausesForExactOneTimeApproval() {
        RuntimeAgentWorkflowToolSnapshot tool = tool("WRITE", "team:write", false);
        config = config.toBuilder().configJson("""
                {"policy":{"allowedTenantIds":["tenant-a"],"permissionRoles":{"team:write":["team:user"]}}}
                """).build();
        Map<String, Object> args = Map.of("teamId", "T-1", "name", "一班");
        when(approvalService.create(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new SupervisorApprovalInteractionService.ApprovalRequest(
                        "spv_1", Map.of("component", "confirm")));
        WorkflowExecutionIdentity trustedIdentity =
                WorkflowExecutionIdentity.fromAgent("default", 7L, "qmssmp", "u-1");

        SupervisorToolPolicyService.PolicyDecision pending = service.evaluate(
                trace, agent, config, tool, input("把 T-1 班组名称改为一班"), args, null, trustedIdentity);
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
        verify(approvalService).create(
                any(), any(), any(), any(), any(), any(), any(), same(trustedIdentity));
        assertTrue(approved.allowed());
        assertTrue(changedArgs.confirmationRequired());
    }

    @Test
    void irreversibleToolIsDeniedByDefault() {
        RuntimeAgentWorkflowToolSnapshot tool = tool("IRREVERSIBLE", "team:delete", false);

        SupervisorToolPolicyService.PolicyDecision decision = service.evaluate(
                trace, agent, config, tool, input("删除班组 T-1"), Map.of("teamId", "T-1"), null);

        assertFalse(decision.allowed());
        assertFalse(decision.confirmationRequired());
        assertEquals("DENY", decision.decision());
    }

    @Test
    void evalBlocksWriteBeforeCreatingApprovalButStillAllowsRead() {
        RuntimeAgentWorkflowToolSnapshot write = tool("WRITE", "team:write", false);
        RuntimeAgentWorkflowToolSnapshot read = tool("READ", "team:read", true);
        config = config.toBuilder().configJson("""
                {"policy":{"allowedTenantIds":["tenant-a"],"permissionRoles":{
                  "team:write":["team:user"],"team:read":["team:user"]}}}
                """).build();
        RuntimeEvalExecutionContext evaluation =
                RuntimeEvalExecutionContext.readOnly("exp-1", "item-1", null);

        SupervisorToolPolicyService.PolicyDecision blocked = service.evaluate(
                trace, agent, config, write, input("修改班组"), Map.of(), null, null, evaluation);
        SupervisorToolPolicyService.PolicyDecision allowed = service.evaluate(
                trace, agent, config, read, input("查询班组"), Map.of(), null, null, evaluation);

        assertFalse(blocked.allowed());
        assertFalse(blocked.confirmationRequired());
        assertEquals("EVAL_SIDE_EFFECT_BLOCKED", blocked.decision());
        assertTrue(allowed.allowed());
        verifyNoInteractions(approvalService);
    }

    @Test
    void rejectsMismatchedProjectTenantRoleAndMissingPermission() {
        RuntimeAgentWorkflowToolSnapshot tool = tool("READ", "team:read", true);
        assertEquals("DENY", service.evaluate(trace, agent, config, tool,
                Map.of("message", "查询", "projectCode", "other", "tenantId", "tenant-a",
                        "roles", List.of("team:user")), Map.of(), null).decision());
        assertEquals("DENY", service.evaluate(trace, agent, config, tool,
                Map.of("message", "查询", "projectCode", "qmssmp", "tenantId", "tenant-b",
                        "roles", List.of("team:user")), Map.of(), null).decision());
        assertEquals("DENY", service.evaluate(trace, agent, config, tool,
                Map.of("message", "查询", "projectCode", "qmssmp", "tenantId", "tenant-a",
                        "roles", List.of("guest")), Map.of(), null).decision());
        tool = tool.toBuilder().permissionKey(null).build();
        assertEquals("DENY", service.evaluate(trace, agent, config, tool,
                input("查询"), Map.of(), null).decision());
    }

    @Test
    void rejectsInvalidPolicyJsonInsteadOfSkippingTenantAndPermissionChecks() {
        config = config.toBuilder().configJson("{invalid-json").build();

        SupervisorToolPolicyService.PolicyDecision decision = service.evaluate(
                trace, agent, config, tool("READ", "team:read", true), input("query"), Map.of(), null);

        assertFalse(decision.allowed());
        assertEquals("DENY", decision.decision());
        assertEquals("Supervisor policy configuration is invalid", decision.reason());
    }

    private RuntimeAgentWorkflowToolSnapshot tool(String risk, String permissionKey, boolean readOnly) {
        RuntimeAgentWorkflowToolSnapshot tool = RuntimeAgentWorkflowToolSnapshot.builder()
                .toolName("query_team")
                .workflowId("wf-team")
                .riskLevel(risk)
                .permissionKey(permissionKey)
                .readOnly(readOnly)
                .enabled(true)
                .build();
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
