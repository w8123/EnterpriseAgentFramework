package com.enterprise.ai.text.tooling.scanner.controller;

import com.enterprise.ai.text.tooling.scanner.manifest.HttpApiOperation;
import com.enterprise.ai.text.tooling.scanner.manifest.ProjectMetadata;
import com.enterprise.ai.text.tooling.scanner.manifest.ToolManifest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControllerHttpApiResponseWireSchemaTest {
    @TempDir Path root;
    private ToolManifest inventory;

    @BeforeEach
    void scanActualSpringSourceWithGenericEnvelopeDeclarations() throws Exception {
        Files.writeString(root.resolve("WireController.java"), """
                package demo;
                import org.springframework.web.bind.annotation.*;
                import org.springframework.http.ResponseEntity;
                import java.util.*;
                @RestController
                class WireController {
                    @GetMapping(value="/dto", produces="application/json") WebApiResult<CountDto> dto() { return null; }
                    @GetMapping(value="/map", produces="application/json") WebApiResult<Map<String,CountDto>> map() { return null; }
                    @GetMapping(value="/list", produces="application/json") WebApiResult<List<CountDto>> list() { return null; }
                    @GetMapping(value="/nested", produces="application/json") WebApiResult<WebApiResult<CountDto>> nested() { return null; }
                    @GetMapping(value="/inherited", produces="application/json") CountResult inherited() { return null; }
                    @GetMapping(value="/transport", produces="application/json") ResponseEntity<Optional<WebApiResult<CountDto>>> transport() { return null; }
                    @GetMapping(value="/unknown", produces="application/json") Object unknown() { return null; }
                    @GetMapping(value="/unknown-data", produces="application/json") WebApiResult<Object> unknownData() { return null; }
                    @GetMapping(value="/raw", produces="application/json") WebApiResult raw() { return null; }
                }
                class CountDto { String parentId; int count; }
                class WebApiResult<T> { boolean success; T data; }
                class Result<T> extends WebApiResult<List<T>> { }
                class CountResult extends Result<CountDto> { String requestId; }
                """, StandardCharsets.UTF_8);
        inventory = new ControllerAnnotationToolManifestScanner().scan(root,
                new ProjectMetadata("wire-schema", "http://localhost:9002", "/api"));
        assertTrue(inventory.httpApiInventoryComplete());
        assertEquals(9, inventory.httpApis().size());
    }

    @Test
    void dtoEnvelopeRetainsSuccessAndDataPathsMatchingJsonWire() {
        JsonNode schema = response("/dto");
        assertEquals("object", schema.path("type").asText());
        assertEquals("boolean", property(schema, "success").path("type").asText());
        assertCount(property(schema, "data"));
        assertFalse(schema.path("properties").has("count"));
    }

    @Test
    void bindsGenericMapValueInsideEnvelope() {
        JsonNode data = property(response("/map"), "data");
        assertEquals("object", data.path("type").asText());
        assertCount(data.path("additionalProperties"));
    }

    @Test
    void bindsGenericListElementInsideEnvelope() {
        JsonNode data = property(response("/list"), "data");
        assertEquals("array", data.path("type").asText());
        assertCount(data.path("items"));
    }

    @Test
    void preservesNestedEnvelopeRatherThanTreatingSameRawClassAsRecursive() {
        JsonNode nested = property(response("/nested"), "data");
        assertEquals("boolean", property(nested, "success").path("type").asText());
        assertCount(property(nested, "data"));
    }

    @Test
    void inheritedFieldsResolveTheirDeclaringTypeParameter() {
        JsonNode schema = response("/inherited");
        assertEquals("string", property(schema, "requestId").path("type").asText());
        assertEquals("boolean", property(schema, "success").path("type").asText());
        JsonNode data = property(schema, "data");
        assertEquals("array", data.path("type").asText());
        assertCount(data.path("items"));
    }

    @Test
    void removesOnlyTransportAndOptionalLayers() {
        JsonNode schema = response("/transport");
        assertEquals("boolean", property(schema, "success").path("type").asText());
        assertCount(property(schema, "data"));
        assertFalse(schema.path("properties").has("body"));
    }

    @Test
    void objectAndUnboundGenericRemainUnconstrainedJson() {
        assertEquals(0, response("/unknown").size());
        assertEquals(0, property(response("/unknown-data"), "data").size());
        assertEquals(0, property(response("/raw"), "data").size());
    }

    private JsonNode response(String route) {
        HttpApiOperation operation = inventory.httpApis().stream().filter(item -> route.equals(item.endpointPath()))
                .findFirst().orElseThrow();
        return operation.responses().get(0).schema();
    }
    private static JsonNode property(JsonNode schema, String name) { return schema.path("properties").path(name); }
    private static void assertCount(JsonNode schema) {
        assertEquals("object", schema.path("type").asText());
        assertEquals("string", property(schema, "parentId").path("type").asText());
        assertEquals("integer", property(schema, "count").path("type").asText());
    }
}
