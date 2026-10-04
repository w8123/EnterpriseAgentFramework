package com.enterprise.ai.runtime.workflow.bmapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import static org.junit.jupiter.api.Assertions.*;

/** Real public Control HTTP, Capability catalog selection and H2 persistence, not page API mocks. */
class HttpApiMarketBindingE2eIntegrationTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    @Test void selectedMarketOperationMustCreateAnOwnerApiInsteadOfOnlyTechnicalReady() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        try (var fixture = new BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture(json)) {
            fixture.start(); fixture.prepareMarketSurface();
            var entries = fixture.multiSourcePublicRequest("GET", "/api/api-market/entries?current=1&size=24", null);
            assertEquals(200, entries.statusCode());
            assertEquals(2, json.readTree(entries.body()).path("total").asInt(), "real catalog pagination total must match its two seeded entries");
            assertEquals(2, json.readTree(entries.body()).path("records").size());
            assertEquals(200, fixture.multiSourcePublicRequest("GET", "/api/api-market/entries/isolated-orders-alpha", null).statusCode());
            var integrated = fixture.multiSourcePublicRequest("POST", "/api/api-market/entries/isolated-orders-alpha/integrations",
                    Map.of("projectId", 41L, "projectCode", "orders", "environment", "DEVELOPMENT",
                            "versionId", 21L, "operationIds", List.of(31L)));
            assertEquals(200, integrated.statusCode(), new String(integrated.body(), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(1, ((List<?>)fixture.marketEvidence().get("assets")).size(),
                    "API_MARKET_OWNER_BINDING_MISSING: READY is only project integration intent, not an API owner");
            assertEquals(0, fixture.marketEvidence().get("upstreamRequests"), "catalog intake must never dispatch");
        }
    }

    @Test void sameSelectionIsIdempotentAndTwoEntriesNeverMergeWithInternalSamePath() throws Exception {
        try (var f = new BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture(json)) {
            f.start(); f.prepareMarketSurface(); var alpha = select(f, "alpha", 21L, 31L);
            var first = f.marketDetail(1); var repeated = select(f, "alpha", 21L, 31L);
            assertEquals(alpha.get("id"), repeated.get("id")); assertEquals(1, assets(f).size());
            assertEquals(first.summary().qualifiedName(), f.marketDetail(1).summary().qualifiedName());
            assertEquals(first.summary().sourceSetRevision(), f.marketDetail(1).summary().sourceSetRevision());
            select(f, "beta", 22L, 32L);
            assertEquals(200, f.changeScan("controller").statusCode());
            assertEquals(3, assets(f).size());
            assertEquals(1, assets(f).stream().map(row -> row.get("route_template")).distinct().count());
            assertEquals(3, assets(f).stream().map(row -> row.get("qualified_name")).distinct().count());
            assertEquals(1, assets(f).stream().filter(row -> !String.valueOf(row.get("qualified_name")).contains(":market:")).count());
            assertEquals(0, f.marketEvidence().get("upstreamRequests"));
            var filtered = f.multiSourcePublicRequest("GET", "/api/apis?projectId=41&sourceStatus=DISCOVERED&current=1&size=10", null);
            assertEquals(200, filtered.statusCode()); assertEquals(3, json.readTree(filtered.body()).path("total").asInt());
            evidence("same-path-isolation", f.marketEvidence());
        }
    }

