package com.enterprise.ai.runtime.workflow.bmapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real browser author flow. Normal owner APIs, Control/HMAC, H2 and an actual stateful POST. */
class HttpApiWriteWorkflowBrowserLauncherTest {
    @Test void waitsForNormalPostPickerMappingsSaveReopenPublicationAndTraceReads() throws Exception {
        String directory = System.getProperty("bmapi.writeWorkflowBrowser.directory");
        Assumptions.assumeTrue(directory != null, "opt-in isolated browser gate");
        Path root = Path.of(directory).toAbsolutePath().normalize(); Files.createDirectories(root);
        Path manifest = root.resolve("write-workflow-browser-manifest.json"); assertFalse(Files.exists(manifest));
        var json = new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        long deadline = System.nanoTime() + Duration.ofMinutes(90).toNanos();
        try (var f = new BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture(json)) {
            f.startWriteFixture(); var surface = f.prepareWriteApiBrowserSurface("order-notes-workflow.yaml");
            f.discoverWriteApi(); f.acceptWriteApiAndConnect();
            String workflowId = createEmptyAuthorDraft(f, json);
            var state = new LinkedHashMap<String, Object>();
            state.put("controlOrigin", surface.controlOrigin()); state.put("upstreamOrigin", surface.upstreamOrigin());
            state.put("authStatePath", surface.authStatePath()); state.put("scanPath", surface.scanPath());
            state.put("workflowId", workflowId); state.put("apiId", 1); state.put("qualifiedName", f.writeApiOwner().summary().qualifiedName());
            state.put("projectId", 41); state.put("projectCode", "orders"); state.put("environment", "dev");
            ready(manifest, json, state, "READY_FOR_NORMAL_POST_AUTHORING");
            await(root, "workflow-published", deadline);
            var persisted = f.writeWorkflowPublishedEvidence(workflowId);
            var working = json.valueToTree(persisted.get("workingCopy"));
            var graph = json.readTree(working.path("graphSpecJson").asText());
            var api = graph.path("nodes").get(0);
            assertEquals(f.writeApiOwner().summary().qualifiedName(), api.path("ref").path("qualifiedName").asText());
            assertEquals(1, api.path("config").path("httpApiAssetId").asInt());
            for (String field : List.of("note", "apiKey", "accessCode", "deliveryMark", "quantity", "rate", "caption", "notify", "kind")) {
                assertEquals("params." + field, api.path("config").path("inputMapping").path("body." + field).asText());
            }
            assertEquals("nodeOutput.api-node.state", graph.path("nodes").get(1).path("config").path("assignments").path("order_state").asText());
            assertFalse(working.path("graphSpecJson").asText().contains(surface.upstreamOrigin()));
            assertFalse(working.path("graphSpecJson").asText().contains(StatefulWriteApiFixtureController.PROJECT_SECRET));
            f.assertWriteApiCounts(0, 0);
            f.prepareWriteWorkflowMcp(workflowId, null); assertEquals(200, f.publishWriteWorkflowMcp().statusCode()); f.createWriteWorkflowMcpClient();
            var call = f.callWriteWorkflowMcp(input()); assertEquals(true, call.get("success"), String.valueOf(call.get("code")));
            assertTrue(String.valueOf(call.get("answer")).contains("NOTED")); assertTrue(String.valueOf(call.get("answer")).contains("order_state"));
            assertEquals(false, f.writeWorkflowActualBody().get("notify")); assertEquals(0, f.writeWorkflowActualBody().get("quantity"));
            assertEquals("", f.writeWorkflowActualBody().get("caption")); assertFalse(f.writeWorkflowActualBody().containsKey("priority"));
            f.assertWriteApiCounts(1, 0); f.assertWriteApiAuditSafe();
            state.put("publishedEvidence", persisted); state.put("confirmedCall", call); state.put("counts", f.writeWorkflowCounts());
            ready(manifest, json, state, "CONFIRMED_POST_READY_FOR_BROWSER_READS");
            await(root, "confirmed-observed", deadline); f.assertWriteApiCounts(1, 0);
            f.dropNextWriteApiResponse(); var unknown = f.callWriteWorkflowMcp(input());
            assertEquals(false, unknown.get("success")); assertEquals("HTTP_API_WORKFLOW_RESULT_UNCONFIRMED", unknown.get("code"));
            f.assertWriteApiCounts(2, 0); state.put("unconfirmedCall", unknown); state.put("counts", f.writeWorkflowCounts());
            ready(manifest, json, state, "UNCONFIRMED_POST_READY_FOR_BROWSER_READS");
            await(root, "unknown-observed", deadline);
            f.assertWriteApiCounts(2, 0); f.assertWriteApiAuditSafe();
            state.put("references", f.writeWorkflowReferences()); state.put("unknownTrace", f.writeWorkflowTrace(String.valueOf(unknown.get("traceId"))));
            state.put("countsAfterBrowserReads", f.writeWorkflowCounts()); state.remove("authStatePath");
            ready(manifest, json, state, "BROWSER_WRITE_WORKFLOW_VERIFIED");
            await(root, "shutdown", deadline);
        }
    }

