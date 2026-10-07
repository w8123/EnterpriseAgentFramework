package com.enterprise.ai.runtime.workflow.bmapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** No inserted tool assets: normal Starter registration → accepted owner → public Console → real SDK. */
class BusinessMethodWriteConsoleE2eIntegrationTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture fixture;
    private static final String PATH = "/api/business-methods/" + StatefulWriteBusinessFixture.STORAGE_NAME + "/invocations";
    private static final String SENSITIVE = "synthetic-2e-sensitive-input";

    @BeforeEach void start() throws Exception {
        fixture = new BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture(json);
        fixture.startWriteFixture();
        fixture.registerWriteMethods(true);
    }
    @AfterEach void close() throws Exception { if (fixture != null) fixture.close(); }

    private void authorize() { fixture.grantTrialPermissions(); fixture.grantWriteAcl(); }
    private Map<String, Object> command() throws Exception {
        var context = fixture.writeContext(StatefulWriteBusinessFixture.STORAGE_NAME);
        return Map.of("invocationId", UUID.randomUUID().toString(), "confirmedSideEffect", true,
                "expectedContractHash", context.get("currentContractHash"), "expectedExecutionRevision", context.get("executionRevision"),
                "input", Map.of("orderNo", "ORD-2E", "note", "isolated-write", "apiKey", SENSITIVE));
    }
    private Map<String, Object> post(Map<String, Object> body) throws Exception {
        var response = fixture.writeRequest("POST", PATH, body);
        assertEquals(200, response.statusCode());
        return fixture.writeResponse(response);
    }
    private void reject(Map<String, Object> body, int status, String code) throws Exception {
        var response = fixture.writeRequest("POST", PATH, body);
        assertEquals(status, response.statusCode());
        assertEquals(code, fixture.writeResponse(response).get("code"));
    }

    @Test void normalRegistrationConfirmedWriteAndConcurrentSameIdProduceOneStateChangeAndSafeTrace() throws Exception {
        authorize();
        fixture.assertWriteCatalogPagination();
        var context = fixture.writeContext(StatefulWriteBusinessFixture.STORAGE_NAME);
        assertEquals("WRITE", context.get("sideEffect")); assertEquals("BUSINESS_METHOD", context.get("assetType"));
        assertEquals(context.get("currentContractHash"), context.get("acceptedContractHash"));
        assertEquals(context.get("currentContractHash"), context.get("sourceContractHash"));
        assertEquals(false, context.get("businessIdentityRequired")); fixture.assertWriteCounts(0, 0);
        var body = command();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> post(body)); var second = executor.submit(() -> post(body));
            first.get(20, TimeUnit.SECONDS); second.get(20, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        var confirmed = post(body);
        assertEquals("SUCCEEDED", confirmed.get("status")); assertEquals("CONFIRMED", confirmed.get("dispatchStage"));
        assertNotNull(confirmed.get("runId")); assertNotNull(confirmed.get("traceId"));
        assertFalse(json.writeValueAsString(confirmed).contains(SENSITIVE));
        var changed = new LinkedHashMap<>(body); changed.put("input", Map.of("orderNo", "ORD-2E", "note", "different"));
        reject(changed, 409, "CONSOLE_CAPABILITY_INVOCATION_CONFLICT");
        var foreign = fixture.writeRequestAsOtherActor(body);
        assertEquals(409, foreign.statusCode()); assertEquals("CONSOLE_CAPABILITY_INVOCATION_CONFLICT", fixture.writeResponse(foreign).get("code"));
        for (int n = 0; n < 3; n++) assertEquals("SUCCEEDED", fixture.writeResponse(fixture.writeRequest("GET",
                "/api/business-method-invocations/" + body.get("invocationId"), null)).get("status"));
        assertEquals(200, fixture.writeRequest("GET", "/api/runops/traces/" + confirmed.get("traceId"), null).statusCode());
        fixture.assertWriteCounts(1, 1); fixture.assertWriteAuditSafe(SENSITIVE);
        var evidence = fixture.writeEvidence();
        assertEquals("bmapi2d", evidence.get("signedProject")); assertNull(evidence.get("signedTenant"));
        assertEquals(true, evidence.get("businessUserAbsent")); assertEquals(true, evidence.get("businessRolesAbsent"));
        System.out.println("BMAPI_2E_WRITE_CONFIRMED " + json.writeValueAsString(evidence));
    }

    @Test void platformAclConfirmationStaleHashInputAndBusinessIdentityRefuseWithoutAnyWrite() throws Exception {
        var unscoped = Map.<String, Object>of("invocationId", UUID.randomUUID().toString(), "expectedContractHash", "a".repeat(64), "expectedExecutionRevision", "c".repeat(64), "input", Map.of(), "confirmedSideEffect", true);
        assertEquals(403, fixture.writeRequest("POST", PATH, unscoped).statusCode()); fixture.assertWriteCounts(0, 0);
        fixture.grantTrialPermissions();
        assertEquals(403, fixture.writeRequest("POST", PATH, unscoped).statusCode()); fixture.assertWriteCounts(0, 0);
        fixture.grantWriteAcl(); var body = new LinkedHashMap<>(command());
        body.put("confirmedSideEffect", false); reject(body, 409, "CONSOLE_CAPABILITY_CONFIRMATION_REQUIRED"); fixture.assertWriteCounts(0, 0);
        body.put("confirmedSideEffect", true); var stale = new LinkedHashMap<>(body); stale.put("expectedContractHash", "f".repeat(64));
        reject(stale, 409, "CAPABILITY_PUBLISHED_CONTRACT_CHANGED"); fixture.assertWriteCounts(0, 0);
        var forged = new LinkedHashMap<>(body); forged.put("actor", "business-user");
        reject(forged, 400, "CONSOLE_CAPABILITY_REQUEST_INVALID"); fixture.assertWriteCounts(0, 0);
        var invalid = new LinkedHashMap<>(body); invalid.put("input", Map.of("orderNo", 123, "note", "input-invalid"));
        var rejected = post(invalid); assertEquals("NOT_DISPATCHED", rejected.get("status"));
        assertEquals("CAPABILITY_INPUT_INVALID", rejected.get("code")); fixture.assertWriteCounts(0, 1);
        fixture.setWriteSourceAvailability("SOURCE_MISSING");
        reject(body, 409, "CAPABILITY_CONTRACT_NOT_ACCEPTED"); fixture.assertWriteCounts(0, 1);
        fixture.setWriteSourceAvailability("READY");
        var identity = fixture.writeContext("bmapi2d_identityOnly"); assertEquals(true, identity.get("businessIdentityRequired"));
        var identityResponse = fixture.writeRequest("POST", "/api/business-methods/bmapi2d_identityOnly/invocations", Map.of(
                "invocationId", UUID.randomUUID().toString(), "input", Map.of(), "expectedContractHash", identity.get("currentContractHash"), "expectedExecutionRevision", identity.get("executionRevision"), "confirmedSideEffect", true));
        assertEquals(409, identityResponse.statusCode()); assertEquals("CAPABILITY_BUSINESS_IDENTITY_REQUIRED", fixture.writeResponse(identityResponse).get("code"));
        fixture.assertWriteCounts(0, 1);
        System.out.println("BMAPI_2E_REFUSALS " + json.writeValueAsString(fixture.writeEvidence()));
    }

    @Test void actualSourceResyncDriftInvalidatesPreparedWriteBeforeSdk() throws Exception {
        authorize(); var body = command(); fixture.syncWriteDrift();
        reject(body, 409, "CAPABILITY_CONTRACT_NOT_ACCEPTED"); fixture.assertWriteCounts(0, 0);
        System.out.println("BMAPI_2E_SOURCE_DRIFT zero_sdk_and_method_hits");
    }

    @Test void responseLostAfterActualSdkWriteIsUnknownAndQueriesOrDuplicateIdCannotAppendAgain() throws Exception {
        authorize(); var body = command(); fixture.dropNextWriteSdkResponse();
        var unknown = post(body); assertEquals("UNKNOWN", unknown.get("status")); assertEquals("UNCONFIRMED", unknown.get("dispatchStage"));
        assertEquals(body.get("invocationId"), unknown.get("invocationId")); fixture.assertWriteCounts(1, 1);
        for (int n = 0; n < 3; n++) assertEquals("UNKNOWN", fixture.writeResponse(fixture.writeRequest("GET",
                "/api/business-method-invocations/" + body.get("invocationId"), null)).get("status"));
        assertEquals("UNKNOWN", post(body).get("status")); fixture.assertWriteCounts(1, 1);
        var newAttempt = command(); assertNotEquals(body.get("invocationId"), newAttempt.get("invocationId"));
        assertEquals("SUCCEEDED", post(newAttempt).get("status")); fixture.assertWriteCounts(2, 2); fixture.assertWriteAuditSafe(SENSITIVE);
        System.out.println("BMAPI_2E_UNKNOWN_QUERY_NEW_ATTEMPT " + json.writeValueAsString(fixture.writeEvidence()));
    }

    @Test void lostPublicResponseRetainsCommittedIdAndReadOnlyQueryFindsOriginalSuccess() throws Exception {
        authorize(); var body = command(); fixture.dropNextWriteControlResponse();
        assertThrows(IOException.class, () -> fixture.writeRequest("POST", PATH, body));
        fixture.assertWriteCounts(1, 1);
        var original = fixture.writeResponse(fixture.writeRequest("GET", "/api/business-method-invocations/" + body.get("invocationId"), null));
        assertEquals("SUCCEEDED", original.get("status")); assertEquals(body.get("invocationId"), original.get("invocationId"));
        fixture.assertWriteCounts(1, 1);
        System.out.println("BMAPI_2E_PUBLIC_RESPONSE_LOSS " + json.writeValueAsString(fixture.writeEvidence()));
    }

    @Test void acceptedWriteCannotEnterStudioReadOnlyTrialOrOrdinaryUntrustedDebug() throws Exception {
        authorize(); fixture.assertWriteExcludedFromReadOnlyAndDebug();
    }
}
