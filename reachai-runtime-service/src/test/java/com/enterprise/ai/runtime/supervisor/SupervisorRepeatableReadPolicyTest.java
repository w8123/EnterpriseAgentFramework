package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolSnapshot;
import com.enterprise.ai.runtime.agent.RuntimeResolvedWorkflowTarget;
import com.enterprise.ai.runtime.workflow.RuntimeCapabilityContractPins;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowPublishedVersionView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SupervisorRepeatableReadPolicyTest {
    private static final String READ_GRAPH = """
            {"nodes":[{"id":"start","type":"USER_INPUT"},
            {"id":"read","type":"TOOL","ref":{"qualifiedName":"project:query","assetType":"BUSINESS_METHOD","assetId":9}},
            {"id":"end","type":"ANSWER"}]}
            """;
    private final RuntimeCapabilityContractPins pins = mock(RuntimeCapabilityContractPins.class);
    private final SupervisorRepeatableReadPolicy policy = new SupervisorRepeatableReadPolicy(new ObjectMapper(), pins);
    private final RuntimeAgentWorkflowToolSnapshot read = RuntimeAgentWorkflowToolSnapshot.builder()
            .toolName("query").riskLevel("READ").readOnly(true).build();

    @Test
    void acceptsOnlyCurrentOwnerVerifiedReadAndRechecksChanges() {
        when(pins.businessMethodRiskFloor(READ_GRAPH)).thenReturn("READ", "WRITE");
        assertTrue(policy.isRepeatable(read, target(READ_GRAPH)));
        assertFalse(policy.isRepeatable(read, target(READ_GRAPH)));
        verify(pins, times(2)).businessMethodRiskFloor(READ_GRAPH);
    }

    @Test
    void rejectsSideEffectsUnknownNodesRawHttpAndInteractionsEvenWhenLabeledRead() {
        when(pins.businessMethodRiskFloor(anyString())).thenReturn("READ");
        for (String type : new String[]{"PAGE_ACTION", "INTERACTION", "HUMAN_APPROVAL", "MCP_CALL", "CODE", "HTTP_REQUEST", "UNKNOWN"}) {
            String unsafe = READ_GRAPH.replace("{\"id\":\"end\",\"type\":\"ANSWER\"}",
                    "{\"id\":\"end\",\"type\":\"" + type + "\"}");
            assertFalse(policy.isRepeatable(read, target(unsafe)), type);
        }
        String httpApi = READ_GRAPH.replace("project:query", "http-api:project:query")
                .replace("\"assetId\":9", "\"assetId\":9},\"config\":{\"httpApiAssetId\":9");
        assertFalse(policy.isRepeatable(read, target(httpApi)));
        assertFalse(policy.isRepeatable(read, target("{\"nodes\":[]}")));
        assertFalse(policy.isRepeatable(read, target("not-json")));
        assertFalse(policy.isRepeatable(RuntimeAgentWorkflowToolSnapshot.builder().riskLevel("WRITE").readOnly(true).build(), target(READ_GRAPH)));
        assertFalse(policy.isRepeatable(RuntimeAgentWorkflowToolSnapshot.builder().riskLevel("READ").readOnly(false).build(), target(READ_GRAPH)));
    }

    @Test
    void rejectsUnavailableOrUnverifiedOwner() {
        when(pins.businessMethodRiskFloor(anyString())).thenThrow(new IllegalStateException("unavailable"));
        assertFalse(policy.isRepeatable(read, target(READ_GRAPH)));
        assertFalse(policy.isRepeatable(read, null));
    }

    private static RuntimeResolvedWorkflowTarget target(String graph) {
        return new RuntimeResolvedWorkflowTarget(null, null,
                RuntimeWorkflowPublishedVersionView.builder().graphSpecSnapshotJson(graph).build());
    }
}