    private String createEmptyAuthorDraft(BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture f, ObjectMapper json) throws Exception {
        ObjectNode graph; String canvas;
        try (var resource = getClass().getResourceAsStream("/bmapi-3c-a/browser-saved-working-copy.json")) {
            assertNotNull(resource); var source = json.readTree(resource);
            graph = (ObjectNode)json.readTree(source.path("graphSpecJson").asText()); canvas = source.path("canvasJson").asText();
        }
        var api = (ObjectNode)graph.path("nodes").get(0); api.put("name", "选择订单 API"); api.remove("ref");
        api.set("inputs", json.createArrayNode()); api.set("outputs", json.createArrayNode());
        var config = (ObjectNode)api.path("config"); config.remove("httpApiAssetId"); config.set("inputMapping", json.createObjectNode());
        config.set("toolConfig", json.createObjectNode().put("ref", "").put("projectCode", "orders").set("inputMapping", json.createObjectNode()));
        ((ObjectNode)graph.path("nodes").get(1).path("config")).set("assignments", json.createObjectNode());
        var schema = graph.putObject("inputSchema").put("type", "object"); var props = schema.putObject("properties");
        props.putObject("orderId").put("type", "string");
        f.writeApiOwner().acceptedContract().path("requestBody").path("schema").path("properties").fields().forEachRemaining(field -> props.set(field.getKey(), field.getValue()));
        schema.putArray("required").add("orderId").add("note").add("apiKey");
        var create = new LinkedHashMap<String, Object>(); create.put("projectId", 41); create.put("projectCode", "orders");
        create.put("keySlug", "bmapi-3cd-browser-write"); create.put("name", "订单备注写入代表流程"); create.put("workflowKind", "GENERAL");
        create.put("executionEngine", "GRAPH_SPEC"); create.put("graphSpecJson", json.writeValueAsString(graph)); create.put("canvasJson", canvas);
        create.put("inputSchemaJson", json.writeValueAsString(schema)); create.put("outputSchemaJson", "{\"type\":\"object\"}");
        create.put("status", "DRAFT"); create.put("definitionAuthority", "USER"); create.put("creationChannel", "STUDIO");
        var created = f.multiSourcePublicRequest("POST", "/api/workflows", create);
        assertEquals(200, created.statusCode(), f.writeWorkflowTransportDiagnostic());
        return String.valueOf(f.writeResponse(created).get("id"));
    }

    private static Map<String, Object> input() {
        var args = new LinkedHashMap<String, Object>(); args.put("orderId", "ORD-3CD-BROWSER"); args.put("note", "isolated-browser-write");
        args.put("apiKey", StatefulWriteApiFixtureController.SENSITIVE_INPUT); args.put("accessCode", "synthetic-3cd-format-secret");
        args.put("deliveryMark", "synthetic-3cd-writeonly-secret"); args.put("notify", false); args.put("quantity", 0);
        args.put("rate", 0.5); args.put("caption", ""); args.put("kind", "COMMENT"); return args;
    }

    private static void ready(Path path, ObjectMapper json, Map<String, Object> state, String status) throws Exception {
        state.put("status", status); Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(state));
        System.out.println("BMAPI_3CD_" + status);
    }

    private static void await(Path root, String name, long deadline) throws Exception {
        while (!Files.exists(root.resolve(name + ".sentinel"))) {
            if (!"shutdown".equals(name) && Files.exists(root.resolve("shutdown.sentinel"))) fail("browser gate cancelled");
            if (System.nanoTime() > deadline) fail("browser gate timed out at " + name);
            Thread.sleep(200L);
        }
    }
}
