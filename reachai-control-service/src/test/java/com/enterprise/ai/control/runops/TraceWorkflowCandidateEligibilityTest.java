package com.enterprise.ai.control.runops;

import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class TraceWorkflowCandidateEligibilityTest {

    private final TraceWorkflowCandidateEligibility service =
            new TraceWorkflowCandidateEligibility(
                    mock(RuntimeProxyClient.class),
                    new ObjectMapper());

    @Test
    void acceptsOnlyOneSuccessfulExplicitlyReadOnlyWorkflowPath() {
        var result = service.evaluate(detail(true, "READ", 0, 1), "trace-1");

        assertTrue(result.eligible());
        assertTrue(result.blockers().isEmpty());
        assertEquals("wf-1", result.sourceWorkflowId());
        assertEquals(22L, result.sourceWorkflowVersionId());
    }

    @Test
    void rejectsPageActionEvenWhenRunSucceeded() {
        var result = service.evaluate(
                detail(false, "PAGE_ACTION", 0, 1), "trace-1");

        assertFalse(result.eligible());
        assertTrue(result.blockers().stream().anyMatch(message ->
                message.contains("未显式证明") || message.contains("PAGE_ACTION")));
    }

    @Test
    void rejectsReplannedOrMultiWorkflowRun() {
        var result = service.evaluate(detail(true, "READ", 1, 2), "trace-1");

        assertFalse(result.eligible());
        assertTrue(result.blockers().stream().anyMatch(message ->
                message.contains("重规划")));
        assertTrue(result.blockers().stream().anyMatch(message ->
                message.contains("恰好调用一个")));
    }

    private Map<String, Object> detail(
            boolean readOnly,
            String riskLevel,
            int replanCount,
            int workflowCallCount) {
        return Map.of(
                "summary", Map.ofEntries(
                        Map.entry("traceId", "trace-1"),
                        Map.entry("projectCode", "orders"),
                        Map.entry("status", "COMPLETED"),
                        Map.entry("runType", "AGENT"),
                        Map.entry("runtimeType", "AGENTSCOPE"),
                        Map.entry("planCount", 1),
                        Map.entry("replanCount", replanCount),
                        Map.entry("workflowCallCount", workflowCallCount),
                        Map.entry("toolCallCount", 1),
                        Map.entry("approvalCount", 0),
                        Map.entry("guardDenyCount", 0)),
                "toolCalls", List.of(Map.of("success", true)),
                "guardDecisions", List.of(Map.of(
                        "decisionType", "SUPERVISOR_TOOL_POLICY",
                        "targetKind", "WORKFLOW_TOOL",
                        "decision", "ALLOW",
                        "metadata", Map.of(
                                "readOnly", readOnly,
                                "riskLevel", riskLevel))),
                "spans", List.of(Map.of(
                        "spanType", "WORKFLOW_TOOL",
                        "status", "SUCCESS",
                        "toolName", "orders_read",
                        "metadata", Map.of(
                                "workflowId", "wf-1",
                                "workflowVersionId", 22L,
                                "workflowVersion", "v1.0.1"))),
                "executionPath", List.of(Map.of(
                        "spanType", "WORKFLOW_NODE",
                        "status", "SUCCESS")));
    }
}
