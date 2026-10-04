package com.enterprise.ai.runtime.workflow.bmapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Normal OpenAPI scan → owner acceptance → explicit project credential/connection → real HTTP business write. */
class HttpApiWriteConsoleE2eIntegrationTest {
    private static final String PATH = "/api/apis/1/invocations";
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture fixture;

    @BeforeEach void start() throws Exception {
        fixture = new BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture(json);
        fixture.startWriteFixture(); fixture.prepareWriteApiBrowserSurface();
        fixture.discoverWriteApi(); fixture.acceptWriteApiAndConnect();
    }
    @AfterEach void close() throws Exception { if (fixture != null) fixture.close(); }
    private void authorize() { fixture.grantApiTrialAcl(); }
    private HttpResponse<byte[]> request(Map<String, Object> body) throws Exception {
        return fixture.multiSourcePublicRequest("POST", PATH, body);
    }
    private Map<String, Object> post(Map<String, Object> body) throws Exception {
        var response = request(body); assertEquals(200, response.statusCode()); return fixture.writeResponse(response);
    }
    private void reject(Map<String, Object> body, int expected) throws Exception { assertEquals(expected, request(body).statusCode()); }
    private Map<String, Object> query(Map<String, Object> body) throws Exception {
        var response = fixture.multiSourcePublicRequest("GET", "/api/api-invocations/" + body.get("invocationId") + "?projectCode=orders", null);
        assertEquals(200, response.statusCode()); return fixture.writeResponse(response);
    }

    @Test void normalScanConcurrentSameIdUsesOnePostOneStateChangeAndRealWriteAudit() throws Exception {
        authorize(); fixture.assertWriteApiCounts(0, 0);
        var body = fixture.writeApiBody(); var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> post(body)); var b = executor.submit(() -> post(body));
            a.get(20, TimeUnit.SECONDS); b.get(20, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        var result = post(body);
        assertEquals("SUCCEEDED", result.get("status")); assertEquals("CONFIRMED", result.get("dispatchStage"));
        assertEquals(200, result.get("httpStatus"));
        assertNotNull(result.get("runId")); assertNotNull(result.get("traceId"));
        String safe = json.writeValueAsString(result);
        assertFalse(safe.contains(StatefulWriteApiFixtureController.PROJECT_SECRET));
        assertFalse(safe.contains(StatefulWriteApiFixtureController.SENSITIVE_INPUT));
        var output = (Map<?, ?>) ((Map<?, ?>) result.get("result")).get("body");
        assertEquals(false, output.get("notify")); assertEquals("ABSENT", output.get("priority"), "source default must not be applied");
        assertEquals(200, fixture.multiSourcePublicRequest("GET", "/api/runops/traces/" + result.get("traceId"), null).statusCode());
        for (int n = 0; n < 3; n++) assertEquals("SUCCEEDED", query(body).get("status"));
        var changed = new LinkedHashMap<>(body); changed.put("body", Map.of("note", "different", "apiKey", StatefulWriteApiFixtureController.SENSITIVE_INPUT));
        reject(changed, 409);
        assertEquals(409, fixture.requestAsOtherActor("orders", PATH, body).statusCode());
        fixture.assertWriteApiCounts(1, 1); fixture.assertWriteApiAuditSafe();
        var evidence = fixture.writeApiEvidence();
        assertTrue(String.valueOf(evidence.get("ledger")).contains("WRITE"));
        assertTrue(String.valueOf(evidence.get("runs")).contains("confirmedSideEffect"));
        assertTrue(String.valueOf(evidence.get("traceRoots")).contains("\"sideEffect\":\"WRITE\""));
        assertTrue(String.valueOf(evidence.get("traceRoots")).contains("\"confirmedSideEffect\":true"));
        System.out.println("BMAPI_3B4_REAL_WRITE " + json.writeValueAsString(evidence));
    }

    @Test void missingConfirmationStaleHashSourceConnectionInvalidBodyAndForgeryAllDispatchZero() throws Exception {
        authorize(); var original = fixture.writeApiBody();
        var noConfirm = new LinkedHashMap<>(original); noConfirm.remove("confirmedSideEffect"); reject(noConfirm, 409);
        noConfirm.put("confirmedSideEffect", false); reject(noConfirm, 409);
        var noSource = new LinkedHashMap<>(original); noSource.remove("expectedSourceSetRevision"); reject(noSource, 409);
        for (String fact : List.of("expectedContractHash", "expectedSourceSetRevision")) {
            var stale = new LinkedHashMap<>(original); stale.put(fact, "c".repeat(64)); reject(stale, 409);
        }
        for (Map<String, Object> invalid : List.<Map<String, Object>>of(Map.of("apiKey", "present"),
                Map.of("note", "", "apiKey", "present"), Map.of("note", "valid", "apiKey", "present", "priority", 0),
                Map.of("note", "valid", "apiKey", "present", "notify", "false"),
                Map.of("note", "valid", "apiKey", "present", "kind", "OTHER"),
                Map.of("note", Map.of("nested", true), "apiKey", "present"),
                Map.of("note", "valid", "apiKey", "present", "undeclared", "value"))) {
            var body = new LinkedHashMap<>(original); body.put("body", invalid); reject(body, 400);
        }
        for (String forged : List.of("url", "headers", "cookies", "httpMethod", "routeTemplate", "sideEffect",
                "projectId", "projectCode", "environment", "platformActorId", "identity", "credentialRef")) {
            var body = new LinkedHashMap<>(original); body.put(forged, "forged"); reject(body, 400);
        }
        fixture.changeWriteApiConnection(); reject(original, 409);
        fixture.changeWriteApiSource(); reject(fixture.writeApiBody(), 409);
        fixture.assertWriteApiCounts(0, 0);
    }

