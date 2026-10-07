package com.enterprise.ai.common.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowReadOnlyTrialPolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    static final String GRAPH = """
            {"nodes":[{"id":"method","type":"TOOL","ref":{"kind":"TOOL","qualifiedName":"orders:normalize"},
            "config":{"inputMapping":{"orderNo":"params.orderNo"}}},
            {"id":"variable","type":"VARIABLE_ASSIGN","config":{"assignments":{"normalized":"nodeOutput.method.data"}}}],
            "edges":[{"from":"method","to":"variable","condition":"always"}],"entryNodeId":"method","exitNodeIds":["variable"]}
            """;
    private ConsoleCapabilityInvocationContracts.InvocationContext owner() {
        return new ConsoleCapabilityInvocationContracts.InvocationContext(1, "orders_normalize", "orders:normalize",
                "orders:normalize", "BUSINESS_METHOD", 41L, "orders", "a".repeat(64), "a".repeat(64), "a".repeat(64),
                "READY", true, "READ_ONLY", List.of(new ConsoleCapabilityInvocationContracts.Parameter("orderNo", "java.lang.String",
                null, true, "BODY", List.of(), Map.of())), null, "java.lang.String", null, null, "UNKNOWN",
                true, false, true, null, null, 30_000, "d".repeat(64));
    }
    @Test void oneScalarMethodUsesTheSharedEnvelopeAndStableSdkDataSource() {
        var target = WorkflowReadOnlyTrialPolicy.target(json, GRAPH);
        assertEquals("BUSINESS_METHOD", target.assetType());
        assertNull(WorkflowReadOnlyTrialPolicy.businessOwnerRejection(owner(), 41L, "orders", target.qualifiedName()));
        assertEquals(Map.of("orderNo", "A-1024"), WorkflowReadOnlyTrialPolicy.businessInput(json, GRAPH, target, owner(), Map.of("orderNo", "A-1024")));
        var allowed = WorkflowReadOnlyTrialPolicy.AllowedTarget.businessMethod(target, owner());
        assertEquals(owner().acceptedContractHash(), allowed.acceptedContractHash());
        assertEquals(owner().sourceContractHash(), allowed.sourceContractHash());
        assertEquals("orders_normalize", allowed.methodName());
        assertEquals(owner().executionRevision(), allowed.executionRevision());
    }
    @Test void complexMixedPinnedConditionalRetryFallbackAndForeignReturnShapesAreRejected() throws Exception {
        for (String mutation : List.of("extra", "pin", "hash", "conditional", "fallback", "retry", "mapping", "foreignOutput", "undeclaredOutput", "args")) {
            ObjectNode graph = (ObjectNode) json.readTree(GRAPH);
            ObjectNode method = (ObjectNode) graph.path("nodes").get(0);
            switch (mutation) {
                case "extra" -> graph.withArray("nodes").addObject().put("type", "HTTP_REQUEST");
                case "pin" -> ((ObjectNode) method.path("ref")).put("definitionId", 7);
                case "hash" -> ((ObjectNode) method.path("ref")).put("contractHash", "a".repeat(64));
                case "conditional" -> ((ObjectNode) graph.path("edges").get(0)).put("condition", "params.allow");
                case "fallback", "retry" -> method.putObject("errorPolicy").put("strategy", mutation.toUpperCase());
                case "mapping" -> ((ObjectNode) method.path("config").path("inputMapping")).put("orderNo", "metadata.tenantId");
                case "foreignOutput" -> ((ObjectNode) graph.path("nodes").get(1).path("config").path("assignments")).put("normalized", "nodeOutput.other.data");
                case "undeclaredOutput" -> ((ObjectNode) graph.path("nodes").get(1).path("config").path("assignments")).put("normalized", "nodeOutput.method.data.notADeclaredScalarField");
                case "args" -> ((ObjectNode) method.path("config")).putObject("args").put("orderNo", "hidden");
            }
            assertThrows(IllegalArgumentException.class, () -> WorkflowReadOnlyTrialPolicy.target(json, graph.toString()), mutation);
        }
    }
    @Test void requiredWrongTypeAndUnknownMappingCannotReachExecution() {
        var target = WorkflowReadOnlyTrialPolicy.target(json, GRAPH);
        assertEquals("BUSINESS_METHOD_TRIAL_INPUT_REQUIRED", assertThrows(IllegalArgumentException.class,
                () -> WorkflowReadOnlyTrialPolicy.businessInput(json, GRAPH, target, owner(), Map.of())).getMessage());
        assertEquals("BUSINESS_METHOD_TRIAL_INPUT_INVALID", assertThrows(IllegalArgumentException.class,
                () -> WorkflowReadOnlyTrialPolicy.businessInput(json, GRAPH, target, owner(), Map.of("orderNo", 7))).getMessage());
        String wrong = GRAPH.replace("\"orderNo\":\"params.orderNo\"", "\"unknown\":\"params.orderNo\"");
        assertEquals("BUSINESS_METHOD_TRIAL_MAPPING_INVALID", assertThrows(IllegalArgumentException.class,
                () -> WorkflowReadOnlyTrialPolicy.businessInput(json, wrong, target, owner(), Map.of("orderNo", "A"))).getMessage());
    }
    @Test void ownerTruthNeverUsesAnExecutableBooleanAlone() throws Exception {
        for (var item : Map.<String, Object>of("sourceAvailability", "DRIFT", "sideEffect", "WRITE", "enabled", false,
                "credentialAvailable", false, "businessIdentityRequired", true, "acceptedContractHash", "b".repeat(64),
                "sourceContractHash", "b".repeat(64), "projectCode", "other", "assetType", "UNCLASSIFIED").entrySet()) {
            ObjectNode changed = json.valueToTree(owner()); changed.set(item.getKey(), json.valueToTree(item.getValue()));
            assertNotNull(WorkflowReadOnlyTrialPolicy.businessOwnerRejection(json.treeToValue(changed,
                    ConsoleCapabilityInvocationContracts.InvocationContext.class), 41L, "orders", "orders:normalize"), item.getKey());
        }
    }
}
