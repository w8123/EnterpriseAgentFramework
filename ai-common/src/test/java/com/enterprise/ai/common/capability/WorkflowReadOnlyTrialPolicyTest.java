package com.enterprise.ai.common.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowReadOnlyTrialPolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    /** The live-test shape: USER_INPUT → method → PARAMETER_EXTRACT(LLM) → API → ANSWER, plus pure helpers. */
    static final String GRAPH = """
            {"schemaVersion":2,"nodes":[
             {"id":"input","type":"USER_INPUT","config":{"fields":[{"name":"keyword","source":"input.message"}]}},
             {"id":"products","name":"查询产品","type":"TOOL","ref":{"kind":"TOOL","qualifiedName":"erp:erpProductSimpleList"},
              "config":{"inputMapping":{"name":"params.keyword"}}},
             {"id":"extract","type":"PARAMETER_EXTRACT","config":{"fields":[{"name":"productId","required":true}]}},
             {"id":"stock","name":"查询库存","type":"TOOL","ref":{"kind":"TOOL","qualifiedName":"http-api:erp:dev:stock"},
              "config":{"httpApiAssetId":7,"inputMapping":{"queryParams.productId":"var.productId"}}},
             {"id":"loop","type":"LOOP","config":{"bodyEntry":"llm"}},
             {"id":"llm","type":"LLM","config":{}},
             {"id":"show","type":"INTERACTION","config":{"interactionType":"present-output"}},
             {"id":"answer","type":"ANSWER","config":{"template":"{{var.stock}}"}}],
             "edges":[],"entryNodeId":"input","exitNodeIds":["answer"]}
            """;

    private ConsoleCapabilityInvocationContracts.InvocationContext owner() {
        return new ConsoleCapabilityInvocationContracts.InvocationContext(1, "orders_normalize", "orders:normalize",
                "orders:normalize", "BUSINESS_METHOD", 41L, "orders", "a".repeat(64), "a".repeat(64), "a".repeat(64),
                "READY", true, "READ_ONLY", List.of(new ConsoleCapabilityInvocationContracts.Parameter("orderNo", "java.lang.String",
                null, true, "BODY", List.of(), Map.of())), null, "java.lang.String", null, null, "UNKNOWN",
                true, false, true, null, null, 30_000, "d".repeat(64));
    }

    @Test void multiStepDraftPinsEveryMethodAndApiNodeAndAllowsPureNodes() {
        var plan = WorkflowReadOnlyTrialPolicy.plan(json, GRAPH);
        assertEquals(List.of(
                new WorkflowReadOnlyTrialPolicy.Target("products", "查询产品", 0, "erp:erpProductSimpleList", "BUSINESS_METHOD"),
                new WorkflowReadOnlyTrialPolicy.Target("stock", "查询库存", 7, "http-api:erp:dev:stock", "HTTP_API")),
                plan.targets());
    }

    @Test void qualifiedNameResolutionMirrorsTheRuntimeToolContract() throws Exception {
        ObjectNode graph = (ObjectNode) json.readTree(GRAPH);
        ObjectNode method = (ObjectNode) graph.path("nodes").get(1);
        method.remove("ref");
        ((ObjectNode) method.path("config")).putObject("toolConfig").putObject("ref").put("qualifiedName", "erp:nested");
        assertEquals("erp:nested", WorkflowReadOnlyTrialPolicy.plan(json, graph.toString()).targets().get(0).qualifiedName());
        // An API asset id alone selects the HTTP API branch, exactly as the Runtime TOOL handler does.
        ((ObjectNode) method.path("config")).put("httpApiAssetId", 9);
        var rejected = assertThrows(WorkflowReadOnlyTrialPolicy.GraphRejected.class,
                () -> WorkflowReadOnlyTrialPolicy.plan(json, graph.toString()));
        assertEquals("products", rejected.blocked().get(0).nodeId());
    }

    @Test void sideEffectAndWaitingNodesAreRefusedByNameBeforeAnyDispatch() throws Exception {
        Map<String, String> mutations = Map.of(
                "PAGE_ACTION", "{\"id\":\"page\",\"name\":\"打开单据\",\"type\":\"PAGE_ACTION\"}",
                "HTTP_POST", "{\"id\":\"post\",\"type\":\"HTTP_REQUEST\",\"config\":{\"method\":\"POST\",\"url\":\"https://x\"}}",
                "HTTP_CREDENTIAL", "{\"id\":\"cred\",\"type\":\"HTTP_REQUEST\",\"config\":{\"httpConfig\":{\"method\":\"GET\",\"credentialRef\":\"c1\"}}}",
                "MCP", "{\"id\":\"mcp\",\"type\":\"MCP_CALL\"}",
                "APPROVAL", "{\"id\":\"approve\",\"type\":\"HUMAN_APPROVAL\"}",
                "COLLECT", "{\"id\":\"ask\",\"type\":\"INTERACTION\",\"config\":{\"interactionType\":\"COLLECT_INPUT\"}}",
                "UNKNOWN", "{\"id\":\"code\",\"type\":\"CODE\"}",
                "UNBOUND_TOOL", "{\"id\":\"empty\",\"type\":\"TOOL\",\"config\":{}}",
                "DUPLICATE", "{\"id\":\"llm\",\"type\":\"LLM\"}");
        for (var mutation : mutations.entrySet()) {
            ObjectNode graph = (ObjectNode) json.readTree(GRAPH);
            graph.withArray("nodes").add(json.readTree(mutation.getValue()));
            var rejected = assertThrows(WorkflowReadOnlyTrialPolicy.GraphRejected.class,
                    () -> WorkflowReadOnlyTrialPolicy.plan(json, graph.toString()), mutation.getKey());
            assertEquals("WORKFLOW_TRIAL_GRAPH_UNSUPPORTED", rejected.code());
            assertEquals(1, rejected.blocked().size(), mutation.getKey());
            assertTrue(rejected.userMessage().startsWith("以下节点不能只读试运行"), rejected.userMessage());
        }
        ObjectNode graph = (ObjectNode) json.readTree(GRAPH);
        graph.withArray("nodes").add(json.readTree(mutations.get("PAGE_ACTION")));
        assertTrue(assertThrows(WorkflowReadOnlyTrialPolicy.GraphRejected.class,
                () -> WorkflowReadOnlyTrialPolicy.plan(json, graph.toString())).userMessage().contains("「打开单据」（页面动作）"));
    }

    @Test void credentialFreeGetRequestIsAPlainReadButTooManyTargetsAreRefused() throws Exception {
        ObjectNode graph = (ObjectNode) json.readTree(GRAPH);
        graph.withArray("nodes").add(json.readTree("{\"id\":\"weather\",\"type\":\"HTTP_REQUEST\",\"config\":{\"url\":\"https://x\"}}"));
        assertEquals(2, WorkflowReadOnlyTrialPolicy.plan(json, graph.toString()).targets().size());
        for (int i = 0; i < WorkflowReadOnlyTrialPolicy.MAX_TARGETS; i++) {
            graph.withArray("nodes").add(json.readTree("{\"id\":\"m" + i + "\",\"type\":\"TOOL\",\"ref\":{\"qualifiedName\":\"erp:m" + i + "\"}}"));
        }
        assertThrows(WorkflowReadOnlyTrialPolicy.GraphRejected.class, () -> WorkflowReadOnlyTrialPolicy.plan(json, graph.toString()));
        assertThrows(WorkflowReadOnlyTrialPolicy.GraphRejected.class, () -> WorkflowReadOnlyTrialPolicy.plan(json, "not json"));
    }

    @Test void allowedTargetsCarryOwnerFactsAndMatchOnlyTheirOwnNode() {
        var method = new WorkflowReadOnlyTrialPolicy.Target("method", null, 0, "orders:normalize", "BUSINESS_METHOD");
        var allowed = WorkflowReadOnlyTrialPolicy.AllowedTarget.businessMethod(method, owner());
        assertEquals("orders_normalize", allowed.methodName());
        assertEquals(owner().executionRevision(), allowed.executionRevision());
        assertTrue(allowed.sameTarget(method));
        assertFalse(allowed.sameTarget(new WorkflowReadOnlyTrialPolicy.Target("other", null, 0, "orders:normalize", "BUSINESS_METHOD")));
        var api = new WorkflowReadOnlyTrialPolicy.Target("api", "API", 1, "http-api:orders:dev:api", "HTTP_API");
        var apiAllowed = WorkflowReadOnlyTrialPolicy.AllowedTarget.httpApi(api, "dev", "a".repeat(64), "b".repeat(64));
        assertTrue(apiAllowed.sameTarget(api));
        assertFalse(apiAllowed.sameTarget(new WorkflowReadOnlyTrialPolicy.Target("api", "API", 2, "http-api:orders:dev:api", "HTTP_API")));
        assertEquals("method", method.nodeName(), "a nameless node is labelled by its id");
    }

    @Test void ownerTruthNeverUsesAnExecutableBooleanAlone() throws Exception {
        assertNull(WorkflowReadOnlyTrialPolicy.businessOwnerRejection(owner(), 41L, "orders", "orders:normalize"));
        for (var item : Map.<String, Object>of("sourceAvailability", "DRIFT", "sideEffect", "WRITE", "enabled", false,
                "credentialAvailable", false, "businessIdentityRequired", true, "acceptedContractHash", "b".repeat(64),
                "sourceContractHash", "b".repeat(64), "projectCode", "other", "assetType", "UNCLASSIFIED").entrySet()) {
            ObjectNode changed = json.valueToTree(owner()); changed.set(item.getKey(), json.valueToTree(item.getValue()));
            String code = WorkflowReadOnlyTrialPolicy.businessOwnerRejection(json.treeToValue(changed,
                    ConsoleCapabilityInvocationContracts.InvocationContext.class), 41L, "orders", "orders:normalize");
            assertNotNull(code, item.getKey());
            assertTrue(WorkflowReadOnlyTrialPolicy.ownerRejectionMessage(code, "查询产品").startsWith("「查询产品」"));
        }
        assertTrue(WorkflowReadOnlyTrialPolicy.ownerRejectionMessage("BUSINESS_METHOD_TRIAL_READ_ONLY_REQUIRED", "改库存")
                .contains("未声明为只读"));
    }
}