    @Test void discoveryAndConnectionDoNotAuthorizeMissingAclGrantOrForeignProject() throws Exception {
        var body = fixture.writeApiBody(); reject(body, 403); fixture.assertWriteApiCounts(0, 0);
        authorize(); fixture.setWriteApiAcl(false); reject(body, 403); fixture.setWriteApiAcl(true);
        fixture.setWriteApiPermission(false); reject(body, 403); fixture.setWriteApiPermission(true);
        assertEquals(403, fixture.requestAsOtherActor("foreign-project", PATH, body).statusCode());
        fixture.assertWriteApiCounts(0, 0);
    }

    @Test void confirmationCannotUseAnUnreadOrRevisedProjectCredential() throws Exception {
        authorize(); var original = fixture.writeApiBody();
        var missing = new LinkedHashMap<>(original); missing.remove("expectedCredentialRevision"); reject(missing, 409);
        var forged = new LinkedHashMap<>(original); forged.put("expectedCredentialRevision", "c".repeat(64)); reject(forged, 409);
        fixture.changeWriteApiCredential(); reject(original, 409); fixture.assertWriteApiCounts(0, 0);
    }

    @Test void consoleWriteGrantDoesNotBypassStudioReadOnlyBoundary() throws Exception {
        authorize(); fixture.assertPublishedWriteApiStillRejectsStudioDebug(); fixture.assertWriteApiCounts(0, 0);
    }

    @Test void upstreamEofAfterWriteIsUnknownQueriesNeverResendAndExplicitNewIdWritesAgain() throws Exception {
        authorize(); var original = fixture.writeApiBody(); fixture.dropNextWriteApiResponse();
        var result = post(original); assertEquals("UNKNOWN", result.get("status"));
        fixture.assertWriteApiCounts(1, 1);
        for (int n = 0; n < 3; n++) assertEquals("UNKNOWN", query(original).get("status"));
        assertEquals("UNKNOWN", post(original).get("status")); fixture.assertWriteApiCounts(1, 1);
        var next = fixture.writeApiBody(); assertNotEquals(original.get("invocationId"), next.get("invocationId"));
        var unconfirmed = new LinkedHashMap<>(next); unconfirmed.remove("confirmedSideEffect"); reject(unconfirmed, 409);
        assertEquals("SUCCEEDED", post(next).get("status")); fixture.assertWriteApiCounts(2, 2);
        fixture.assertWriteApiAuditSafe();
        System.out.println("BMAPI_3B4_UPSTREAM_EOF " + json.writeValueAsString(fixture.writeApiEvidence()));
    }

    @Test void actualPublicResponseLossPreservesSucceededLedgerAndOnlyQueriesSameId() throws Exception {
        authorize(); var original = fixture.writeApiBody(); fixture.dropNextWriteControlResponse();
        assertThrows(IOException.class, () -> request(original)); fixture.assertWriteApiCounts(1, 1);
        assertEquals("SUCCEEDED", query(original).get("status"));
        assertEquals("SUCCEEDED", query(original).get("status")); fixture.assertWriteApiCounts(1, 1);
        System.out.println("BMAPI_3B4_PUBLIC_LOSS " + json.writeValueAsString(fixture.writeApiEvidence()));
    }

    @Test void actual307And503DoNotRedirectOrRetryAndParsingFailureDoesNotPretendNotExecuted() throws Exception {
        authorize(); int writes = 0;
        for (String mode : List.of("307", "503", "401", "INVALID_JSON")) {
            fixture.writeApi.responseMode.set(mode); var body = fixture.writeApiBody(); var result = post(body); writes++;
            if ("INVALID_JSON".equals(mode)) assertEquals("UNKNOWN", result.get("status"));
            else { assertEquals("HTTP_FAILED", result.get("status")); assertEquals(Integer.valueOf(mode), result.get("httpStatus")); }
            fixture.assertWriteApiCounts(writes, writes); query(body); post(body); fixture.assertWriteApiCounts(writes, writes);
        }
        System.out.println("BMAPI_3B4_HTTP_ERRORS " + json.writeValueAsString(fixture.writeApiEvidence()));
    }
}
