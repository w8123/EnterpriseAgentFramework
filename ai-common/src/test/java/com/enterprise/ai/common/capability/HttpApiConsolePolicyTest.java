package com.enterprise.ai.common.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HttpApiConsolePolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    private ObjectNode contract() throws Exception {
        return (ObjectNode) json.readTree("""
                {"identity":{"method":"POST","routeTemplate":"/orders/{id}/notes","mappingConditions":{"consumes":["application/json"]}},
                 "parameters":[{"name":"id","location":"PATH","required":true,"schema":{"type":"string"}}],
                 "requestBody":{"required":true,"contentTypes":["application/json"],"schema":{
                    "type":"object","required":["note"],"properties":{"note":{"type":"string","minLength":1,"maxLength":20},
                    "count":{"type":"integer","minimum":0,"maximum":5,"default":1},"urgent":{"type":"boolean"},
                    "state":{"type":"string","enum":["NEW"]},"fixed":{"type":"string","const":"one"}}}},
                 "responses":[{"status":"200","contentTypes":["application/json"],"schema":{"type":"object"}}],
                 "authentication":{"state":"NONE"},"sideEffect":"WRITE"}
                """);
    }

    @Test void consoleGrantCannotGrantWorkflowOrStudioPost() throws Exception {
        assertNull(HttpApiConsolePolicy.unsupportedReason(contract()));
        assertNotNull(HttpApiFirstCallPolicy.unsupportedReason(contract()));
        assertThrows(IllegalArgumentException.class, () -> HttpApiFirstCallPolicy.bind(contract(), Map.of("id", "1"), Map.of(), json));
        assertNull(HttpApiConsolePolicy.connectionAuthReason(contract(), "NONE", null, null));
        assertNotNull(HttpApiFirstCallPolicy.connectionAuthReason(contract(), "NONE", null, null));
    }

    @Test void preservesScalarConstraintsAndAbsenceWithoutApplyingSourceDefaults() throws Exception {
        var input = new LinkedHashMap<String, Object>(); input.put("note", "one"); input.put("urgent", false);
        var bound = HttpApiConsolePolicy.bind(contract(), Map.of("id", "A 1"), Map.of(), input, json);
        assertEquals("/orders/A%201/notes", bound.encodedRoute());
        assertEquals(Map.of("note", "one", "urgent", false), bound.jsonBody());
        for (Map<String, Object> invalid : java.util.List.<Map<String, Object>>of(Map.of("note", ""), Map.of("note", "one", "count", -1),
                Map.of("note", "one", "count", 1.5), Map.of("note", "one", "urgent", "false"),
                Map.of("note", "one", "state", "OLD"), Map.of("note", "one", "fixed", "two"),
                Map.of("note", "one", "undeclared", "value"), Map.of("note", Map.of("nested", "one")))) {
            assertThrows(IllegalArgumentException.class, () -> HttpApiConsolePolicy.bind(contract(), Map.of("id", "1"), Map.of(), invalid, json));
        }
        assertThrows(IllegalArgumentException.class, () -> HttpApiConsolePolicy.bind(contract(), Map.of("id", "1"), Map.of(), null, json));
        input.put("note", null);
        assertThrows(IllegalArgumentException.class, () -> HttpApiConsolePolicy.bind(contract(), Map.of("id", "1"), Map.of(), input, json));
        var optional = contract(); ((ObjectNode) optional.path("requestBody")).put("required", false);
        assertNull(HttpApiConsolePolicy.bind(optional, Map.of("id", "1"), Map.of(), null, json).jsonBody());
    }

    @Test void optionalNonNullableNullMustBeRejectedRatherThanRemovedFromConfirmedBody() throws Exception {
        var input = new LinkedHashMap<String, Object>(); input.put("note", "one"); input.put("count", null);
        assertThrows(IllegalArgumentException.class,
                () -> HttpApiConsolePolicy.bind(contract(), Map.of("id", "1"), Map.of(), input, json));
        assertTrue(input.containsKey("count")); assertNull(input.get("count"));
        var declaration = contract();
        ((ObjectNode) declaration.at("/requestBody/schema/properties")).putObject("caption").put("type", "string");
        assertEquals(Map.of("note", "one", "count", 0, "urgent", false, "caption", ""),
                HttpApiConsolePolicy.bind(declaration, Map.of("id", "1"), Map.of(),
                        Map.of("note", "one", "count", 0, "urgent", false, "caption", ""), json).jsonBody());
    }

    @Test void existingGetOptionalNullOmissionIsNotChangedByJsonWriteBinding() throws Exception {
        var declaration = json.readTree("""
                {"identity":{"method":"GET","routeTemplate":"/orders/{id}"},"sideEffect":"READ_ONLY",
                "parameters":[{"name":"id","location":"PATH","required":true,"schema":{"type":"string"}},
                {"name":"count","location":"QUERY","required":false,"schema":{"type":"integer"}}],
                "responses":[{"status":"200","contentTypes":["application/json"],"schema":{"type":"object"}}],
                "authentication":{"state":"NONE"}}
                """);
        var query = new LinkedHashMap<String, Object>(); query.put("count", null);
        var bound = HttpApiConsolePolicy.bind(declaration, Map.of("id", "1"), query, null, json);
        assertEquals("GET", bound.method()); assertTrue(bound.queryParams().isEmpty()); assertNull(bound.jsonBody());
    }

    @Test void unsupportedDeclarationsAreNotSilentlyDropped() throws Exception {
        for (String method : java.util.List.of("PUT", "PATCH", "DELETE")) {
            var contract = contract(); ((ObjectNode) contract.path("identity")).put("method", method);
            assertNotNull(HttpApiConsolePolicy.unsupportedReason(contract));
        }
        var contract = contract(); contract.put("sideEffect", "IRREVERSIBLE");
        assertNotNull(HttpApiConsolePolicy.unsupportedReason(contract));
        for (String key : java.util.List.of("pattern", "allOf", "items")) {
            contract = contract(); ((ObjectNode) contract.at("/requestBody/schema/properties/note")).put(key, "unsupported");
            assertNotNull(HttpApiConsolePolicy.unsupportedReason(contract));
        }
        contract = contract(); ((ObjectNode) contract.at("/requestBody/schema/properties/note")).put("type", "object");
        assertNotNull(HttpApiConsolePolicy.unsupportedReason(contract));
        contract = contract(); ((ObjectNode) contract.at("/requestBody/schema/properties")).putObject("nested.name").put("type", "string");
        assertNotNull(HttpApiRequestPolicy.unsupportedReason(contract), "body mapping has exactly one flat field segment");
    }
}
