package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.common.capability.HttpApiConsoleContracts;
import com.enterprise.ai.common.capability.HttpApiDraftTrialPolicy;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialRuntime;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionEventSink;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpClient;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpEgressPolicy;
import com.enterprise.ai.runtime.execution.policy.RuntimeEvalExecutionContext;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.runops.consolehttpapi.ControllerOrderFixtureController;
import com.enterprise.ai.runtime.runops.consolehttpapi.RuntimeHttpApiConnectionService;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** H2 release pin + the exact Studio graph + real loopback HTTP into the scanned Controller method. */
class RuntimeWorkflowHttpApiServiceTest {
    private static final String HASH = "24c4c6a8623e7e22cf06bc8cee00d14827a28719008e7f1f29638913365f7ea4";
    private static final String SOURCE_REVISION = "bee5acf3c8553b93041a532fb5258dcaf54d699e131689edfc0826cb2bb018c1";
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<String> candidateHash = new AtomicReference<>(HASH);
    private final AtomicReference<String> sourceStatus = new AtomicReference<>("ACCEPTED");
    private final AtomicBoolean sourceConfirmed = new AtomicBoolean(true);
    private final AtomicReference<Long> connectionRevision = new AtomicReference<>(1L);
    private final AtomicReference<String> credentialRevision = new AtomicReference<>();
    private final AtomicReference<String> sideEffect = new AtomicReference<>("READ_ONLY");
    private final AtomicReference<String> connectionStatus = new AtomicReference<>("CONFIGURED");
    private final AtomicBoolean credentialDisabled = new AtomicBoolean();
    private final AtomicBoolean unauthorized = new AtomicBoolean();
    private final AtomicInteger ownerLookups = new AtomicInteger();
    private final AtomicInteger requests = new AtomicInteger();
    private final ControllerOrderFixtureController controller = new ControllerOrderFixtureController();
    private RuntimeQueryTestDatabase db;
    private HttpServer upstream;
    private String graph;
    private String reference;
    private RuntimeCapabilityContractPins contractPins;
    private RuntimeGraphSpecExecutor executor;
    private RuntimeWorkflowHttpApiService projection;

