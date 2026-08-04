package com.enterprise.ai.agent.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphSpecContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesRuntimeSemanticsWithoutWorkflowIdentityOrCanvasLayout() throws Exception {
        GraphSpec graphSpec = GraphSpec.builder()
                .node(GraphSpec.Node.builder()
                        .id("answer")
                        .type("ANSWER")
                        .build())
                .edge(GraphSpec.Edge.builder()
                        .id("answer-to-next")
                        .from("answer")
                        .to("next")
                        .build())
                .entryNodeId("answer")
                .exitNodeIds(List.of("answer"))
                .build();

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(graphSpec));

        assertEquals(2, json.path("schemaVersion").asInt());
        assertEquals("answer", json.path("entryNodeId").asText());
        assertEquals(List.of("answer"), objectMapper.convertValue(
                json.path("exitNodeIds"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertFalse(json.has("entry"));
        assertFalse(json.has("finish"));
        assertFalse(json.has("code"));
        assertFalse(json.has("name"));
        assertFalse(json.has("mode"));
        assertFalse(json.has("runtimeHint"));
        assertFalse(json.has("layout"));
        assertFalse(json.path("nodes").get(0).has("layout"));
        assertFalse(json.path("edges").get(0).has("layout"));
    }

    @Test
    void readsCanonicalGraphSpecFields() throws Exception {
        GraphSpec graphSpec = objectMapper.readValue("""
                {
                  "schemaVersion": 2,
                  "entryNodeId": "answer",
                  "exitNodeIds": ["answer"],
                  "nodes": [
                    {"id": "answer", "type": "ANSWER"}
                  ],
                  "edges": []
                }
                """, GraphSpec.class);

        assertEquals("answer", graphSpec.getEntryNodeId());
        assertEquals(List.of("answer"), graphSpec.getExitNodeIds());

        JsonNode rewritten = objectMapper.readTree(objectMapper.writeValueAsString(graphSpec));
        assertTrue(rewritten.has("entryNodeId"));
        assertTrue(rewritten.has("exitNodeIds"));
        assertFalse(rewritten.has("entry"));
        assertFalse(rewritten.has("finish"));
        assertTrue(rewritten.has("schemaVersion"));
    }

    @Test
    void rejectsUnknownGraphSpecFields() {
        assertThrows(Exception.class, () -> objectMapper.readValue("""
                {"schemaVersion":2,"entryNodeId":"answer","exitNodeIds":["answer"],
                 "nodes":[{"id":"answer","type":"ANSWER","layout":{"x":1}}],"edges":[]}
                """, GraphSpec.class));
    }
}