    @Test void authorCreatedGraphPassesEveryBrowserHostAssertionWithCompleteReferences() throws Exception {
        try (var f = new BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture(json)) {
            f.start(); f.prepareMarketBrowserSurface(); select(f, "alpha", 21L, 31L); accept(f);
            assertEquals(200, f.saveMultiSourceConnection().statusCode()); f.grantApiTrialAcl();
            assertEquals(200, f.multiSourcePublicRequest("POST", "/api/apis/1/invocations", f.marketInvocationBody(1)).statusCode());
            f.marketCreateAuthorDraft(); assertEquals(200, f.marketTrial().get("httpStatus"));
            var trial = f.marketBrowserTrialCompleted(); assertEquals("PAID", trial.get("assignedScalar"));
            f.changePublishVersion("v1.0.0"); var published = f.marketBrowserPublished();
            var references = (Map<?, ?>) published.get("references");
            assertEquals("COMPLETE", references.get("runtimeEvidence"));
            assertEquals("COMPLETE", references.get("publicationEvidence"));
            f.changeReferenceUnavailable(true); assertEquals("UNKNOWN", f.changeReferences().get("runtimeEvidence"));
            f.changeReferenceUnavailable(false); f.marketCatalogMutation("operation-off");
            assertFalse(f.marketDetail(1).summary().sourceConfirmed());
            String code = f.marketRejectPublished(); assertEquals(3, f.marketEvidence().get("upstreamRequests"));
            evidence("author-browser-flow", Map.of("trial", trial, "published", published,
                    "unavailableCode", code, "unavailablePersistence", f.marketEvidence()));
        }
    }

    @Test void realConsoleProofTrialExplicitPublishAndFrozenOldPinsSurviveReselection() throws Exception {
        try (var f = new BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture(json)) {
            f.start(); f.prepareMarketSurface(); var integration = select(f, "alpha", 21L, 31L);
            assertEquals("隔离市场来源选择", integration.get("note"), "UTF-8 note must round trip across the real Control/Capability boundary");
            var forgedReady = f.multiSourcePublicRequest("PUT", "/api/api-market/integrations/" + integration.get("id") + "/status", Map.of("status", "READY"));
            assertEquals(400, forgedReady.statusCode());
            assertEquals("API_MARKET_EXPLICIT_RESELECTION_REQUIRED", body(forgedReady).get("code"));
            assertEquals(400, f.multiSourcePublicRequest("PUT", "/api/api-market/integrations/" + integration.get("id") + "/status", Map.of("status", "READY", "verification", "VERIFIED")).statusCode());
            assertEquals("DISCOVERED", f.marketDetail(1).summary().sourceStatus());
            assertNull(f.marketDetail(1).summary().acceptedContractHash());
            assertEquals("BLOCKED", verification(f).get("status"));
            assertTrue(f.saveMultiSourceConnection().statusCode() >= 400, "no connection before explicit acceptance");
            accept(f); f.marketSaveDraft(1L, "api-isolated-orders-alpha-get-order-http-proof");
            assertPublishRejected(f, "HTTP_API_CONNECTION_NOT_READY");
            assertEquals(200, f.saveMultiSourceConnection().statusCode());
            assertEquals("UNVERIFIED", verification(f).get("status"));
            assertPublishRejected(f, "HTTP_API_MARKET_VERIFICATION_REQUIRED");
            var denied = f.multiSourcePublicRequest("POST", "/api/apis/1/invocations", f.marketInvocationBody(1));
            assertEquals(403, denied.statusCode(), "configuration and public catalog do not grant target ACL");
            assertEquals(0, f.marketEvidence().get("upstreamRequests"));
            f.grantApiTrialAcl();
            var console = f.multiSourcePublicRequest("POST", "/api/apis/1/invocations", f.marketInvocationBody(1));
            assertEquals(200, console.statusCode()); assertEquals("SUCCEEDED", body(console).get("status"));
            assertEquals("VERIFIED", verification(f).get("status"));
            var trial = f.marketTrial(); assertEquals(200, trial.get("httpStatus"));
            assertTrue(json.writeValueAsString(trial).contains("PAID"), "actual readonly trial must reach the isolated GET");
            var observedTrial = json.readTree(f.marketReadOnlyTrialResponse());
            assertEquals("PAID", observedTrial.at("/apiOutput/state").asText());
            assertEquals("PAID", observedTrial.at("/variables/order_state").asText());
            var version = f.changePublishVersion("v1.0.0");
            var published = f.marketCallPublished();
            assertEquals(3, f.marketEvidence().get("upstreamRequests"), "Console, explicit trial, published Workflow each dispatch once");
            String pins = json.writeValueAsString(f.marketEvidence().get("pins"));
            String selection = f.marketDetail(1).summary().sourceSetRevision();
            f.marketCatalogMutation("new-version");
            assertEquals(selection, f.marketDetail(1).summary().sourceSetRevision(), "new catalog version never upgrades a binding");
            assertEquals("VERIFIED", verification(f).get("status"));
            select(f, "alpha", 41L, 41L);
            assertEquals(1, assets(f).size()); assertEquals("STALE", verification(f).get("status"));
            f.marketRejectPublished(); assertEquals(pins, json.writeValueAsString(f.marketEvidence().get("pins")));
            var reverified = f.multiSourcePublicRequest("POST", "/api/apis/1/invocations", f.marketInvocationBody(1));
            assertEquals(200, reverified.statusCode()); assertEquals("VERIFIED", verification(f).get("status"));
            assertEquals("HTTP_API_PUBLISHED_PIN_STALE", f.marketRejectPublished());
            select(f, "alpha", 21L, 31L);
            assertNotEquals(selection, f.marketDetail(1).summary().sourceSetRevision(), "v1→v2→v1 does not resurrect old proof");
            assertEquals("STALE", verification(f).get("status")); f.marketRejectPublished();
            assertEquals(pins, json.writeValueAsString(f.marketEvidence().get("pins")));
            var refs = f.multiSourcePublicRequest("GET", "/api/apis/1/references", null);
            assertEquals(200, refs.statusCode()); assertEquals("COMPLETE", body(refs).get("runtimeEvidence"));
            assertTrue(json.writeValueAsString(body(refs)).contains("PUBLISHED"));
            assertEquals(200, f.multiSourcePublicRequest("PUT", "/api/api-market/integrations/" + integration.get("id") + "/status",
                    Map.of("status", "DISABLED")).statusCode());
            assertFalse(f.marketDetail(1).summary().sourceConfirmed());
            assertEquals("BLOCKED", verification(f).get("status")); f.marketRejectPublished();
            assertEquals(pins, json.writeValueAsString(f.marketEvidence().get("pins")));
            select(f, "alpha", 21L, 31L);
            assertTrue(f.marketDetail(1).summary().sourceConfirmed());
            assertEquals("STALE", verification(f).get("status")); f.marketRejectPublished();
            evidence("controlled-lifecycle", Map.of("version", version, "publishedCall", published,
                    "proof", verification(f), "references", body(refs), "persistence", f.marketEvidence()));
        }
    }

