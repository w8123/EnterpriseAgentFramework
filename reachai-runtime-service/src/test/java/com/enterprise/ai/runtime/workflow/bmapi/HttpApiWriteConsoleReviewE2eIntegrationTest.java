package com.enterprise.ai.runtime.workflow.bmapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Review counterexamples use normal OpenAPI discovery and real public HTTP/H2, never inserted owner rows. */
class HttpApiWriteConsoleReviewE2eIntegrationTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture fixture;

    @BeforeEach void start() throws Exception {
        fixture = new BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture(json);
        fixture.startWriteFixture(); fixture.prepareWriteApiBrowserSurface("order-notes-sensitive.yaml");
        fixture.discoverWriteApi(); fixture.acceptWriteApiAndConnect(); fixture.grantApiTrialAcl();
    }
    @AfterEach void close() throws Exception { if (fixture != null) fixture.close(); }

    @Test void confirmedOptionalNullIsPublic400BeforeAnyUpstreamOrLedger() throws Exception {
        var command = fixture.writeApiBody();
        var body = new LinkedHashMap<>((Map<String, Object>) command.get("body")); body.put("priority", null);
        command.put("body", body);
        var response = fixture.multiSourcePublicRequest("POST", "/api/apis/1/invocations", command);
        System.out.println("BMAPI_3B4_R1_NULL actualStatus=" + response.statusCode()
                + " upstreamPosts=" + fixture.writeApi.postHits.get() + " notes=" + fixture.writeApi.noteCount());
        assertEquals(400, response.statusCode(), "explicit optional null must be rejected before dispatch");
        fixture.assertWriteApiCounts(0, 0);
    }

    @Test void actualJsonBodyPreservesAbsentFalseZeroAndAllowedEmptyWithoutDefaults() throws Exception {
        var command = fixture.writeApiBody();
        var body = new LinkedHashMap<>((Map<String, Object>) command.get("body")); body.put("quantity", 0); body.put("caption", "");
        command.put("body", body);
        var response = fixture.multiSourcePublicRequest("POST", "/api/apis/1/invocations", command);
        assertEquals(200, response.statusCode()); assertEquals("SUCCEEDED", fixture.writeResponse(response).get("status"));
        var actual = fixture.writeApi.lastBody.get();
        assertEquals(Set.of("note", "apiKey", "notify", "quantity", "caption"), actual.keySet());
        assertEquals(false, actual.get("notify")); assertEquals(0, actual.get("quantity")); assertEquals("", actual.get("caption"));
        assertFalse(actual.containsKey("priority")); assertFalse(actual.containsKey("kind"));
        assertTrue(body.equals(actual), "actual body must exactly match confirmed input, without printing sensitive input");
        fixture.assertWriteApiCounts(1, 1); fixture.assertWriteApiAuditSafe();
        var safeBody = new LinkedHashMap<>(actual); safeBody.put("apiKey", "[redacted]");
        System.out.println("BMAPI_3B4_R1_ACTUAL_BODY " + json.writeValueAsString(safeBody));
    }

    @Test void acceptedPasswordFormatAndWriteOnlyRedactNeutralEchoInResponseQueryLedgerRunAndTrace() throws Exception {
        assertSourceDeclaredSensitiveEcho(false);
    }

    @Test void resultUsesPreparedAcceptedSchemaEvenIfAnotherContractIsAcceptedAfterDispatch() throws Exception {
        assertSourceDeclaredSensitiveEcho(true);
    }

    private void assertSourceDeclaredSensitiveEcho(boolean changeAfterDispatch) throws Exception {
        var owner = fixture.writeApiOwner();
        assertEquals("password", owner.acceptedContract().at("/requestBody/schema/properties/accessCode/format").asText());
        assertTrue(owner.acceptedContract().at("/requestBody/schema/properties/deliveryMark/writeOnly").asBoolean());
        String privateA = UUID.randomUUID().toString(), privateB = UUID.randomUUID().toString();
        var command = fixture.writeApiBody();
        var body = new LinkedHashMap<>((Map<String, Object>) command.get("body"));
        body.put("accessCode", privateA); body.put("deliveryMark", privateB); command.put("body", body);
        if (changeAfterDispatch) fixture.writeApi.afterAppend.set(() -> {
            try { fixture.removeWriteApiSensitiveDeclarationsAndAccept(); }
            catch (Exception failure) { throw new IllegalStateException("isolated accepted schema edit failed"); }
        });
        var initial = fixture.multiSourcePublicRequest("POST", "/api/apis/1/invocations", command);
        assertEquals(200, initial.statusCode()); var result = fixture.writeResponse(initial);
        assertEquals("SUCCEEDED", result.get("status"));
        assertTrue(privateA.equals(fixture.writeApi.lastBody.get().get("accessCode")), "actual password-format input preserved");
        assertTrue(privateB.equals(fixture.writeApi.lastBody.get().get("deliveryMark")), "actual writeOnly input preserved");
        var queried = fixture.multiSourcePublicRequest("GET", "/api/api-invocations/" + command.get("invocationId") + "?projectCode=orders", null);
        assertEquals(200, queried.statusCode());
        var trace = fixture.multiSourcePublicRequest("GET", "/api/runops/traces/" + result.get("traceId"), null);
        assertEquals(200, trace.statusCode());
        List<String> privateValues = List.of(privateA, privateB);
        var leaks = new LinkedHashMap<>(fixture.writeApiSensitiveAuditLeaks(privateValues));
        leaks.put("initialResponse", containsAny(initial.body(), privateValues));
        leaks.put("sameIdQuery", containsAny(queried.body(), privateValues));
        leaks.put("publicTrace", containsAny(trace.body(), privateValues));
        System.out.println("BMAPI_3B4_R2_SAFE_FLAGS preparedSchemaPinned=" + changeAfterDispatch + " " + json.writeValueAsString(leaks));
        // Only flags can appear in a failing assertion, never the generated private values or raw result.
        assertFalse(leaks.containsValue(true), "source-declared sensitive values leaked at one or more checked boundaries");
        var output = (Map<?, ?>) ((Map<?, ?>) result.get("result")).get("body");
        assertEquals("[redacted]", output.get("receivedA")); assertEquals("[redacted]", output.get("receivedB"));
        assertEquals("isolated-write", output.get("note")); assertEquals(1, output.get("noteCount")); assertEquals(false, output.get("notify"));
        fixture.assertWriteApiCounts(1, 1); fixture.assertWriteApiAuditSafe();
        if (changeAfterDispatch) {
            assertFalse(fixture.writeApiOwner().acceptedContract().at("/requestBody/schema/properties/accessCode").has("format"));
            assertFalse(fixture.writeApiOwner().acceptedContract().at("/requestBody/schema/properties/deliveryMark").has("writeOnly"));
        }
    }

    private boolean containsAny(byte[] bytes, List<String> privateValues) {
        String response = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        return privateValues.stream().anyMatch(response::contains);
    }
}