    @BeforeEach
    void start() throws Exception {
        JsonNode browser;
        try (var stream = getClass().getResourceAsStream("/bmapi-3c-a/browser-saved-working-copy.json")) {
            assertNotNull(stream);
            browser = json.readTree(stream);
        }
        assertEquals("fixture-r3", browser.path("browserRevision").asText());
        graph = browser.path("graphSpecJson").asText();
        reference = json.readTree(graph).at("/nodes/0/ref/qualifiedName").asText();
        db = new RuntimeQueryTestDatabase(List.of("runtime_workflow_http_api_pin"),
                RuntimeWorkflowHttpApiPinMapper.class);
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        upstream.createContext("/orders/", exchange -> {
            requests.incrementAndGet();
            byte[] body;
            int status;
            if (unauthorized.get()) {
                status = 401;
                body = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
            } else {
                try {
                    var response = mvc.perform(get(exchange.getRequestURI().toString())).andReturn().getResponse();
                    status = response.getStatus();
                    body = response.getContentAsByteArray();
                } catch (Exception failed) {
                    status = 500;
                    body = "{\"error\":\"fixture_failure\"}".getBytes(StandardCharsets.UTF_8);
                }
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        upstream.start();

        RuntimeCapabilityCatalogClient owner = mock(RuntimeCapabilityCatalogClient.class);
        JsonNode accepted = json.readTree("""
                {"identity":{"method":"GET","routeTemplate":"/orders/{orderId}","mappingConditions":{}},
                 "sideEffect":"READ_ONLY",
                 "parameters":[{"name":"orderId","location":"PATH","required":true,"schema":{"type":"string"}},
                               {"name":"detailLevel","location":"QUERY","required":false,"schema":{"type":"string"}}],
                 "responses":[{"contentTypes":["application/json"],"schema":{"type":"object"}}],
                 "authentication":{"state":"UNKNOWN"}}
                """);
        when(owner.getHttpApiExecutionContext(eq(1L), eq("orders"))).thenAnswer(call -> {
            ownerLookups.incrementAndGet();
            return new HttpApiConsoleContracts.ExecutionContext(1, 1L, reference, 41L, "orders", "dev",
                        "GET", "/orders/{orderId}", sourceConfirmed.get(), sourceStatus.get(), null,
                        candidateHash.get(), HASH,
                        SOURCE_REVISION, ((com.fasterxml.jackson.databind.node.ObjectNode) accepted.deepCopy())
                        .put("sideEffect", sideEffect.get()), true, null);
        });
        RuntimeHttpApiConnectionService connections = mock(RuntimeHttpApiConnectionService.class);
        String origin = "http://127.0.0.1:" + upstream.getAddress().getPort();
        when(connections.read(any())).thenAnswer(call -> new HttpApiConsoleContracts.ConnectionView(
                reference, 41L, "orders", "dev", origin,
                credentialRevision.get() == null ? "NONE" : "API_KEY_HEADER",
                credentialRevision.get() == null ? null : "cred-1", null, credentialRevision.get(),
                connectionRevision.get(), connectionStatus.get(), null, origin + "/orders/{orderId}"));
        when(connections.snapshot(eq(reference))).thenAnswer(call ->
                new RuntimeHttpApiConnectionService.ConnectionSnapshot(41L, "orders", "dev", origin,
                        credentialRevision.get() == null ? "NONE" : "API_KEY_HEADER",
                        credentialRevision.get() == null ? null : "cred-1", connectionRevision.get()));
        when(connections.selectedCredential(any(), any(), any())).thenAnswer(call -> {
            if (credentialDisabled.get()) throw new IllegalArgumentException("HTTP_API_CREDENTIAL_DISABLED");
            return credentialRevision.get() == null ? null : new RuntimeWorkflowCredentialRuntime(
                        "cred-1", "Synthetic API key", "API_KEY_HEADER",
                        Map.of("headerName", "X-API-Key", "apiKey", "synthetic-secret"),
                        credentialRevision.get());
        });
        WorkflowHttpClient http = new WorkflowHttpClient(json, WorkflowHttpEgressPolicy.permissiveForTests(), null);
        projection = new RuntimeWorkflowHttpApiService(owner, connections,
                db.mapper(RuntimeWorkflowHttpApiPinMapper.class), http, json);
        contractPins = new RuntimeCapabilityContractPins(owner, json, projection);
        executor = new RuntimeGraphSpecExecutor(json, mock(RuntimeModelServiceClient.class), owner,
                mock(RuntimeControlCatalogClient.class), null, http, null, projection);
    }

    @AfterEach
    void stop() {
        if (upstream != null) upstream.stop(0);
        if (db != null) db.close();
    }

    @Test
    void publishedBrowserGraphCallsTheControllerAndAssignsPaidWithoutLeakingSyntheticApiKey() throws Exception {
        String published = publish();
        assertEquals(HASH, json.readTree(published).at("/nodes/0/ref/contractHash").asText());
        assertTrue(json.readTree(published).at("/nodes/0/ref/definitionId").asLong() > 0);
        assertFalse(published.contains("127.0.0.1"));
        assertFalse(published.contains("credentialRevision"));
        var result = executor.execute(published, Map.of("params", Map.of("orderId", "O-321", "detailLevel", "full")),
                WorkflowExecutionIdentity.fromMcpRemoteClient("orders", 41L, "orders", "test-client"));
        assertTrue(result.success(), result.code() + ": " + result.answer());
        assertTrue(result.answer().contains("PAID"));
        assertFalse(result.answer().contains("synthetic-controller-value"));
        assertEquals(1, requests.get());
        assertEquals(1, controller.methodCalls());
    }

    @Test
    void changedOwnerConnectionWrongProjectAndMissingPathStopBeforeHttpDispatch() {
        String published = publish();
        var trusted = WorkflowExecutionIdentity.fromMcpRemoteClient("orders", 41L, "orders", "test-client");
        candidateHash.set("b".repeat(64));
        var drift = executor.execute(published, Map.of("params", Map.of("orderId", "O-321")), trusted);
        assertFalse(drift.success()); assertEquals(0, requests.get());
        candidateHash.set(HASH);
        connectionRevision.set(2L);
        var changed = executor.execute(published, Map.of("params", Map.of("orderId", "O-321")), trusted);
        assertFalse(changed.success()); assertEquals(0, requests.get());
        connectionRevision.set(1L);
        var wrongProject = executor.execute(published, Map.of("params", Map.of("orderId", "O-321")),
                WorkflowExecutionIdentity.fromMcpRemoteClient("other", 42L, "other", "other-client"));
        assertFalse(wrongProject.success()); assertEquals(0, requests.get());
        var missingPath = executor.execute(published, Map.of("params", Map.of("detailLevel", "full")), trusted);
        assertFalse(missingPath.success()); assertEquals(0, requests.get());
        assertEquals("pathParams.orderId", missingPath.metadata().get("inputField"));
    }

    @Test
    void unacceptedOrUnconfirmedSourceCannotCreateAPublishedPin() {
        sourceStatus.set("PENDING");
        assertEquals("HTTP_API_SOURCE_NOT_READY",
                assertThrows(IllegalArgumentException.class, this::publish).getMessage());
        sourceStatus.set("ACCEPTED");
        sourceConfirmed.set(false);
        assertEquals("HTTP_API_SOURCE_NOT_READY",
                assertThrows(IllegalArgumentException.class, this::publish).getMessage());
        assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_workflow_http_api_pin", Integer.class));
        assertEquals(0, requests.get());
    }

    @Test
    void http401FailsWithoutRetryEvenThoughTheCapturedGraphEnablesRetry() {
        String published = publish();
        unauthorized.set(true);
        var result = executor.execute(published, Map.of("params", Map.of("orderId", "O-321")),
                WorkflowExecutionIdentity.fromMcpRemoteClient("orders", 41L, "orders", "test-client"));
        assertFalse(result.success());
        assertEquals(1, requests.get(), "an HTTP failure must not cause a blind second dispatch");
        assertEquals(0, controller.methodCalls());
    }

    @Test
    void debugIdentityCannotBorrowThePublishedWorkflowPin() {
        String published = publish();
        var result = executor.execute(published, Map.of("params", Map.of("orderId", "O-321")),
                WorkflowExecutionIdentity.untrustedDebug());
        assertFalse(result.success());
        assertEquals("HTTP_API_WORKFLOW_IDENTITY_DENIED", result.code());
        assertEquals(0, requests.get());
    }

    @Test
    void trustedEvalStillBlocksApiToolBeforeOwnerLookupOrHttpWhilePublishedMcpStillRuns() {
        String published = publish();
        int ownerLookupsBeforeEval = ownerLookups.get();
        var trusted = WorkflowExecutionIdentity.fromMcpRemoteClient("orders", 41L, "orders", "test-client");
        Map<String, Object> input = Map.of("params", Map.of("orderId", "O-321", "detailLevel", "full"));
        var eval = executor.execute(published, input, RuntimeGraphSpecExecutionEventSink.NOOP,
                RuntimeGraphSpecExecutionCancellation.none(), trusted,
                RuntimeEvalExecutionContext.readOnly("exp-api", "item-api", null));

        assertFalse(eval.success());
        assertEquals("EVAL_SIDE_EFFECT_BLOCKED", eval.code());
        assertEquals("TOOL", eval.nodeType());
        assertEquals("READ_ONLY_EXECUTION", eval.metadata().get("evalMode"));
        assertEquals(ownerLookupsBeforeEval, ownerLookups.get(), "Eval must not resolve the API owner");
        assertEquals(0, requests.get(), "Eval must not dispatch HTTP");
        assertEquals(0, controller.methodCalls());

        var mcp = executor.execute(published, input, trusted);
        assertTrue(mcp.success(), mcp.code() + ": " + mcp.answer());
        assertTrue(mcp.answer().contains("PAID"));
        assertEquals(1, requests.get());
        assertEquals(1, controller.methodCalls());
    }

    @Test
    void projectCredentialRevisionChangeRejectsThePublishedPinBeforeDispatch() {
        credentialRevision.set("revision-1");
        String published = publish();
        credentialRevision.set("revision-2");
        var result = executor.execute(published, Map.of("params", Map.of("orderId", "O-321")),
                WorkflowExecutionIdentity.fromMcpRemoteClient("orders", 41L, "orders", "test-client"));
        assertFalse(result.success());
        assertEquals(0, requests.get());
    }

    private String publish() {
        var workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId("wf-api"); workflow.setProjectId(41L); workflow.setProjectCode("orders");
        String published = contractPins.pin(graph, workflow);
        contractPins.bindPublishedGraph(published, 101L);
        contractPins.validatePinned(published);
        return published;
    }

    private RuntimeWorkflowHttpApiService.DraftPin draftPin() throws Exception {
        var workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId("wf-draft"); workflow.setProjectId(41L); workflow.setProjectCode("orders");
        var node = json.readValue(graph, com.enterprise.ai.agent.graph.GraphSpec.class).getNodes().get(0);
        return projection.prepareDraft(node, workflow, "saved-r1", HttpApiDraftTrialPolicy.target(json, graph));
    }

    @Test void draftTrialUsesRequestFactsCallsOnceAndNeverPersistsOrBorrowsReleasePin() throws Exception {
        var identity = WorkflowExecutionIdentity.fromAttestedStudioProjectTest(41L, "orders", "42");
        var input = Map.<String, Object>of("params", Map.of("orderId", "O-321", "detailLevel", "full"));
        var pin = draftPin();
        var result = projection.withDraftScope(pin, () -> true, () -> executor.execute(graph, input, identity));
        assertTrue(result.success(), result.code()); assertTrue(result.answer().contains("PAID"));
        assertFalse(result.answer().contains("synthetic-controller-value"));
        assertEquals(1, requests.get()); assertEquals(1, controller.methodCalls());
        assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_workflow_http_api_pin", Integer.class));
        var outsideScope = executor.execute(graph, input, identity);
        assertFalse(outsideScope.success()); assertEquals("HTTP_API_TRIAL_SCOPE_DENIED", outsideScope.code());
        assertEquals(1, requests.get());
    }

    @Test void draftOwnerRevisionProjectConnectionAndCredentialDriftNeverDispatch() throws Exception {
        credentialRevision.set("credential-r1");
        var pin = draftPin();
        var identity = WorkflowExecutionIdentity.fromAttestedStudioProjectTest(41L, "orders", "42");
        var input = Map.<String, Object>of("params", Map.of("orderId", "O-321"));
        var stale = projection.withDraftScope(pin, () -> false, () -> executor.execute(graph, input, identity));
        assertFalse(stale.success()); assertEquals(0, requests.get());
        var crossProject = projection.withDraftScope(pin, () -> true, () -> executor.execute(graph, input,
                WorkflowExecutionIdentity.fromAttestedStudioProjectTest(42L, "other", "42")));
        assertFalse(crossProject.success()); assertEquals(0, requests.get());
        candidateHash.set("f".repeat(64));
        assertFalse(projection.withDraftScope(pin, () -> true, () -> executor.execute(graph, input, identity)).success());
        candidateHash.set(HASH); connectionRevision.set(2L);
        assertFalse(projection.withDraftScope(pin, () -> true, () -> executor.execute(graph, input, identity)).success());
        connectionRevision.set(1L); credentialRevision.set("credential-r2");
        assertFalse(projection.withDraftScope(pin, () -> true, () -> executor.execute(graph, input, identity)).success());
        credentialRevision.set("credential-r1"); credentialDisabled.set(true);
        assertFalse(projection.withDraftScope(pin, () -> true, () -> executor.execute(graph, input, identity)).success());
        credentialDisabled.set(false); connectionStatus.set("BLOCKED");
        assertFalse(projection.withDraftScope(pin, () -> true, () -> executor.execute(graph, input, identity)).success());
        assertEquals(0, requests.get()); assertEquals(0, controller.methodCalls());
    }

    @Test void draftUnknownOrWriteDeclarationIsRejectedEvenForGetAndFailureNeverRetries() throws Exception {
        var pin = draftPin();
        var identity = WorkflowExecutionIdentity.fromAttestedStudioProjectTest(41L, "orders", "42");
        var input = Map.<String, Object>of("params", Map.of("orderId", "O-321"));
        for (String declaration : List.of("WRITE", "UNKNOWN", "")) {
            sideEffect.set(declaration);
            assertThrows(IllegalArgumentException.class, this::draftPin);
            assertFalse(projection.withDraftScope(pin, () -> true, () -> executor.execute(graph, input, identity)).success());
        }
        assertEquals(0, requests.get());
        sideEffect.set("READ_ONLY"); unauthorized.set(true);
        var failed = projection.withDraftScope(pin, () -> true, () -> executor.execute(graph, input, identity));
        assertFalse(failed.success()); assertEquals(1, requests.get());
    }
}
