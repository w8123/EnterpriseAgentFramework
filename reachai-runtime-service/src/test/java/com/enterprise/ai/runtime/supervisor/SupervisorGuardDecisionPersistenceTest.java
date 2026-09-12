package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigSnapshot;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolSnapshot;
import com.enterprise.ai.runtime.supervisor.SupervisorRuntimeAdapter.RemoteAgentBinding;
import com.enterprise.ai.runtime.execution.policy.RuntimeEvalExecutionContext;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.runops.RuntimeGuardDecisionLogEntity;
import com.enterprise.ai.runtime.runops.RuntimeGuardDecisionLogMapper;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupervisorGuardDecisionPersistenceTest {
    private RuntimeQueryTestDatabase database;
    private RuntimeGuardDecisionLogMapper logs;
    private SupervisorToolPolicyService policy;
    private SupervisorApprovalInteractionService approvals;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private RuntimeAgentView agent;
    private RuntimeAgentConfigSnapshot config;
    private SupervisorExecutionTraceService.TraceHandle trace;

    @BeforeEach
    void setUp() throws Exception {
        database = new RuntimeQueryTestDatabase(List.of("runtime_guard_decision_log"), RuntimeGuardDecisionLogMapper.class);
        logs = spy(database.mapper(RuntimeGuardDecisionLogMapper.class));
        approvals = mock(SupervisorApprovalInteractionService.class);
        policy = new SupervisorToolPolicyService(new com.enterprise.ai.runtime.runops.RuntimeGuardDecisionWriter(logs), approvals, json);
        agent = new RuntimeAgentView("agent-1", 7L, "orders", "order-agent", "订单助手", null,
                "PROJECT", null, true, 12L, 12L, 1, "ACTIVE", "AGENTSCOPE", 1, null, null);
        config = RuntimeAgentConfigSnapshot.builder().id(12L).agentId("agent-1")
                .toolCatalogMode("ALLOW_LIST").policyProfile("STANDARD").configJson("{\"policy\":{}}").build();
        trace = new SupervisorExecutionTraceService.TraceHandle("trace-guard", "root", 1L, LocalDateTime.now());
        var approval = new SupervisorApprovalInteractionService.ApprovalRequest("spv_guard", Map.of("component", "confirm"));
        when(approvals.create(any(), any(), any(), any(), anyMap(), anyMap(), anyString(), any())).thenReturn(approval);
        when(approvals.create(any(), any(), any(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), anyMap(), anyString(), any())).thenReturn(approval);
    }

    @AfterEach
    void close() { if (database != null) database.close(); }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void callerProjectTextCannotReplaceTheAgentsProjectInGuardEvidence(boolean a2a) {
        var input = input();
        input.put("projectCode", "forged-project");
        assertFalse(evaluate(a2a, "READ", input, identity(), RuntimeEvalExecutionContext.none()).allowed());
        assertEquals(7L, row().getProjectId());
        assertEquals("orders", row().getProjectCode());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void auditTenantComesFromTheSeparateTrustedIdentity(boolean a2a) {
        var input = input();
        input.put("tenantId", "forged-tenant");
        evaluate(a2a, "READ", input, identity(), RuntimeEvalExecutionContext.none());
        assertEquals("tenant-a", row().getTenantId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publicAuditMetadataUsesTheSameTrustedScopeAsTheRow(boolean a2a) throws Exception {
        var input = input();
        input.put("projectCode", "forged-project");
        input.put("tenantId", "forged-tenant");
        evaluate(a2a, "READ", input, identity(), RuntimeEvalExecutionContext.none());
        var metadata = json.readTree(row().getMetadataJson());
        assertEquals("orders", metadata.path("projectCode").asText());
        assertEquals("tenant-a", metadata.path("tenantId").asText());
        assertFalse(row().getMetadataJson().contains("forged-"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anUntrustedInputCannotInventTenantOwnershipForTheAudit(boolean a2a) throws Exception {
        evaluate(a2a, "READ", input(), null, RuntimeEvalExecutionContext.none());
        assertNull(row().getTenantId());
        assertFalse(json.readTree(row().getMetadataJson()).has("tenantId"));
    }

    @Test
    void a2aGuardEvidenceNamesTheActualTargetKind() {
        assertTrue(evaluate(true, "READ", input(), identity(), RuntimeEvalExecutionContext.none()).allowed());
        assertEquals("A2A_REMOTE_AGENT", row().getTargetKind());
        assertEquals("远端订单助手", row().getTargetName());
    }

    @Test
    void workflowAllowanceRemainsOneScopedGuardDecision() {
        assertTrue(evaluate(false, "READ", input(), identity(), RuntimeEvalExecutionContext.none()).allowed());
        var row = row();
        assertEquals("ALLOW", row.getDecision());
        assertEquals("SUPERVISOR_TOOL_POLICY", row.getDecisionType());
        assertEquals("WORKFLOW_TOOL", row.getTargetKind());
        assertEquals("订单查询", row.getTargetName());
        assertEquals("trace-guard", row.getTraceId());
        assertEquals("TEST", row.getEnvironment());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void approvalRequiredDecisionsAreActuallyPersisted(boolean a2a) throws Exception {
        var decision = evaluate(a2a, "WRITE", input(), identity(), RuntimeEvalExecutionContext.none());
        assertTrue(decision.confirmationRequired());
        var row = row();
        assertNotNull(row, "A confirmation decision must not disappear at the SQL boundary");
        assertEquals("REQUIRE_CONFIRMATION", row.getDecision());
        assertEquals("spv_guard", json.readTree(row.getMetadataJson()).path("interactionId").asText());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void evaluationBlockDecisionsAreActuallyPersisted(boolean a2a) {
        var decision = evaluate(a2a, "WRITE", input(), identity(), RuntimeEvalExecutionContext.readOnly("exp-1", "item-1", null));
        assertEquals("EVAL_SIDE_EFFECT_BLOCKED", decision.decision());
        var row = row();
        assertNotNull(row, "An evaluation rejection must not disappear at the SQL boundary");
        assertEquals("EVAL_SIDE_EFFECT_BLOCKED", row.getDecision());
        verifyNoInteractions(approvals);
    }

    @Test
    void guardWriteFailurePreservesTheExistingBestEffortPolicyDecision() {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("injected audit failure"))
                .when(logs).insert(any(RuntimeGuardDecisionLogEntity.class));
        assertTrue(evaluate(false, "READ", input(), identity(), RuntimeEvalExecutionContext.none()).allowed());
        assertNull(row());
        verify(logs).insert(any(RuntimeGuardDecisionLogEntity.class));
    }

    @Test
    void guardReasonsAndArgumentsDoNotPersistPrivateBusinessContent() throws Exception {
        evaluate(false, "READ", input(), identity(), RuntimeEvalExecutionContext.none());
        assertEquals("[omitted]", row().getReason());
        assertFalse(json.writeValueAsString(row()).contains("private-"));
        assertTrue(json.readTree(row().getMetadataJson()).has("args"));
    }

    private SupervisorToolPolicyService.PolicyDecision evaluate(boolean a2a, String risk, Map<String, Object> input,
                                                               WorkflowExecutionIdentity identity, RuntimeEvalExecutionContext evaluation) {
        Map<String, Object> args = Map.of("message", "private-message", "token", "private-token", "orderId", "private-order");
        if (a2a) {
            var binding = new RemoteAgentBinding(1L, 2L, 3L, 4L, "orders-remote", "远端订单助手", null,
                    "[]", "[]", risk, "orders:read", true);
            return policy.evaluateA2a(trace, agent, config, binding, input, args, null, identity, evaluation);
        }
        RuntimeAgentWorkflowToolSnapshot tool = RuntimeAgentWorkflowToolSnapshot.builder()
                .toolName("订单查询")
                .workflowId("wf-orders")
                .riskLevel(risk)
                .permissionKey("orders:read")
                .readOnly("READ".equals(risk))
                .enabled(true)
                .build();
        return policy.evaluate(trace, agent, config, tool, input, args, null, identity, evaluation);
    }

    private Map<String, Object> input() {
        return new LinkedHashMap<>(Map.of("projectCode", "orders", "tenantId", "tenant-a", "environment", "TEST",
                "sessionId", "session-1", "message", "查询订单", "roles", List.of()));
    }

    private WorkflowExecutionIdentity identity() {
        return WorkflowExecutionIdentity.fromAgent("tenant-a", 7L, "orders", "user-a");
    }

    private RuntimeGuardDecisionLogEntity row() {
        return logs.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RuntimeGuardDecisionLogEntity>()
                .eq("trace_id", "trace-guard"));
    }
}