    @Test void latestFailureUnknownConnectionChangeAndDirectoryUnavailableAreNotProof() throws Exception {
        try (var f = new BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture(json)) {
            f.start(); f.prepareMarketSurface(); select(f, "alpha", 21L, 31L); accept(f);
            assertEquals(200, f.saveMultiSourceConnection().statusCode()); f.grantApiTrialAcl();
            assertEquals(200, f.multiSourcePublicRequest("POST", "/api/apis/1/invocations", f.marketInvocationBody(1)).statusCode());
            assertEquals("VERIFIED", verification(f).get("status"));
            f.marketFailUpstream(true);
            var failed = f.multiSourcePublicRequest("POST", "/api/apis/1/invocations", f.marketInvocationBody(1));
            assertEquals(200, failed.statusCode()); assertEquals("HTTP_FAILED", body(failed).get("status"));
            assertEquals("FAILED", verification(f).get("status")); f.marketFailUpstream(false);
            f.marketDropNextUpstream();
            var unknown = f.multiSourcePublicRequest("POST", "/api/apis/1/invocations", f.marketInvocationBody(1));
            assertEquals(200, unknown.statusCode()); assertEquals("UNKNOWN", body(unknown).get("status"));
            assertEquals("UNKNOWN", verification(f).get("status"));
            assertEquals(200, f.multiSourcePublicRequest("POST", "/api/apis/1/invocations", f.marketInvocationBody(1)).statusCode());
            var current = f.marketConnection(1);
            var saved = f.multiSourcePublicRequest("PUT", "/api/apis/1/connection", Map.of("origin", current.get("origin"),
                    "authMode", "NONE", "expectedRevision", current.get("revision")));
            assertEquals(200, saved.statusCode()); assertEquals("STALE", verification(f).get("status"));
            int before = ((Number)f.marketEvidence().get("upstreamRequests")).intValue();
            for (String kind : List.of("entry", "version", "operation")) {
                f.marketCatalogMutation(kind + "-off");
                assertFalse(f.marketDetail(1).summary().sourceConfirmed());
                f.marketCatalogMutation(kind + "-on");
                assertFalse(f.marketDetail(1).summary().sourceConfirmed(), "catalog reappearance cannot silently reactivate a detected broken binding");
                select(f, "alpha", 21L, 31L); assertTrue(f.marketDetail(1).summary().sourceConfirmed());
                assertEquals("STALE", verification(f).get("status"));
            }
            assertEquals(before, ((Number)f.marketEvidence().get("upstreamRequests")).intValue());
            evidence("invalid-proof-and-unavailable", Map.of("failed", body(failed), "unknown", body(unknown), "proof", verification(f), "persistence", f.marketEvidence()));
        }
    }

