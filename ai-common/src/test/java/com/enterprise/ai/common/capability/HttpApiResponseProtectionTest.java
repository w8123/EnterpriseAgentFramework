package com.enterprise.ai.common.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HttpApiResponseProtectionTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void consoleAllowsProvenJsonArraysButStudioFirstCallStaysObjectOnly() throws Exception {
        var contract = json.readTree("""
                {"identity":{"method":"GET","routeTemplate":"/fixture","mappingConditions":{}},
                 "sideEffect":"READ_ONLY","parameters":[],"authentication":{"state":"UNKNOWN"},
                 "responses":[{"contentTypes":["application/json"],"schema":{"type":"array","items":{"type":"string"}}}]}
                """);
        assertNull(HttpApiRequestPolicy.unsupportedReason(contract));
        assertNotNull(HttpApiFirstCallPolicy.unsupportedReason(contract));
        assertTrue(HttpApiRequestPolicy.confirmsResponse(contract, List.of("fixture")));
        assertFalse(HttpApiRequestPolicy.confirmsResponse(contract, Map.of("wrong", true)));
        assertEquals(List.of("[redacted]"), HttpApiResponseProtection.prepare(contract, Map.of("token", "fixture"),
                Map.of(), null, null).protect(List.of("fixture")));
    }

    @Test
    void rootArraysNestedWriteOnlyAndSensitiveContainersProtectEchoesUnderOtherNames() throws Exception {
        var contract = json.readTree("""
                {"requestBody":{"schema":{"type":"array","items":{"type":"object","properties":{
                  "profile":{"type":"object","properties":{"pin":{"type":"string","writeOnly":true}}},
                  "secrets":{"type":"array","items":{"type":"string"}}}}}}}
                """);
        var protection = HttpApiResponseProtection.prepare(contract, Map.of(), Map.of(),
                List.of(Map.of("profile", Map.of("pin", "nested-private-value"),
                        "secrets", List.of("array-private-value"))), null);
        var safe = protection.protect(Map.of("echo", "nested-private-value/array-private-value", "other", "public"));
        assertEquals(Map.of("echo", "[redacted]/[redacted]", "other", "public"), safe);
    }

    @Test
    void nullableStillHonorsEnumAndConst() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> HttpApiJsonBinding.bind(json.readTree(
                "{\"type\":\"string\",\"nullable\":true,\"enum\":[\"known\"]}"), null, json));
        assertThrows(IllegalArgumentException.class, () -> HttpApiJsonBinding.bind(json.readTree(
                "{\"type\":\"string\",\"nullable\":true,\"const\":\"known\"}"), null, json));
        assertNull(HttpApiJsonBinding.bind(json.readTree(
                "{\"type\":\"string\",\"nullable\":true,\"enum\":[null,\"known\"]}"), null, json));
        assertEquals(Map.of("value", 1), HttpApiJsonBinding.bind(json.readTree(
                "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"number\"}},\"enum\":[{\"value\":1.0}]}"),
                Map.of("value", 1), json));
    }
}
