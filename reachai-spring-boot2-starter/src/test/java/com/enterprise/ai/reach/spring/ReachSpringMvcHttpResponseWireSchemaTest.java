package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.capability.ReachHttpApiDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ReachSpringMvcHttpResponseWireSchemaTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void dtoEnvelopeMatchesSerializedWireRatherThanPayloadRoot() {
        Map<?, ?> schema = response("/dto");
        JsonNode wire = json.valueToTree(new WebApiResult<CountDto>(new CountDto()));
        assertEquals(true, wire.path("success").asBoolean());
        assertEquals(2, wire.path("data").path("count").asInt());
        assertEquals("object", schema.get("type"));
        assertEquals("boolean", property(schema, "success").get("type"));
        assertCount(property(schema, "data"));
        assertFalse(properties(schema).containsKey("count"));
    }

    @Test
    void mapEnvelopeRetainsMapValueGeneric() {
        Map<?, ?> data = property(response("/map"), "data");
        assertEquals("object", data.get("type"));
        assertCount((Map<?, ?>) data.get("additionalProperties"));
    }

    @Test
    void listEnvelopeRetainsElementGeneric() {
        Map<?, ?> data = property(response("/list"), "data");
        assertEquals("array", data.get("type"));
        assertCount((Map<?, ?>) data.get("items"));
    }

    @Test
    void nestedSameEnvelopeTypeIsNotMistakenForRecursiveReference() {
        Map<?, ?> nested = property(response("/nested"), "data");
        assertEquals("boolean", property(nested, "success").get("type"));
        assertCount(property(nested, "data"));
    }

    @Test
    void inheritsAndBindsGenericFieldsThroughSeveralDtoParents() {
        Map<?, ?> schema = response("/inherited");
        assertEquals("string", property(schema, "requestId").get("type"));
        assertEquals("boolean", property(schema, "success").get("type"));
        Map<?, ?> data = property(schema, "data");
        assertEquals("array", data.get("type"));
        assertCount((Map<?, ?>) data.get("items"));
    }

    @Test
    void unwrapsActualSpringTransportAndOptionalWithoutRemovingBusinessEnvelope() {
        Map<?, ?> schema = response("/transport");
        assertEquals("boolean", property(schema, "success").get("type"));
        assertCount(property(schema, "data"));
        assertFalse(properties(schema).containsKey("body"));
    }

    @Test
    void unknownObjectAndUnboundGenericAreUnconstrainedJsonInsteadOfFakeObjects() {
        assertEquals(Map.of(), response("/unknown"));
        assertEquals(Map.of(), property(response("/unknown-data"), "data"));
        assertEquals(Map.of(), property(response("/raw"), "data"));
    }

    private Map<?, ?> response(String route) {
        ReachHttpApiDescriptor descriptor = ReachSpringMvcEndpointScanner.scanClass(WireController.class).stream()
                .filter(item -> route.equals(item.getEndpointPath())).findFirst().orElseThrow();
        return descriptor.getResponses().get(0).getSchema();
    }

    private static Map<?, ?> properties(Map<?, ?> schema) { return (Map<?, ?>) schema.get("properties"); }
    private static Map<?, ?> property(Map<?, ?> schema, String name) { return (Map<?, ?>) properties(schema).get(name); }
    private static void assertCount(Map<?, ?> schema) {
        assertEquals("object", schema.get("type"));
        assertEquals("string", property(schema, "parentId").get("type"));
        assertEquals("integer", property(schema, "count").get("type"));
    }

    public static class CountDto { public String parentId = "DT0000000008"; public int count = 2; }
    public static class WebApiResult<T> {
        public boolean success = true;
        public T data;
        public WebApiResult(T data) { this.data = data; }
    }
    public static class Result<T> extends WebApiResult<List<T>> {
        public Result() { super(null); }
    }
    public static class CountResult extends Result<CountDto> { public String requestId; }

    @RestController
    static class WireController {
        @GetMapping(value = "/dto", produces = "application/json") WebApiResult<CountDto> dto() { return null; }
        @GetMapping(value = "/map", produces = "application/json") WebApiResult<Map<String, CountDto>> map() { return null; }
        @GetMapping(value = "/list", produces = "application/json") WebApiResult<List<CountDto>> list() { return null; }
        @GetMapping(value = "/nested", produces = "application/json") WebApiResult<WebApiResult<CountDto>> nested() { return null; }
        @GetMapping(value = "/inherited", produces = "application/json") CountResult inherited() { return null; }
        @GetMapping(value = "/transport", produces = "application/json") ResponseEntity<Optional<WebApiResult<CountDto>>> transport() { return null; }
        @GetMapping(value = "/unknown", produces = "application/json") Object unknown() { return null; }
        @GetMapping(value = "/unknown-data", produces = "application/json") WebApiResult<Object> unknownData() { return null; }
        @GetMapping(value = "/raw", produces = "application/json") WebApiResult raw() { return null; }
    }
}
