package com.enterprise.ai.runtime.workflow.bmapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Normal scan/acceptance and real public Control publication; no accepted-row or Workflow-shell shortcut. */
class HttpApiWriteWorkflowE2eIntegrationTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture fixture;
    private String workflowId;
    private String revision;

    @BeforeEach void start() throws Exception {
        fixture = new BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture(json);
        fixture.startWriteFixture();
        fixture.prepareWriteApiBrowserSurface("order-notes-workflow.yaml");
        fixture.discoverWriteApi();
        fixture.acceptWriteApiAndConnect();
    }

    @AfterEach void close() throws Exception { if (fixture != null) fixture.close(); }

    @Test void normalScannedWriteApiCanPublishWithServerOwnerPin() throws Exception {
        saveWriteDraft("WRITE");
        var published = request("POST", "/api/workflows/" + workflowId + "/versions/publish",
                Map.of("version", "v1.0.0", "baseRevision", revision, "rolloutPercent", 100));
        System.out.println("BMAPI_3CD_PUBLIC_POST_PUBLICATION_STATUS " + published.statusCode());
        if (published.statusCode() != 200) {
            System.out.println("BMAPI_3CD_PUBLICATION_REFUSAL " + body(published).get("message"));
        }
        assertEquals(200, published.statusCode(), "normal POST WRITE owner must support final publication");
        assertEquals("WRITE", json.readTree(String.valueOf(body(published).get("snapshotJson"))).path("httpApiRiskFloor").asText());
        fixture.assertWriteApiCounts(0, 0);
    }

    @Test void publishedWriteMcpPreservesNativeBodyAndConsumesDeclaredResponseWithSafeAudit() throws Exception {
        publishAndExpose("TERMINATE", false, false);
        var result = fixture.callWriteWorkflowMcp(validInput());
        assertEquals(true, result.get("success"), String.valueOf(result.get("code")));
        assertTrue(String.valueOf(result.get("answer")).contains("NOTED"));
        assertTrue(String.valueOf(result.get("answer")).contains("order_state"));
        var actual = fixture.writeWorkflowActualBody();
        assertEquals(false, actual.get("notify")); assertEquals(0, actual.get("quantity"));
        assertEquals(0.5, actual.get("rate")); assertEquals("", actual.get("caption"));
        assertFalse(actual.containsKey("priority"), "source default must not be filled");
        fixture.assertWriteApiCounts(1, 0); fixture.assertWriteApiAuditSafe();
        var trace = fixture.writeWorkflowTrace(String.valueOf(result.get("traceId")));
        assertSafe(trace); assertSafe(result);
        var references = fixture.writeWorkflowReferences();
        assertEquals("COMPLETE", references.get("runtimeEvidence"));
        assertTrue(json.writeValueAsString(references).contains(workflowId));
    }

    @ParameterizedTest @ValueSource(strings = {"TERMINATE", "CONTINUE", "FALLBACK"})
    void writtenButLostResponseIsUnknownAndNeverRetriedOrRecovered(String strategy) throws Exception {
        publishAndExpose(strategy, false, false);
        fixture.dropNextWriteApiResponse();
        var result = fixture.callWriteWorkflowMcp(validInput());
        assertEquals(false, result.get("success"));
        assertEquals("HTTP_API_WORKFLOW_RESULT_UNCONFIRMED", result.get("code"));
        fixture.assertWriteApiCounts(1, 0);
        for (int index = 0; index < 3; index++) {
            var trace = fixture.writeWorkflowTrace(String.valueOf(result.get("traceId")));
            var safe = json.writeValueAsString(trace); assertTrue(safe.contains("UNCONFIRMED"));
            assertTrue(safe.contains("UNKNOWN")); assertSafe(trace);
        }
        fixture.assertWriteApiCounts(1, 0);
        var explicitNewRun = fixture.callWriteWorkflowMcp(validInput());
        assertEquals(true, explicitNewRun.get("success"));
        assertNotEquals(result.get("traceId"), explicitNewRun.get("traceId"));
        fixture.assertWriteApiCounts(2, 0); fixture.assertWriteApiAuditSafe();
    }

    @ParameterizedTest @ValueSource(strings = {"TIMEOUT", "INVALID_JSON"})
    void actualWrittenTimeoutOrInvalidSuccessBodyStaysUnknownWithoutASecondDispatch(String mode) throws Exception {
        publishAndExpose("FALLBACK", false, false);
        fixture.writeWorkflowResponseMode(mode);
        long started = System.nanoTime();
        var result = fixture.callWriteWorkflowMcp(validInput());
        if ("TIMEOUT".equals(mode)) {
            assertTrue(System.nanoTime() - started >= java.time.Duration.ofSeconds(29).toNanos(),
                    "must exercise the real HTTP timeout after the business write, not an immediate synthetic failure");
        }
        assertEquals(false, result.get("success"));
        assertEquals("HTTP_API_WORKFLOW_RESULT_UNCONFIRMED", result.get("code"));
        fixture.assertWriteApiCounts(1, 0);
        for (int query = 0; query < 2; query++) {
            var trace = fixture.writeWorkflowTrace(String.valueOf(result.get("traceId")));
            assertSafe(trace);
            assertTrue(json.writeValueAsString(trace).contains("UNCONFIRMED"));
            assertTrue(json.writeValueAsString(trace).contains("UNKNOWN"));
        }
        fixture.assertWriteApiCounts(1, 0); fixture.assertWriteApiAuditSafe();
    }

    @Test void preparingSensitiveDeclarationsBeforeDispatchSurvivesResponseTimeSourceChange() throws Exception {
        publishAndExpose("TERMINATE", false, false);
        fixture.writeWorkflowRemoveSensitiveDeclarationsDuringResponse();
        var result = fixture.callWriteWorkflowMcp(validInput());
        assertEquals(true, result.get("success")); assertSafe(result);
        assertSafe(fixture.writeWorkflowTrace(String.valueOf(result.get("traceId"))));
        assertFalse(fixture.writeApiOwner().acceptedContract().path("requestBody").path("schema")
                .path("properties").path("accessCode").has("format"));
        fixture.assertWriteApiCounts(1, 0); fixture.assertWriteApiAuditSafe();
    }

    @Test void missingNullUnknownAndInvalidNativeInputsReachOwnerBinderButNeverHttp() throws Exception {
        publishAndExpose("TERMINATE", true, true);
        var missing = validInput(); missing.remove("note");
        var explicitNull = validInput(); explicitNull.put("note", null);
        var wrongType = validInput(); wrongType.put("quantity", "0");
        var wrongBoolean = validInput(); wrongBoolean.put("notify", "false");
        var wrongEnum = validInput(); wrongEnum.put("kind", "NOT_DECLARED");
        var longNote = validInput(); longNote.put("note", "x".repeat(121));
        var outsideConstraint = validInput(); outsideConstraint.put("rate", 2.0);
        var unknown = validInput(); unknown.put("unexpected", "not-declared");
        for (var input : List.of(missing, explicitNull, wrongType, wrongBoolean, wrongEnum, longNote, outsideConstraint, unknown)) {
            var result = fixture.callWriteWorkflowMcp(input);
            assertEquals(false, result.get("success"));
            assertEquals("HTTP_API_WORKFLOW_INPUT_INVALID", result.get("code"));
            assertNotNull(result.get("traceId"), "this assertion must reach the actual Runtime owner binder");
            fixture.assertWriteApiCounts(0, 0);
        }
    }

    @Test void mcpReadOverrideCannotLowerServerPinnedWriteOwner() throws Exception {
        saveWriteDraft("WRITE"); publish("v1.0.0");
        fixture.prepareWriteWorkflowMcp(workflowId, "READ");
        var rejected = fixture.publishWriteWorkflowMcp();
        assertTrue(rejected.statusCode() >= 400);
        assertTrue(json.writeValueAsString(body(rejected)).contains("MCP_WORKFLOW_RISK_DOWNGRADE"));
        fixture.assertWriteApiCounts(0, 0);
    }

    @Test void officialAgentConfirmationRejectAndConfirmUseTheSamePublishedWriteWorkflow() throws Exception {
        saveWriteDraft("WRITE"); publish("v1.0.0");
        String agentId = createPublishedAgent();
        fixture.writeWorkflowAgentPlan(validInput());
        var waiting = executeAgent(Map.of("agentId", agentId, "message", "Append an order note", "sessionId", "3cd-agent-reject",
                "projectCode", "orders", "tenantId", "default"));
        assertEquals(false, waiting.get("success")); assertEquals("SUPERVISOR_CONFIRMATION_REQUIRED", metadata(waiting).get("code"));
        assertSafe(waiting);
        fixture.assertWriteApiCounts(0, 0);
        String rejectedId = String.valueOf(metadata(waiting).get("interactionId"));
        var rejected = executeAgent(Map.of("agentId", agentId, "interactionId", rejectedId, "sessionId", "3cd-agent-reject",
                "idempotencyKey", "3cd-explicit-reject", "uiSubmit", Map.of("action", "reject")));
        assertEquals("SUPERVISOR_ACTION_REJECTED", metadata(rejected).get("code")); fixture.assertWriteApiCounts(0, 0);

        fixture.writeWorkflowAgentPlan(validInput());
        var second = executeAgent(Map.of("agentId", agentId, "message", "Append an order note", "sessionId", "3cd-agent-confirm",
                "projectCode", "orders", "tenantId", "default"));
        assertEquals("SUPERVISOR_CONFIRMATION_REQUIRED", metadata(second).get("code")); fixture.assertWriteApiCounts(0, 0);
        assertSafe(second);
        fixture.writeWorkflowAgentFinish();
        var confirmation = Map.<String, Object>of("agentId", agentId, "interactionId", metadata(second).get("interactionId"),
                "sessionId", "3cd-agent-confirm", "idempotencyKey", "3cd-explicit-confirm", "uiSubmit", Map.of("action", "confirm"));
        var confirmed = executeAgent(confirmation);
        assertEquals(true, confirmed.get("success"), String.valueOf(metadata(confirmed).get("code")));
        fixture.assertWriteApiCounts(1, 0);
        int rounds = fixture.writeWorkflowAgentModelCalls();
        var replay = executeAgent(confirmation);
        assertEquals(confirmed.get("answer"), replay.get("answer"));
        assertEquals(metadata(confirmed).get("traceId"), metadata(replay).get("traceId"));
        assertEquals(true, metadata(replay).get("idempotentReplay"));
        assertEquals(rounds, fixture.writeWorkflowAgentModelCalls()); fixture.assertWriteApiCounts(1, 0);
        var evidence = fixture.writeWorkflowAgentEvidence();
        assertEquals(2, evidence.get("approvals")); assertEquals(2, evidence.get("confirmationRequired"));
        assertEquals(1, evidence.get("allowed")); assertEquals(0, evidence.get("globalRuntimeGrants"));
        assertSafe(confirmed); assertSafe(fixture.writeWorkflowTrace(String.valueOf(metadata(confirmed).get("traceId"))));
        fixture.assertWriteApiAuditSafe();
    }

    @Test void officialAgentCannotAutoResendAConfirmedButUnknownWriteAfterModelReplan() throws Exception {
        saveWriteDraft("WRITE"); publish("v1.0.0"); String agentId = createPublishedAgent();
        fixture.writeWorkflowAgentPlan(validInput());
        var waiting = executeAgent(Map.of("agentId", agentId, "message", "Append an order note", "sessionId", "3cd-agent-unknown",
                "projectCode", "orders", "tenantId", "default"));
        assertEquals("SUPERVISOR_CONFIRMATION_REQUIRED", metadata(waiting).get("code")); fixture.assertWriteApiCounts(0, 0);
        fixture.dropNextWriteApiResponse(); fixture.writeWorkflowAgentReplan(validInput());
        var confirmed = executeAgent(Map.of("agentId", agentId, "interactionId", metadata(waiting).get("interactionId"),
                "sessionId", "3cd-agent-unknown", "idempotencyKey", "3cd-confirm-unknown", "uiSubmit", Map.of("action", "confirm")));
        assertEquals(false, confirmed.get("success"), "a failed/unknown write must not become a successful Agent run");
        assertEquals("HTTP_API_WORKFLOW_RESULT_UNCONFIRMED", metadata(confirmed).get("code"));
        assertEquals("UNKNOWN", metadata(confirmed).get("outcomeClass"));
        fixture.assertWriteApiCounts(1, 0);
        for (int query = 0; query < 2; query++) {
            var trace = fixture.writeWorkflowTrace(String.valueOf(metadata(confirmed).get("traceId"))); assertSafe(trace);
            assertTrue(json.writeValueAsString(trace).contains("UNKNOWN"));
            assertTrue(json.writeValueAsString(trace).contains("UNCONFIRMED"));
        }
        fixture.assertWriteApiCounts(1, 0); fixture.assertWriteApiAuditSafe();
    }

    private String createPublishedAgent() throws Exception {
        var created = request("POST", "/api/agents", Map.of("projectId", 41L, "projectCode", "orders",
                "keySlug", "bmapi-3cd-write-agent", "name", "Isolated 3CD Agent", "visibility", "PROJECT", "enabled", true));
        assertEquals(200, created.statusCode(), "normal Agent identity create");
        String agentId = String.valueOf(body(created).get("id"));
        var config = new LinkedHashMap<String, Object>();
        config.put("runtimeType", "AGENTSCOPE"); config.put("systemPrompt", "Use the configured order-note Workflow.");
        config.put("modelInstanceId", "controlled-local-model"); config.put("maxPlanSteps", 8); config.put("maxWorkflowCalls", 3);
        config.put("maxReplans", 1); config.put("totalTimeoutMs", 30_000); config.put("workflowTimeoutMs", 10_000);
        config.put("pageBridgeTimeoutMs", 2_000); config.put("parallelReadOnly", false);
        config.put("policyProfile", "STANDARD"); config.put("toolCatalogMode", "ALLOW_LIST");
        config.put("configJson", "{\"policy\":{\"allowedTenantIds\":[\"default\"]}}");
        config.put("tools", List.of(Map.of("workflowId", workflowId, "toolName", "append_order_note", "riskLevel", "READ",
                "readOnly", true, "permissionKey", "orders:notes:append", "enabled", true)));
        var downgraded = request("PUT", "/api/agents/" + agentId + "/config-versions/draft", config);
        assertEquals(400, downgraded.statusCode(), fixture.writeWorkflowTransportDiagnostic());
        assertTrue(String.valueOf(body(downgraded).get("message")).contains("HTTP_API_WORKFLOW_RISK_DOWNGRADE"));
        config.put("tools", List.of(Map.of("workflowId", workflowId, "toolName", "append_order_note", "riskLevel", "WRITE",
                "readOnly", false, "permissionKey", "orders:notes:append", "enabled", true)));
        var draft = request("PUT", "/api/agents/" + agentId + "/config-versions/draft", config);
        assertEquals(200, draft.statusCode(), "normal Agent draft save");
        var published = request("POST", "/api/agents/" + agentId + "/config-versions/" + body(draft).get("id") + "/publish", Map.of());
        assertEquals(200, published.statusCode(), "normal Agent configuration publish");
        return agentId;
    }

    private Map<String, Object> executeAgent(Map<String, Object> input) throws Exception {
        var executed = request("POST", "/api/runtime/agents/execute", input);
        assertEquals(200, executed.statusCode(), "public Agent execute/resume: " + fixture.writeWorkflowTransportDiagnostic()); return body(executed);
    }

    @SuppressWarnings("unchecked") private Map<String, Object> metadata(Map<String, Object> result) {
        assertInstanceOf(Map.class, result.get("metadata")); return (Map<String, Object>) result.get("metadata");
    }

    @ParameterizedTest @ValueSource(strings = {"307", "401", "503", "BUSINESS_FAILED"})
    void knownFailureResponseDoesNotRetryRedirectOrFallBackToSuccess(String mode) throws Exception {
        publishAndExpose("CONTINUE", false, false); fixture.writeWorkflowResponseMode(mode);
        var result = fixture.callWriteWorkflowMcp(validInput());
        assertEquals(false, result.get("success"));
        assertEquals("RUNTIME_HTTP_STATUS_" + ("BUSINESS_FAILED".equals(mode) ? "422" : mode), result.get("code"));
        var counts = fixture.writeWorkflowCounts(); assertEquals(1, counts.get("postHits"));
        assertEquals("BUSINESS_FAILED".equals(mode) ? 0 : 1, counts.get("writes"));
        assertEquals(0, counts.get("redirectedHits")); assertEquals(0, counts.get("consoleLedger"));
    }

    @ParameterizedTest @ValueSource(strings = {"SOURCE", "REMOVED", "CONNECTION", "CREDENTIAL"})
    void fixedFactsDriftRejectsOldReleaseAndNeedsExplicitWorkflowAndMcpRepublishing(String change) throws Exception {
        publishAndExpose("TERMINATE", false, false);
        createPublishedAgent();
        var initialFixed = fixture.writeWorkflowFixedVersionEvidence(workflowId);
        if ("SOURCE".equals(change)) fixture.changeWriteApiSource();
        if ("REMOVED".equals(change)) fixture.removeWriteWorkflowApiSource();
        if ("CONNECTION".equals(change)) fixture.changeWriteApiConnection();
        if ("CREDENTIAL".equals(change)) fixture.changeWriteApiCredential();
        var rejected = fixture.callWriteWorkflowMcp(validInput());
        assertEquals(false, rejected.get("success"));
        assertTrue(List.of("HTTP_API_SOURCE_NOT_READY", "HTTP_API_PUBLISHED_PIN_STALE").contains(rejected.get("code")), "old owner pin must reject before HTTP");
        fixture.assertWriteApiCounts(0, 0);
        if ("SOURCE".equals(change)) fixture.acceptWriteApiCandidate();
        if ("REMOVED".equals(change)) fixture.restoreWriteWorkflowApiSource();
        if ("CREDENTIAL".equals(change)) fixture.restoreWriteWorkflowCredential();
        publish("v1.0.1");
        assertEquals(initialFixed, fixture.writeWorkflowFixedVersionEvidence(workflowId), "releasing Workflow must not migrate existing MCP or Agent fixed version");
        assertEquals(false, fixture.callWriteWorkflowMcp(validInput()).get("success")); fixture.assertWriteApiCounts(0, 0);
        assertEquals(200, fixture.publishWriteWorkflowMcp().statusCode());
        var restored = fixture.callWriteWorkflowMcp(validInput());
        assertEquals(true, restored.get("success"), String.valueOf(restored.get("code")));
        fixture.assertWriteApiCounts(1, 0); assertSafe(restored); fixture.assertWriteApiAuditSafe();
    }

    @Test void mcpClientAndOuterAclDenialsAndWrongProjectNeverDispatchThePost() throws Exception {
        publishAndExpose("TERMINATE", false, false);
        fixture.writeWorkflowMcpClientEnabled(false);
        assertEquals("MCP_OUTER_POLICY_DENIED", fixture.callWriteWorkflowMcp(validInput()).get("code"));
        fixture.writeWorkflowMcpClientEnabled(true); fixture.writeWorkflowMcpAcl(false);
        assertEquals("MCP_OUTER_POLICY_DENIED", fixture.callWriteWorkflowMcp(validInput()).get("code"));
        fixture.writeWorkflowMcpAcl(true); fixture.writeWorkflowMcpClientScope("wrong-project");
        var denied = fixture.callWriteWorkflowMcp(validInput()); assertEquals(false, denied.get("success"));
        assertEquals("MCP_WORKFLOW_PROJECT_SCOPE_DENIED", denied.get("code"));
        fixture.assertWriteApiCounts(0, 0);
    }

    @Test void bothStudioDraftEntrancesStayReadOnlyAndFinalAuthorReadMetadataIsNotTrusted() throws Exception {
        saveWriteDraft("WRITE");
        var debug = request("POST", "/api/workflows/studio/debug-run", Map.of("workflowId", workflowId, "inputParams", validInput(),
                "debugOptions", Map.of("consoleInvocation", true, "confirmedSideEffect", true)));
        assertEquals(200, debug.statusCode()); assertEquals(false, body(debug).get("success"));
        assertEquals("HTTP_API_PUBLISHED_PIN_INVALID", body(debug).get("errorCode"));
        var readOnly = request("POST", "/api/workflows/studio/read-only-trials", Map.of("workflowId", workflowId,
                "expectedRevision", revision, "inputParams", validInput()));
        assertEquals(409, readOnly.statusCode()); assertEquals("HTTP_API_TRIAL_GRAPH_UNSUPPORTED", body(readOnly).get("errorCode"));
        var working = body(request("GET", "/api/workflows/" + workflowId + "/working-copy", null));
        var forgedGraph = (ObjectNode) json.readTree(String.valueOf(working.get("graphSpecJson")));
        forgedGraph.putObject("metadata").put("riskLevel", "READ");
        var forged = new LinkedHashMap<>(working); forged.put("baseRevision", working.get("revision"));
        forged.put("graphSpecJson", json.writeValueAsString(forgedGraph));
        var attempted = request("PUT", "/api/workflows/" + workflowId + "/working-copy", forged);
        assertTrue(attempted.statusCode() >= 400, "strict GraphSpec forbids author risk metadata");
        assertFalse(String.valueOf(body(request("GET", "/api/workflows/" + workflowId + "/working-copy", null)).get("graphSpecJson")).contains("metadata"));
        var advisoryRisk = new LinkedHashMap<>(working); advisoryRisk.put("baseRevision", working.get("revision"));
        advisoryRisk.put("extraJson", "{\"riskLevel\":\"READ\",\"readOnly\":true}");
        assertEquals(200, request("PUT", "/api/workflows/" + workflowId + "/working-copy", advisoryRisk).statusCode());
        var published = publish("v1.0.0");
        var snapshot = json.readTree(String.valueOf(published.get("snapshotJson")));
        assertEquals("WRITE", snapshot.path("riskLevel").asText()); assertEquals("WRITE", snapshot.path("httpApiRiskFloor").asText());
        fixture.assertWriteApiCounts(0, 0);
    }

    private Map<String, Object> validInput() {
        var input = new LinkedHashMap<String, Object>();
        input.put("orderId", "ORD-3CD"); input.put("note", "isolated-write");
        input.put("apiKey", StatefulWriteApiFixtureController.SENSITIVE_INPUT);
        input.put("accessCode", "synthetic-3cd-format-secret"); input.put("deliveryMark", "synthetic-3cd-writeonly-secret");
        input.put("notify", false); input.put("quantity", 0); input.put("rate", 0.5); input.put("caption", ""); input.put("kind", "COMMENT");
        return input;
    }

    private void assertSafe(Object evidence) throws Exception {
        String safe = json.writeValueAsString(evidence);
        for (String field : List.of("apiKey", "accessCode", "deliveryMark")) {
            assertFalse(safe.contains(String.valueOf(validInput().get(field))), "sensitive source value must be protected");
        }
        assertFalse(safe.contains(StatefulWriteApiFixtureController.PROJECT_SECRET), "PROJECT credential must be protected");
    }

    private void publishAndExpose(String strategy, boolean looseSchema, boolean unknownMapping) throws Exception {
        saveWriteDraft("WRITE", strategy, looseSchema, unknownMapping); publish("v1.0.0");
        fixture.prepareWriteWorkflowMcp(workflowId, null);
        assertEquals(200, fixture.publishWriteWorkflowMcp().statusCode()); fixture.createWriteWorkflowMcpClient();
    }

    private Map<String, Object> publish(String version) throws Exception {
        revision = String.valueOf(body(request("GET", "/api/workflows/" + workflowId + "/working-copy", null)).get("revision"));
        var response = request("POST", "/api/workflows/" + workflowId + "/versions/publish",
                Map.of("version", version, "baseRevision", revision, "rolloutPercent", 100));
        assertEquals(200, response.statusCode(), "explicit Workflow publication"); return body(response);
    }

    private void saveWriteDraft(String risk) throws Exception {
        saveWriteDraft(risk, "TERMINATE", false, false);
    }

    private void saveWriteDraft(String risk, String strategy, boolean looseSchema, boolean unknownMapping) throws Exception {
        ObjectNode graph;
        try (var resource = getClass().getResourceAsStream("/bmapi-3c-a/browser-saved-working-copy.json")) {
            assertNotNull(resource);
            graph = (ObjectNode) json.readTree(json.readTree(resource).path("graphSpecJson").asText());
        }
        // GraphSpec does not accept metadata. Published risk must be derived by the server from the API owner.
        String reference = fixture.writeApiOwner().summary().qualifiedName();
        var node = (ObjectNode) graph.path("nodes").get(0);
        node.put("name", "POST /orders/{orderId}/notes");
        ((ObjectNode) node.path("ref")).put("qualifiedName", reference).put("name", reference);
        var mapping = json.createObjectNode().put("pathParams.orderId", "params.orderId");
        for (String field : List.of("note", "apiKey", "accessCode", "deliveryMark", "priority", "quantity", "rate", "caption", "notify", "kind")) {
            mapping.put("body." + field, "params." + field);
        }
        if (unknownMapping) mapping.put("body.unexpected", "params.unexpected");
        var policy = node.putObject("errorPolicy"); policy.put("strategy", strategy);
        if ("FALLBACK".equals(strategy)) policy.put("fallbackNodeId", "variable_1790214522008");
        var config = (ObjectNode) node.path("config");
        config.set("inputMapping", mapping);
        ((ObjectNode) config.path("toolConfig")).put("ref", reference).put("qualifiedName", reference).set("inputMapping", mapping);
        node.remove("inputs");
        var schema = graph.putObject("inputSchema");
        schema.put("type", "object");
        var properties = schema.putObject("properties");
        properties.putObject("orderId").put("type", "string");
        fixture.writeApiOwner().acceptedContract().path("requestBody").path("schema").path("properties").fields()
                .forEachRemaining(field -> properties.set(field.getKey(), looseSchema ? json.createObjectNode() : field.getValue()));
        if (looseSchema) schema.putArray("required").add("orderId");
        else schema.putArray("required").add("orderId").add("note").add("apiKey");

        var create = new LinkedHashMap<String, Object>();
        create.put("projectId", 41L); create.put("projectCode", "orders");
        create.put("keySlug", "bmapi-3cd-write-workflow"); create.put("name", "BMAPI 3CD write Workflow");
        create.put("workflowKind", "GENERAL"); create.put("executionEngine", "GRAPH_SPEC");
        create.put("graphSpecJson", json.writeValueAsString(graph)); create.put("inputSchemaJson", json.writeValueAsString(schema));
        create.put("outputSchemaJson", "{\"type\":\"object\"}"); create.put("status", "DRAFT");
        create.put("definitionAuthority", "USER"); create.put("creationChannel", "STUDIO");
        var created = request("POST", "/api/workflows", create);
        assertEquals(200, created.statusCode(), "public draft create: " + fixture.writeWorkflowTransportDiagnostic());
        workflowId = String.valueOf(body(created).get("id"));
        var loaded = request("GET", "/api/workflows/" + workflowId + "/working-copy", null);
        assertEquals(200, loaded.statusCode()); revision = String.valueOf(body(loaded).get("revision"));
        var save = new LinkedHashMap<>(create);
        save.remove("projectId"); save.remove("projectCode"); save.remove("status"); save.put("baseRevision", revision);
        var saved = request("PUT", "/api/workflows/" + workflowId + "/working-copy", save);
        assertEquals(200, saved.statusCode(), "public draft save: " + body(saved).get("message")); revision = String.valueOf(body(saved).get("revision"));
        var reopened = request("GET", "/api/workflows/" + workflowId + "/working-copy", null);
        assertEquals(200, reopened.statusCode());
        assertEquals("params.notify", json.readTree(String.valueOf(body(reopened).get("graphSpecJson"))).at("/nodes/0/config/inputMapping/body.notify").asText());
    }

    private HttpResponse<byte[]> request(String method, String path, Map<String, Object> payload) throws Exception {
        return fixture.multiSourcePublicRequest(method, path, payload);
    }

    private Map<String, Object> body(HttpResponse<byte[]> response) throws Exception { return fixture.writeResponse(response); }
}
