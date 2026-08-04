package com.enterprise.ai.runtime.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeWorkflowDocumentCanonicalizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuntimeWorkflowDocumentCanonicalizer canonicalizer =
            new RuntimeWorkflowDocumentCanonicalizer(objectMapper);

    @Test
    void preservesCanonicalGraphSpecRuntimeSemantics() throws Exception {
        String canonicalJson = canonicalizer.canonicalizeGraphSpecJson("""
                {
                  "schemaVersion": 2,
                  "entryNodeId": "lookup",
                  "exitNodeIds": ["answer"],
                  "nodes": [
                    {
                      "id": "lookup",
                      "type": "TOOL",
                      "config": {
                        "qualifiedName": "orders.lookup"
                      }
                    },
                    {"id": "answer", "type": "ANSWER"}
                  ],
                  "edges": [
                    {"id": "lookup-answer", "from": "lookup", "to": "answer", "condition": "always"}
                  ]
                }
                """);

        JsonNode graph = objectMapper.readTree(canonicalJson);
        assertEquals(2, graph.path("schemaVersion").asInt());
        assertEquals("lookup", graph.path("entryNodeId").asText());
        assertEquals("answer", graph.path("exitNodeIds").get(0).asText());
        assertEquals(2, graph.path("nodes").size());
        assertEquals(1, graph.path("edges").size());
        assertEquals("lookup-answer", graph.path("edges").get(0).path("id").asText());
        assertFalse(graph.has("entry"));
        assertFalse(graph.has("finish"));
        assertFalse(graph.has("code"));
        assertFalse(graph.has("name"));
        assertFalse(graph.has("mode"));
        assertFalse(graph.has("runtimeHint"));
        assertFalse(graph.has("layout"));
        JsonNode lookupConfig = graph.path("nodes").get(0).path("config");
        assertEquals("orders.lookup", lookupConfig.path("qualifiedName").asText());
    }

    @Test
    void preservesCanonicalCanvasLayoutDocument() throws Exception {
        String canonicalJson = canonicalizer.canonicalizeCanvasJson("""
                {
                  "schemaVersion": 1,
                  "layoutVersion": 1,
                  "viewport": {"x": 10, "y": 20, "zoom": 0.8},
                  "nodes": [
                    {
                      "id": "lookup",
                      "position": {"x": 100, "y": 200},
                      "collapsed": true
                    },
                    {
                      "id": "answer",
                      "position": {"x": 420, "y": 200},
                      "width": 260,
                      "height": 160,
                      "collapsed": false
                    }
                  ],
                  "edges": [
                    {
                      "id": "lookup-answer",
                      "label": "Next",
                      "style": "smoothstep"
                    }
                  ]
                }
                """);

        JsonNode canvas = objectMapper.readTree(canonicalJson);
        assertEquals(1, canvas.path("schemaVersion").asInt());
        assertEquals(1, canvas.path("layoutVersion").asInt());
        assertEquals(2, canvas.path("nodes").size());
        assertEquals(100, canvas.path("nodes").get(0).path("position").path("x").asInt());
        assertTrue(canvas.path("nodes").get(0).path("collapsed").asBoolean());
        assertEquals(420, canvas.path("nodes").get(1).path("position").path("x").asInt());
        assertEquals(260, canvas.path("nodes").get(1).path("width").asInt());
        assertEquals("Next", canvas.path("edges").get(0).path("label").asText());
        assertEquals("smoothstep", canvas.path("edges").get(0).path("style").asText());
        assertFalse(canvas.path("nodes").get(0).has("data"));
        assertFalse(canvas.path("nodes").get(0).has("type"));
        assertFalse(canvas.path("edges").get(0).has("source"));
        assertFalse(canvas.path("edges").get(0).has("target"));
        assertFalse(canvas.path("edges").get(0).has("condition"));
    }

    @Test
    void rejectsRemovedGraphAndCanvasFields() {
        assertThrows(IllegalArgumentException.class, () -> canonicalizer.canonicalizeGraphSpecJson("""
                {"schemaVersion":2,"entry":"answer","entryNodeId":"answer","exitNodeIds":["answer"],
                 "nodes":[{"id":"answer","type":"ANSWER"}],"edges":[]}
                """));
        assertThrows(IllegalArgumentException.class, () -> canonicalizer.canonicalizeCanvasJson("""
                {"version":2,"nodes":[],"edges":[]}
                """));
    }

    @Test
    void rejectsUnknownFieldsAndNodeTypeAliases() {
        assertThrows(IllegalArgumentException.class, () -> canonicalizer.canonicalizeGraphSpecJson("""
                {"schemaVersion":2,"entryNodeId":"answer","exitNodeIds":["answer"],
                 "nodes":[{"id":"answer","type":"reply"}],"edges":[]}
                """));
        assertThrows(IllegalArgumentException.class, () -> canonicalizer.canonicalizeGraphSpecJson("""
                {"schemaVersion":2,"entryNodeId":"answer","exitNodeIds":["answer"],
                 "nodes":[{"id":"answer","type":"ANSWER","legacyField":true}],"edges":[]}
                """));
        assertThrows(IllegalArgumentException.class, () -> canonicalizer.canonicalizeCanvasJson("""
                {"schemaVersion":1,"layoutVersion":1,"nodes":[],"edges":[],"graphCode":"legacy"}
                """));
    }

    @Test
    void rejectsJsonNullDocuments() {
        assertThrows(IllegalArgumentException.class, () -> canonicalizer.canonicalizeGraphSpecJson("null"));
        assertThrows(IllegalArgumentException.class, () -> canonicalizer.canonicalizeCanvasJson("null"));
    }
}
