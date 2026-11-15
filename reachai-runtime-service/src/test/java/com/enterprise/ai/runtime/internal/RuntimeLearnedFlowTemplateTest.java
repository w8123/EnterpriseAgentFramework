package com.enterprise.ai.runtime.internal;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationResult;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RuntimeLearnedFlowTemplateTest {

    private static final String PARAMETERS = """
            [{"name":"pageReqVO","type":"object","required":false},
             {"name":"pageReqVO.customerId","type":"number","description":"客户编号"},
             {"name":"pageReqVO.no","type":"string","description":"销售单编号"},
             {"name":"pageReqVO.secret","type":"string","metadata":{"sensitive":true}}]
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private GraphSpec graph() {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("name", "yudao_erp_erpSaleOrderPage");
        definition.put("projectCode", "yudao-erp");
        definition.put("parametersJson", PARAMETERS);
        return new RuntimeLearnedFlowService(null, null, null, null, null, null, null, objectMapper)
                .graphSpec(definition, "yudao-erp:erpSaleOrderPage", "查询销售订单", "model-1");
    }

    @Test
    void templatePassesReleaseValidation() throws Exception {
        RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId("wf-flow");
        workflow.setProjectCode("yudao-erp");
        workflow.setKeySlug(RuntimeLearnedFlowService.keySlug("yudao-erp:erpSaleOrderPage"));
        workflow.setName("直达流程");
        workflow.setWorkflowKind("GENERAL");
        workflow.setExecutionEngine("GRAPH_SPEC");
        workflow.setGraphSpecJson(objectMapper.writeValueAsString(graph()));

        RuntimeWorkflowReleaseValidationResult result = new RuntimeWorkflowReleaseValidationService(
                mock(RuntimeControlCatalogClient.class), objectMapper).validate(workflow);

        assertTrue(result.valid(), () -> String.valueOf(result.errors()));
        assertEquals("flow-yudao-erp-erpsaleorderpage", workflow.getKeySlug());
    }

    @Test
    void mapsLeafParametersAndDeclaresDirectAnswer() throws Exception {
        JsonNode graph = objectMapper.readTree(objectMapper.writeValueAsString(graph()));
        JsonNode properties = graph.path("inputSchema").path("properties");
        assertTrue(properties.has("question"));
        assertTrue(properties.has("customerId"));
        assertTrue(properties.has("no"));
        assertFalse(properties.has("secret"));
        assertFalse(properties.has("pageReqVO"));
        JsonNode nodes = graph.path("nodes");
        assertEquals("params.customerId", nodes.get(1).path("config").path("inputMapping").path("customerId").asText());
        assertEquals("yudao-erp:erpSaleOrderPage", nodes.get(1).path("ref").path("qualifiedName").asText());
        assertTrue(nodes.get(3).path("config").path("directAnswer").asBoolean());
    }
}
