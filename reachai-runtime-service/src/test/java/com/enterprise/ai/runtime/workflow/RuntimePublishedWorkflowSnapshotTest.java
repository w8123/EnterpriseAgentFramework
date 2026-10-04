package com.enterprise.ai.runtime.workflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuntimePublishedWorkflowSnapshotTest {
    @Test
    void explicitNullBlocksCallerRoutingWithoutMutatingBusinessInput() {
        var published = RuntimePublishedWorkflowSnapshot.read(version("{\"defaultModelInstanceId\":null}"));
        var caller = Map.<String, Object>of("workflowDefaultModelInstanceId", "forged", "orderId", 12);
        var input = published.executionInput(caller);
        assertTrue(input.containsKey("workflowDefaultModelInstanceId"));
        assertNull(input.get("workflowDefaultModelInstanceId"));
        assertEquals(12, input.get("orderId"));
        assertEquals(12, ((Map<?, ?>) input.get("params")).get("orderId"));
        assertFalse(((Map<?, ?>) input.get("params")).containsKey("workflowDefaultModelInstanceId"));
        assertEquals("forged", caller.get("workflowDefaultModelInstanceId"));
    }

    @Test
    void explicitAgentParamsKeepTheirOwnValues() {
        var published = RuntimePublishedWorkflowSnapshot.read(version("{\"defaultModelInstanceId\":null}"));
        var input = published.executionInput(Map.of("orderId", "root", "params", Map.of("orderId", "agent")));
        assertEquals("root", input.get("orderId"));
        assertEquals("agent", ((Map<?, ?>) input.get("params")).get("orderId"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "[]", "{}", "{\"defaultModelInstanceId\":42}",
            "{\"id\":\"another-workflow\",\"defaultModelInstanceId\":null}",
            "{\"projectId\":1.5,\"defaultModelInstanceId\":null}"})
    void rejectsIncompleteOrInconsistentPublishedSnapshots(String json) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> RuntimePublishedWorkflowSnapshot.read(version(json)));
        assertTrue(error.getMessage().startsWith("PUBLISHED_WORKFLOW_SNAPSHOT_INVALID:"));
    }

    @Test
    void onlyExecutableVersionsCanProvideDefaults() {
        var version = version("{\"defaultModelInstanceId\":\"published\"}");
        version.setStatus("DRAFT");
        assertThrows(IllegalArgumentException.class, () -> RuntimePublishedWorkflowSnapshot.read(version));
        version.setStatus("RETIRED");
        assertEquals("published", RuntimePublishedWorkflowSnapshot.read(version).defaultModelInstanceId());
    }

    private RuntimeWorkflowVersionEntity version(String snapshot) {
        var version = new RuntimeWorkflowVersionEntity();
        version.setId(44L);
        version.setWorkflowId("wf-orders");
        version.setStatus("ACTIVE");
        version.setSnapshotJson(snapshot);
        version.setGraphSpecSnapshotJson("{\"nodes\":[],\"edges\":[]}");
        return version;
    }
}
