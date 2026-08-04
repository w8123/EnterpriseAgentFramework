package com.enterprise.ai.runtime.workflow.proposal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeWorkflowProposalRequestContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void generationRequestUsesCanonicalWorkflowFields() throws Exception {
        RuntimeWorkflowProposalGenerationRequest request = objectMapper.readValue("""
                {
                  "workflowId": "workflow-1",
                  "workflowName": "Page Assistant",
                  "workflowKind": "PAGE_ASSISTANT",
                  "requirement": "build a page assistant"
                }
                """, RuntimeWorkflowProposalGenerationRequest.class);

        assertEquals("workflow-1", request.workflowId());
        assertEquals("Page Assistant", request.workflowName());
        assertEquals("PAGE_ASSISTANT", request.workflowKind());
    }

    @Test
    void canonicalSerializationDoesNotReintroduceAgentOrDraftFields() throws Exception {
        RuntimeWorkflowProposalGenerationRequest request = new RuntimeWorkflowProposalGenerationRequest(
                "workflow-1", "Order Workflow", "build order flow", "orders", "model-1", "GENERAL",
                null, null, null, null);

        JsonNode json = objectMapper.valueToTree(request);

        assertEquals("workflow-1", json.path("workflowId").asText());
        assertEquals("Order Workflow", json.path("workflowName").asText());
        assertEquals("GENERAL", json.path("workflowKind").asText());
        assertTrue(json.has("requirement"));
        assertFalse(json.has("agentId"));
        assertFalse(json.has("agentName"));
        assertFalse(json.has("draftScenario"));
        assertFalse(json.has("currentCanvas"));
    }

    @Test
    void rejectsRemovedAgentEraIdentityAliases() {
        assertThrows(Exception.class, () -> objectMapper.readValue("""
                {"agentId":"workflow-legacy","requirement":"build a workflow"}
                """, RuntimeWorkflowProposalGenerationRequest.class));
        assertThrows(Exception.class, () -> objectMapper.readValue("""
                {"requirement":"build a workflow","currentCanvas":{"version":2}}
                """, RuntimeWorkflowProposalGenerationRequest.class));
        assertThrows(Exception.class, () -> objectMapper.readValue("""
                {"agentName":"Legacy Workflow","instruction":"add an answer node"}
                """, RuntimeWorkflowProposalEditRequest.class));
    }

    @Test
    void editRequestUsesCanonicalWorkflowIdentity() throws Exception {
        RuntimeWorkflowProposalEditRequest request = objectMapper.readValue("""
                {
                  "workflowId": "workflow-1",
                  "workflowName": "Order Workflow",
                  "instruction": "add an answer node",
                  "currentGraphSpec": {
                    "schemaVersion": 2,
                    "nodes": [],
                    "edges": [],
                    "entryNodeId": "",
                    "exitNodeIds": []
                  }
                }
                """, RuntimeWorkflowProposalEditRequest.class);

        assertEquals("workflow-1", request.workflowId());
        assertEquals("Order Workflow", request.workflowName());
    }
}