    @Test void wrongScopesSelectionsForgedFactsAndUnsupportedCatalogContractsCannotWriteOwners() throws Exception {
        try (var f = new BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture(json)) {
            f.start(); f.prepareMarketSurface(); Map<String, Object> valid = selection(21L, 31L);
            List<Map<String, Object>> invalid = new java.util.ArrayList<>();
            for (var change : List.of(Map.of("projectId", 999999L), Map.of("projectCode", "foreign"),
                    Map.of("environment", "PRODUCTION"), Map.of("versionId", 22L), Map.of("operationIds", List.of(32L)),
                    Map.of("operationIds", List.of()), Map.of("operationIds", List.of(31L, 31L)),
                    Map.of("qualifiedName", "http-api:forged"), Map.of("origin", "http://forged.invalid"),
                    Map.of("authMode", "NONE"), Map.of("status", "READY"), Map.of("verification", Map.of("status", "VERIFIED")))) {
                var request = new LinkedHashMap<String, Object>(valid); request.putAll(change); invalid.add(request);
            }
            var missingVersion = new LinkedHashMap<>(valid); missingVersion.remove("versionId"); invalid.add(missingVersion);
            for (var request : invalid) {
                var response = f.multiSourcePublicRequest("POST", "/api/api-market/entries/isolated-orders-alpha/integrations", request);
                assertTrue(response.statusCode() >= 400, "invalid selection must reject: " + request.keySet());
                assertEquals(0, assets(f).size()); assertEquals(0, f.marketEvidence().get("upstreamRequests"));
            }
            assertTrue(f.multiSourcePublicRequest("POST", "/api/api-market/entries/not-present/integrations", valid).statusCode() >= 400);
            f.marketCatalogMutation("wrong-project-role");
            assertEquals(403, f.multiSourcePublicRequest("POST", "/api/api-market/entries/isolated-orders-alpha/integrations", valid).statusCode());
            assertEquals(403, f.multiSourcePublicRequest("GET", "/api/api-market/integrations?projectId=41", null).statusCode());
            f.marketCatalogMutation("restore-project-role");
            for (var pair : List.of(List.of("auth-required", "auth-none"), List.of("write-method", "read-method"),
                    List.of("missing-media", "media-on"), List.of("missing-status", "status-on"))) {
                f.marketCatalogMutation(pair.get(0));
                assertEquals(400, f.multiSourcePublicRequest("POST", "/api/api-market/entries/isolated-orders-alpha/integrations", valid).statusCode());
                assertEquals(0, assets(f).size()); f.marketCatalogMutation(pair.get(1));
            }
            f.marketCatalogMutation("unsupported-schema");
            var unsupported = f.multiSourcePublicRequest("POST", "/api/api-market/entries/isolated-orders-alpha/integrations", valid);
            assertEquals(400, unsupported.statusCode()); assertEquals("API_MARKET_SCHEMA_UNSUPPORTED", body(unsupported).get("code"));
            assertEquals(0, assets(f).size()); evidence("scope-and-contract-refusals", f.marketEvidence());
        }
    }

