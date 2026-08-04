package com.enterprise.ai.runtime.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RuntimeWorkflowSchemaResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void derivesInputSchemaFromPinnedPublishedGraphSpec() throws Exception {
        RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setInputSchemaJson("{\"type\":\"object\",\"properties\":{\"mutable\":{\"type\":\"boolean\"}}}");

        RuntimeWorkflowVersionEntity version = new RuntimeWorkflowVersionEntity();
        version.setSnapshotJson("{\"inputSchemaJson\":null}");
        version.setGraphSpecSnapshotJson("{\"schemaVersion\":2,\"inputSchema\":{\"type\":\"object\",\"properties\":{\"message\":{\"type\":\"string\"}}}}");

        JsonNode resolved = objectMapper.readTree(
                RuntimeWorkflowSchemaResolver.inputSchemaJson(objectMapper, workflow, version));

        assertEquals("string", resolved.at("/properties/message/type").asText());
        assertNull(resolved.at("/properties/mutable/type").textValue());
    }

    @Test
    void explicitPublishedSchemaWinsOverPublishedGraphSpec() throws Exception {
        RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
        RuntimeWorkflowVersionEntity version = new RuntimeWorkflowVersionEntity();
        version.setSnapshotJson("{\"inputSchemaJson\":\"{\\\"type\\\":\\\"object\\\",\\\"required\\\":[\\\"query\\\"]}\"}");
        version.setGraphSpecSnapshotJson("{\"inputSchema\":{\"type\":\"object\",\"required\":[\"message\"]}}");

        JsonNode resolved = objectMapper.readTree(
                RuntimeWorkflowSchemaResolver.inputSchemaJson(objectMapper, workflow, version));

        assertEquals("query", resolved.at("/required/0").asText());
    }

    @Test
    void derivesOutputSchemaFromWorkingGraphWhenNoVersionExists() throws Exception {
        RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setGraphSpecJson("{\"outputSchema\":{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}}}}");

        JsonNode resolved = objectMapper.readTree(
                RuntimeWorkflowSchemaResolver.outputSchemaJson(objectMapper, workflow, null));

        assertEquals("string", resolved.at("/properties/answer/type").asText());
    }
}
