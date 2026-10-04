package com.enterprise.ai.runtime.workflow.bmapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Required body and required properties are independent facts on the real owner/pin/HTTP path. */
class HttpApiEmptyBodyWorkflowE2eIntegrationTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture fixture;

    @AfterEach void close() throws Exception { if (fixture != null) fixture.close(); }

    @Test void requiredObjectWithOnlyOptionalFieldsSendsActualEmptyJsonOnce() throws Exception {
        prepare("order-touch-required.yaml");
        var result = call(Map.of("orderId", "ORD-EMPTY"));
        assertEquals(true, result.get("success"), String.valueOf(result.get("code")));
        assertTrue(String.valueOf(result.get("answer")).contains("TOUCHED"));
        assertEquals("{}", fixture.writeWorkflowBodyEvidence().get("rawBody"));
        assertEquals("application/json", fixture.writeWorkflowBodyEvidence().get("contentType"));
        assertEquals(Map.of(), fixture.writeWorkflowActualBody());
        fixture.assertWriteApiCounts(1, 0);
    }

    @Test void optionalBodyWithNoPresentMappingsStaysAbsent() throws Exception {
        prepare("order-touch-optional.yaml");
        var result = call(Map.of("orderId", "ORD-ABSENT"));
        assertEquals(true, result.get("success"), String.valueOf(result.get("code")));
        assertNull(fixture.writeWorkflowBodyEvidence().get("rawBody"));
        assertNull(fixture.writeWorkflowBodyEvidence().get("contentType"));
        assertNull(fixture.writeWorkflowActualBody());
        fixture.assertWriteApiCounts(1, 0);
    }

    @Test void explicitNullIsNotAnEmptyObjectAndNeverDispatches() throws Exception {
        prepare("order-touch-required.yaml");
        var input = new LinkedHashMap<String, Object>(); input.put("orderId", "ORD-NULL"); input.put("caption", null);
        assertEquals("HTTP_API_WORKFLOW_INPUT_INVALID", call(input).get("code"));
        fixture.assertWriteApiCounts(0, 0);
    }

    @Test void missingRequiredFieldIsStillRejectedBeforeHttp() throws Exception {
        prepare("order-touch-required-field.yaml");
        assertEquals("HTTP_API_WORKFLOW_INPUT_INVALID", call(Map.of("orderId", "ORD-MISSING")).get("code"));
        fixture.assertWriteApiCounts(0, 0);
    }

    @Test void presentOptionalNativeFalseZeroAndEmptyStringAreNotDropped() throws Exception {
        prepare("order-touch-required.yaml");
        var result = call(Map.of("orderId", "ORD-NATIVE", "caption", "", "notify", false, "quantity", 0));
        assertEquals(true, result.get("success"), String.valueOf(result.get("code")));
        assertEquals(Map.of("caption", "", "notify", false, "quantity", 0), fixture.writeWorkflowActualBody());
        fixture.assertWriteApiCounts(1, 0);
    }

    private Map<String, Object> call(Map<String, Object> input) throws Exception {
        var result = fixture.callWriteWorkflowMcp(input);
        var evidence = new LinkedHashMap<>(fixture.writeWorkflowBodyEvidence());
        evidence.put("success", result.get("success")); evidence.put("code", result.get("code"));
        System.out.println("BMAPI_3CD_R1_JSON_BODY " + json.writeValueAsString(evidence));
        return result;
    }

    private void prepare(String source) throws Exception {
        fixture = new BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture(json);
        fixture.startWriteFixture(); fixture.prepareWriteApiBrowserSurface(source);
        fixture.discoverWriteApi(); fixture.acceptWriteApiAndConnect();
        ObjectNode graph;
        try (var resource = getClass().getResourceAsStream("/bmapi-3c-a/browser-saved-working-copy.json")) {
            assertNotNull(resource); graph = (ObjectNode) json.readTree(json.readTree(resource).path("graphSpecJson").asText());
        }
        var reference = fixture.writeApiOwner().summary().qualifiedName();
        var node = (ObjectNode) graph.path("nodes").get(0);
        ((ObjectNode) node.path("ref")).put("qualifiedName", reference).put("name", reference);
        var mapping = json.createObjectNode().put("pathParams.orderId", "params.orderId");
        for (String field : new String[]{"caption", "notify", "quantity"}) mapping.put("body." + field, "params." + field);
        var config = (ObjectNode) node.path("config"); config.set("inputMapping", mapping);
        ((ObjectNode) config.path("toolConfig")).put("ref", reference).put("qualifiedName", reference).set("inputMapping", mapping);
        node.remove("inputs");
        // Loose author schema deliberately reaches the owner binder for required/null rejection.
        var schema = json.readTree("{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"},\"caption\":{},\"notify\":{},\"quantity\":{}},\"required\":[\"orderId\"]}");
        graph.set("inputSchema", schema);
        var create = new LinkedHashMap<String, Object>();
        create.put("projectId", 41L); create.put("projectCode", "orders"); create.put("keySlug", "bmapi-3cd-empty-body");
        create.put("name", "Isolated order touch"); create.put("workflowKind", "GENERAL"); create.put("executionEngine", "GRAPH_SPEC");
        create.put("graphSpecJson", json.writeValueAsString(graph)); create.put("inputSchemaJson", json.writeValueAsString(schema));
        create.put("outputSchemaJson", "{\"type\":\"object\"}"); create.put("status", "DRAFT");
        create.put("definitionAuthority", "USER"); create.put("creationChannel", "STUDIO");
        var created = fixture.multiSourcePublicRequest("POST", "/api/workflows", create); assertEquals(200, created.statusCode());
        var workflowId = String.valueOf(fixture.writeResponse(created).get("id"));
        var working = fixture.writeResponse(fixture.multiSourcePublicRequest("GET", "/api/workflows/" + workflowId + "/working-copy", null));
        var save = new LinkedHashMap<>(create); save.remove("projectId"); save.remove("projectCode"); save.remove("status");
        save.put("baseRevision", working.get("revision"));
        var saved = fixture.multiSourcePublicRequest("PUT", "/api/workflows/" + workflowId + "/working-copy", save); assertEquals(200, saved.statusCode());
        var reopened = fixture.writeResponse(fixture.multiSourcePublicRequest("GET", "/api/workflows/" + workflowId + "/working-copy", null));
        var published = fixture.multiSourcePublicRequest("POST", "/api/workflows/" + workflowId + "/versions/publish",
                Map.of("version", "v1.0.0", "baseRevision", reopened.get("revision"), "rolloutPercent", 100));
        assertEquals(200, published.statusCode(), fixture.writeWorkflowTransportDiagnostic());
        fixture.prepareWriteWorkflowMcp(workflowId, null); assertEquals(200, fixture.publishWriteWorkflowMcp().statusCode());
        fixture.createWriteWorkflowMcpClient();
    }
}