    @Test void legacyRawMarketRefStaysSaveableButPublicationAndExecutionRejectBeforeUpstream() throws Exception {
        try (var f = new BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture(json)) {
            f.start(); f.prepareMarketSurface();
            for (String placement : List.of("top", "nested")) {
                f.marketSaveRawDraft(placement);
                assertTrue(json.writeValueAsString(f.marketValidate()).contains("GRAPH_HTTP_API_MARKET_BINDING_REQUIRED"));
                assertTrue(((Number)f.marketPublishAttempt("v1.0.0").get("httpStatus")).intValue() >= 400);
                assertTrue(json.writeValueAsString(f.marketRawDebug()).contains("RUNTIME_HTTP_API_MARKET_BINDING_REQUIRED"));
                assertEquals(0, f.marketEvidence().get("upstreamRequests"));
            }
            f.marketSaveRawDraft("ordinary");
            assertFalse(json.writeValueAsString(f.marketValidate()).contains("GRAPH_HTTP_API_MARKET_BINDING_REQUIRED"));
            String ordinary = json.writeValueAsString(f.marketRawDebug());
            assertFalse(ordinary.contains("RUNTIME_HTTP_API_MARKET_BINDING_REQUIRED"));
            assertTrue(ordinary.contains("RUNTIME_GRAPH_EXECUTED"), ordinary);
            assertEquals(1, f.marketEvidence().get("upstreamRequests"), "ordinary raw HTTP retains its existing isolated upstream behavior");
            evidence("raw-market-ref-refusals", Map.of("upstreamRequests", f.marketEvidence().get("upstreamRequests")));
        }
    }

    private Map<String, Object> select(BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture f, String entry, long version, long operation) throws Exception {
        var response = f.multiSourcePublicRequest("POST", "/api/api-market/entries/isolated-orders-" + entry + "/integrations", selection(version, operation));
        assertEquals(200, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8)); return body(response);
    }
    private Map<String, Object> selection(long version, long operation) {
        return Map.of("projectId", 41L, "projectCode", "orders", "environment", "DEVELOPMENT", "versionId", version,
                "operationIds", List.of(operation), "note", "隔离市场来源选择");
    }
    private void accept(BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture f) throws Exception {
        assertEquals(200, f.multiSourcePublicRequest("POST", "/api/apis/1/accept", Map.of("expectedSourceSetRevision", f.marketDetail(1).summary().sourceSetRevision())).statusCode());
    }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> assets(BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture f) { return (List<Map<String, Object>>)f.marketEvidence().get("assets"); }
    @SuppressWarnings("unchecked") private Map<String, Object> verification(BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture f) throws Exception { return (Map<String, Object>)f.marketConnection(1).get("verification"); }
    private Map<String, Object> body(java.net.http.HttpResponse<byte[]> response) throws Exception { return json.readValue(response.body(), new com.fasterxml.jackson.core.type.TypeReference<>() { }); }
    private void assertPublishRejected(BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture f, String code) throws Exception {
        var response = f.marketPublishAttempt("v1.0.0");
        assertTrue(((Number)response.get("httpStatus")).intValue() >= 400, "configured-only market API must not publish");
        assertTrue(json.writeValueAsString(response).contains(code), json.writeValueAsString(response));
        assertEquals(0, f.marketEvidence().get("upstreamRequests"));
    }
    private void evidence(String name, Map<String, Object> value) throws Exception {
        String directory = System.getProperty("bmapi.market.evidence"); if (directory == null) return;
        Path path = Path.of(directory).toAbsolutePath().resolve(name + ".json"); Files.createDirectories(path.getParent());
        assertFalse(Files.exists(path), "never replace earlier evidence");
        Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(value), StandardCharsets.UTF_8);
    }
}
