package com.enterprise.ai.common.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class HttpApiDraftTrialPolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final String GRAPH = """
            {"nodes":[{"id":"api","type":"TOOL","ref":{"kind":"TOOL","qualifiedName":"http-api:orders:dev:api"},
             "config":{"httpApiAssetId":1,"inputMapping":{"pathParams.id":"params.id"}}},
             {"id":"variable","type":"VARIABLE_ASSIGN","config":{"assignments":{"state":"nodeOutput.api.state"}}}],
             "edges":[{"from":"api","to":"variable","condition":"always"}],"entryNodeId":"api","exitNodeIds":["variable"]}
            """;

    @Test void acceptsOnlyTheSavedApiAndLocalVariableShape() {
        var target = HttpApiDraftTrialPolicy.target(json, GRAPH);
        assertEquals(new HttpApiDraftTrialPolicy.Target("api", 1L, "http-api:orders:dev:api"), target);
        assertEquals(64, HttpApiDraftTrialPolicy.graphSha256(GRAPH).length());
        assertNotEquals(HttpApiDraftTrialPolicy.graphSha256(GRAPH), HttpApiDraftTrialPolicy.graphSha256(GRAPH + " "));
    }

    @Test void rejectsUnprovenNodesConditionsMappingsAndAssignmentsBeforeAnyDispatch() throws Exception {
        List<Consumer<ObjectNode>> mutations = List.of(
                root -> ((ObjectNode) root.at("/nodes/1")).put("type", "HTTP_REQUEST"),
                root -> ((ObjectNode) root.at("/edges/0")).put("condition", "params.allow"),
                root -> ((ObjectNode) root.at("/nodes/1")).putObject("errorPolicy").put("strategy", "FALLBACK"),
                root -> ((ObjectNode) root.at("/nodes/0/ref")).put("definitionId", 99L),
                root -> ((ObjectNode) root.at("/nodes/0/config/inputMapping")).put("headers.Authorization", "params.id"),
                root -> ((ObjectNode) root.at("/nodes/0/config/inputMapping")).put("pathParams.id", "credential.secret"),
                root -> ((ObjectNode) root.at("/nodes/1/config")).putObject("assignments"),
                root -> ((ObjectNode) root.at("/nodes/1/config/assignments")).put("state", "memory.personal"),
                root -> ((ObjectNode) root.at("/nodes/0")).put("id", "api.other")
        );
        for (var mutate : mutations) {
            ObjectNode graph = (ObjectNode) json.readTree(GRAPH);
            mutate.accept(graph);
            assertEquals("HTTP_API_TRIAL_GRAPH_UNSUPPORTED", assertThrows(IllegalArgumentException.class,
                    () -> HttpApiDraftTrialPolicy.target(json, graph.toString())).getMessage());
        }
    }

    @Test void getAloneNeverProvesReadOnlyAndMethodMustMatchOwnerContract() throws Exception {
        for (String sideEffect : List.of("UNKNOWN", "WRITE", "READ", "")) {
            assertFalse(HttpApiDraftTrialPolicy.readOnlyOwner("GET", json.readTree(
                    "{\"identity\":{\"method\":\"GET\"},\"sideEffect\":\"" + sideEffect + "\"}")));
        }
        var readOnly = json.readTree("{\"identity\":{\"method\":\"GET\"},\"sideEffect\":\"READ_ONLY\"}");
        assertTrue(HttpApiDraftTrialPolicy.readOnlyOwner("GET", readOnly));
        assertFalse(HttpApiDraftTrialPolicy.readOnlyOwner("POST", readOnly));
        assertFalse(HttpApiDraftTrialPolicy.readOnlyOwner("HEAD", readOnly));
    }
}
