package com.enterprise.ai.control.mcp.application.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class McpDottedArgumentShapeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode stockSchema() throws Exception {
        return mapper.readTree("""
                {"type":"object","required":["createReqVO.name"],"properties":{
                  "pageReqVO":{"type":"object"},
                  "pageReqVO.productId":{"type":"number","description":"产品编号"},
                  "createReqVO":{"type":"object"},
                  "createReqVO.name":{"type":"string"},
                  "keyword":{"type":"string"},
                  "loose.key":{"type":"string"}}}
                """);
    }

    @Test
    void flatKeysAreAdvertisedUnderTheirObjectProperty() throws Exception {
        JsonNode original = stockSchema();
        JsonNode nested = McpDottedArgumentShape.nest(original);

        assertFalse(nested.get("properties").has("pageReqVO.productId"));
        assertEquals("产品编号",
                nested.at("/properties/pageReqVO/properties/productId/description").asText());
        assertEquals(0, nested.get("required").size());
        assertEquals("name", nested.at("/properties/createReqVO/required/0").asText());
        // A dotted key without a declared object parent is left alone; the frozen schema is not mutated.
        assertEquals("string", nested.at("/properties/loose.key/type").asText());
        assertEquals("number", original.at("/properties/pageReqVO.productId/type").asText());
    }

    @Test
    void flatArgumentsAreFoldedIntoTheObjectBeforeExecution() throws Exception {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("pageReqVO.productId", 1);
        arguments.put("pageReqVO", Map.of("pageNo", 2));
        arguments.put("keyword", "鼠标");

        Map<String, Object> shaped = McpDottedArgumentShape.unflatten(arguments, stockSchema());

        assertEquals(Map.of("pageNo", 2, "productId", 1), shaped.get("pageReqVO"));
        assertEquals("鼠标", shaped.get("keyword"));
        assertEquals(List.of("pageReqVO", "keyword"), List.copyOf(shaped.keySet()));
    }

    @Test
    void nestedArgumentsPassThroughUnchanged() throws Exception {
        Map<String, Object> arguments = Map.of("pageReqVO", Map.of("productId", 1));
        assertSame(arguments, McpDottedArgumentShape.unflatten(arguments, stockSchema()));
    }
}
