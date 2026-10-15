package com.enterprise.ai.common.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HttpApiJsonBindingTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void validatesRootArraysScalarsNullableAndTypedAdditionalProperties() throws Exception {
        var schema = json.readTree("""
            {"type":"array","minItems":1,"maxItems":3,"uniqueItems":true,"items":{"type":"object",
             "properties":{"name":{"type":"string","nullable":true}},"additionalProperties":{"type":"integer","minimum":0}}}
            """);
        Map<String,Object> map = new LinkedHashMap<>(); map.put("name", null); map.put("count", 1);
        assertEquals(List.of(map), HttpApiJsonBinding.bind(schema, List.of(map), json));
        assertThrows(IllegalArgumentException.class, () -> HttpApiJsonBinding.bind(schema, List.of(map, map), json));
        assertThrows(IllegalArgumentException.class, () -> HttpApiJsonBinding.bind(schema, List.of(Map.of("count", -1)), json));
        assertThrows(IllegalArgumentException.class, () -> HttpApiJsonBinding.bind(schema, List.of(Map.of("count", "1")), json));
        assertEquals(false, HttpApiJsonBinding.bind(json.readTree("{\"type\":\"boolean\"}"), false, json));
        assertEquals(0, HttpApiJsonBinding.bind(json.readTree("{\"type\":\"integer\"}"), 0, json));
        assertEquals("line\nnext", HttpApiJsonBinding.bind(json.readTree("{\"type\":\"string\"}"), "line\nnext", json));
    }

    @Test void enforcesSizeDepthNodesAndCollectionBoundsWithoutMutatingInput() {
        Object deep = "value"; for (int i=0;i<7;i++) deep = List.of(deep);
        Object tooDeep = deep;
        assertThrows(IllegalArgumentException.class, () -> HttpApiJsonBinding.copyWire(tooDeep, json));
        assertThrows(IllegalArgumentException.class, () -> HttpApiJsonBinding.copyWire(Collections.nCopies(1025, "item"), json));
        assertThrows(IllegalArgumentException.class, () -> HttpApiJsonBinding.copyWire(Collections.nCopies(8, "a".repeat(32768)), json));
        Map<String,Object> broad = new LinkedHashMap<>(); for (int i=0;i<65;i++) broad.put("key"+i, true);
        assertThrows(IllegalArgumentException.class, () -> HttpApiJsonBinding.copyWire(broad, json));
        assertThrows(IllegalArgumentException.class, () -> HttpApiJsonBinding.copyWire(List.of(Double.NaN), json));
        assertThrows(IllegalArgumentException.class, () -> HttpApiJsonBinding.copyWire(new Object(), json));
        List<Object> original = new ArrayList<>(List.of(Map.of("z", 1, "a", 2)));
        Object copy = HttpApiJsonBinding.copyWire(original, json); original.clear();
        assertEquals(List.of(Map.of("a", 2, "z", 1)), copy);
    }

    @Test void allHandledSchemaConstraintsAreEnforcedAndUnrecognizedKeywordsAreBlocked() throws Exception {
        var numeric = json.readTree("{\"type\":\"number\",\"exclusiveMinimum\":0,\"exclusiveMaximum\":10,\"multipleOf\":2}");
        assertEquals(2, HttpApiJsonBinding.bind(numeric, 2, json));
        for (Object invalid : List.of(0, 3, 10, "2")) assertThrows(IllegalArgumentException.class, () -> HttpApiJsonBinding.bind(numeric, invalid, json));
        var schema = json.readTree("{\"type\":\"object\",\"const\":{\"ok\":true},\"properties\":{\"ok\":{\"type\":\"boolean\"}}}");
        assertEquals(Map.of("ok", true), HttpApiJsonBinding.bind(schema, Map.of("ok", true), json));
        assertThrows(IllegalArgumentException.class, () -> HttpApiJsonBinding.bind(schema, Map.of("ok", false), json));
        assertNotNull(HttpApiJsonBinding.unsupportedReason(json.readTree("{\"type\":\"string\",\"pattern\":\"unbounded\"}")));
        assertNotNull(HttpApiJsonBinding.unsupportedReason(json.readTree("{\"type\":\"string\",\"format\":\"binary\"}")));
    }
}
