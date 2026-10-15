package com.enterprise.ai.common.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HttpApiConsoleExtendedContractTest {
    private final ObjectMapper json = new ObjectMapper();
    private ObjectNode contract(String method, String effect, String schema) throws Exception {
        return (ObjectNode) json.readTree("""
            {"identity":{"method":"%s","routeTemplate":"/records/{id}","mappingConditions":{}},
             "sideEffect":"%s","parameters":[{"name":"id","location":"PATH","required":true,"schema":{"type":"string"}}],
             "requestBody":{"required":true,"contentTypes":["application/json"],"schema":%s},
             "responses":[{"status":"200","contentTypes":["application/json"],"schema":{"type":"object"}}],
             "authentication":{"state":"NONE"}}
            """.formatted(method, effect, schema));
    }

    @Test void actualPutPatchAndDeleteContractsHaveValidatedBodyAndBodylessForms() throws Exception {
        for (String method : List.of("PUT", "PATCH", "DELETE")) {
            var contract = contract(method, "WRITE", "{\"type\":\"object\",\"properties\":{\"enabled\":{\"type\":\"boolean\"}},\"required\":[\"enabled\"]}");
            assertNull(HttpApiConsolePolicy.unsupportedReason(contract), method);
            assertEquals(Map.of("enabled", false), HttpApiConsolePolicy.bind(contract, Map.of("id", "R 1"), Map.of(), Map.of("enabled", false), json).jsonBody());
            contract.remove("requestBody");
            assertNull(HttpApiConsolePolicy.unsupportedReason(contract));
            assertNull(HttpApiConsolePolicy.bind(contract, Map.of("id", "R1"), Map.of(), null, json).jsonBody());
        }
    }

    @Test void legacyGetMutationsAreSupportedButNeverClassifiedAsReadOnly() throws Exception {
        for (String effect : List.of("WRITE", "IDEMPOTENT_WRITE", "IRREVERSIBLE")) {
            var contract = contract("GET", effect, "{}"); contract.remove("requestBody");
            assertNull(HttpApiConsolePolicy.unsupportedReason(contract));
            assertFalse(HttpApiDraftTrialPolicy.readOnlyOwner("GET", contract));
        }
    }

    @Test void actualRootArrayCanCrossThePublicWireWithoutBecomingAnObject() throws Exception {
        var contract = contract("POST", "WRITE", "{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"minItems\":1,\"maxItems\":3}");
        assertNull(HttpApiConsolePolicy.unsupportedReason(contract));
        var body = List.of("A", "B");
        Map<String,Object> wire = new LinkedHashMap<>();
        wire.put("invocationId", UUID.randomUUID().toString()); wire.put("expectedContractHash", "a".repeat(64));
        wire.put("connectionRevision", 1); wire.put("body", body);
        assertEquals(body, HttpApiConsoleContracts.PublicInvocationRequest.fromWire(wire).body());
    }

    @Test void nestedDtoAndArrayItemsAreValidatedWithoutRemovingConfirmedInput() throws Exception {
        var contract = contract("PUT", "WRITE", """
            {"type":"object","properties":{"order":{"type":"object","properties":{
               "lines":{"type":"array","minItems":1,"maxItems":2,"items":{"type":"object","properties":{
                 "sku":{"type":"string","minLength":1},"quantity":{"type":"integer","minimum":1,"maximum":5}},"required":["sku","quantity"]}}},"required":["lines"]}},"required":["order"]}
            """);
        Map<String,Object> valid = Map.of("order", Map.of("lines", List.of(Map.of("sku", "A", "quantity", 2))));
        assertNull(HttpApiConsolePolicy.unsupportedReason(contract));
        assertEquals(valid, HttpApiConsolePolicy.bind(contract, Map.of("id", "R1"), Map.of(), valid, json).jsonBody());
        for (var item : List.<Map<String,Object>>of(Map.of("sku", "A", "quantity", 0), Map.of("sku", "", "quantity", 1),
                Map.of("sku", "A", "quantity", 1.5), Map.of("quantity", 1), Map.of("sku", "A", "quantity", 1, "unknown", true))) {
            Map<String,Object> invalid = Map.of("order", Map.of("lines", List.of(item)));
            assertThrows(IllegalArgumentException.class, () -> HttpApiConsolePolicy.bind(contract, Map.of("id", "R1"), Map.of(), invalid, json));
        }
    }

    @Test void plainTextBodyIsADeclaredStringAndNotAQuotedJsonStringOnTheBusinessWire() throws Exception {
        var contract = contract("POST", "WRITE", "{\"type\":\"string\",\"maxLength\":100}");
        ((ObjectNode) contract.path("requestBody")).set("contentTypes", json.valueToTree(List.of("text/plain")));
        assertNull(HttpApiConsolePolicy.unsupportedReason(contract));
    }

    @Test void unsupportedSchemaConstraintsRemainExplicitInsteadOfBeingDiscarded() throws Exception {
        for (String keyword : List.of("pattern", "oneOf", "allOf", "$ref")) {
            var contract = contract("POST", "WRITE", "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}}}");
            ((ObjectNode) contract.at("/requestBody/schema/properties/value")).put(keyword, "unhandled");
            assertNotNull(HttpApiConsolePolicy.unsupportedReason(contract), keyword);
        }
    }
}
