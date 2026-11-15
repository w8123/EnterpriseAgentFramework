package com.enterprise.ai.runtime.internal;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.common.capability.HttpApiConsoleContracts;
import com.enterprise.ai.common.capability.HttpApiRequestPolicy;
import com.enterprise.ai.runtime.execution.kernel.RuntimeHttpApiInputBindings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeHttpApiFlowTemplateTest {

    private static final String REF = "http-api:yudao-erp:dev:" + "a".repeat(64);

    private GraphSpec graph(String contract) throws Exception {
        var owner = new HttpApiConsoleContracts.ExecutionContext(HttpApiConsoleContracts.VERSION, 41L, REF,
                7L, "yudao-erp", "dev", "GET", "/erp/customer/{id}/orders", true, "ACCEPTED", null,
                "b".repeat(64), "b".repeat(64), "rev-1", new ObjectMapper().readTree(contract), true, null);
        return new RuntimeHttpApiFlowService(null, null, null).graphSpec(owner, "查询客户订单");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsPathQueryAndBodyFieldsToApiTargets() throws Exception {
        GraphSpec graph = graph("""
                {"parameters":[
                   {"name":"id","location":"PATH","required":true,"schema":{"type":"integer"}},
                   {"name":"pageNo","location":"QUERY","required":false,"schema":{"type":"integer","description":"页码"}},
                   {"name":"X-Tenant","location":"HEADER","required":false,"schema":{"type":"string"}},
                   {"name":"ids","location":"QUERY","required":false,"schema":{"type":"array","items":{"type":"integer"}}}],
                 "requestBody":{"required":true,"schema":{"type":"object","required":["name"],
                   "properties":{"name":{"type":"string"},"id":{"type":"integer"}}}}}
                """);
        GraphSpec.Node api = graph.getNodes().stream().filter(node -> "api".equals(node.getId())).findFirst().orElseThrow();
        Map<String, Object> mapping = (Map<String, Object>) api.getConfig().get("inputMapping");
        assertEquals("params.id", mapping.get("pathParams.id"));
        assertEquals("params.pageNo", mapping.get("queryParams.pageNo"));
        assertEquals("params.name", mapping.get("body.name"));
        // a body field that collides with a path parameter gets its own input name
        assertEquals("params.body_id", mapping.get("body.id"));
        assertEquals("params.ids", mapping.get("queryParams.ids"));
        assertEquals(Map.of("type", "integer"), ((Map<?, ?>) ((Map<?, ?>) graph.getInputSchema()
                .get("properties")).get("ids")).get("items"));
        assertEquals(5, mapping.size());
        assertEquals(41L, api.getConfig().get("httpApiAssetId"));
        assertEquals(REF, api.getRef().getQualifiedName());
        assertEquals(List.of("id", "name"), graph.getInputSchema().get("required"));
        assertTrue(graph.getNodes().stream().noneMatch(node -> "LLM".equals(node.getType())));
    }

    @Test
    void preservesNestedAndOptionalWholeBodySchemas() throws Exception {
        GraphSpec graph = graph("""
                {"parameters":[],"requestBody":{"required":false,"schema":{"type":"object",
                  "required":["items"],"properties":{"items":{"type":"array","items":{"type":"integer"}}}}}}
                """);
        Map<?, ?> properties = (Map<?, ?>) graph.getInputSchema().get("properties");
        Map<?, ?> body = (Map<?, ?>) properties.get("body");
        assertEquals(List.of("items"), body.get("required"));
        assertFalse(graph.getInputSchema().containsKey("required"));
        Map<?, ?> mapping = (Map<?, ?>) graph.getNodes().get(1).getConfig().get("inputMapping");
        assertEquals("params.body", mapping.get("body"));
    }

    @Test
    void longProjectSlugsKeepTheApiIdentity() {
        String code = "long-project-".repeat(10);
        String first = RuntimeHttpApiFlowService.keySlug(code, 41);
        String second = RuntimeHttpApiFlowService.keySlug(code, 42);
        assertTrue(first.length() <= 64);
        assertTrue(first.endsWith("-41"));
        assertFalse(first.equals(second));
    }

    @Test
    void generatedMappingReachesRealRequestBinderWithArrayAndNestedBody() throws Exception {
        String contract = """
                {"identity":{"method":"POST","routeTemplate":"/orders","mappingConditions":{}},
                 "sideEffect":"WRITE", "authentication":{"state":"NONE"},
                 "responses":[{"contentTypes":["application/json"],"schema":{"type":"object"}}],
                 "parameters":[{"name":"ids","location":"QUERY","required":true,
                   "schema":{"type":"array","items":{"type":"integer"}}}],
                 "requestBody":{"required":true,"contentTypes":["application/json"],"schema":{"type":"object",
                   "required":["details"],"properties":{"details":{"type":"object","required":["items"],
                   "properties":{"items":{"type":"array","items":{"type":"object","required":["sku"],
                   "properties":{"sku":{"type":"string"}}}}}}}}}}
                """;
        GraphSpec graph = graph(contract);
        GraphSpec.Node api = graph.getNodes().get(1);
        Map<String, Object> details = Map.of("items", List.of(Map.of("sku", "A"), Map.of("sku", "B")));
        Map<String, Object> input = RuntimeHttpApiInputBindings.resolve(api,
                Map.of("params", Map.of("ids", List.of(1, 2), "details", details)));
        assertEquals(List.of(1, 2), input.get("queryParams.ids"));
        assertEquals(details, input.get("body.details"));
        var bound = HttpApiRequestPolicy.bind(new ObjectMapper().readTree(contract), Map.of(),
                Map.of("ids", input.get("queryParams.ids")), Map.of("details", input.get("body.details")),
                new ObjectMapper());
        assertEquals("1,2", bound.queryParams().get("ids"));
        assertEquals(Map.of("details", details), bound.jsonBody());
    }

    @Test
    void bodyWithAdditionalFieldsUsesWholeBodyAndUntypedBodyIsRejected() throws Exception {
        GraphSpec graph = graph("""
                {"parameters":[],"requestBody":{"required":true,"schema":{"type":"object",
                "additionalProperties":true,"properties":{"name":{"type":"string"}}}}}
                """);
        Map<?, ?> mapping = (Map<?, ?>) graph.getNodes().get(1).getConfig().get("inputMapping");
        assertEquals("params.body", mapping.get("body"));
        assertThrows(IllegalArgumentException.class,
                () -> graph("{\"parameters\":[],\"requestBody\":{\"schema\":{}}}"));
    }

    @Test
    void apiWithoutParametersStillBuildsACallableFlow() throws Exception {
        GraphSpec graph = graph("{\"parameters\":[]}");
        assertEquals("user_input", graph.getEntryNodeId());
        assertEquals(List.of("answer"), graph.getExitNodeIds());
        assertFalse(graph.getInputSchema().containsKey("required"));
        assertEquals("api-yudao-erp-41", RuntimeHttpApiFlowService.keySlug("yudao-erp", 41));
    }

    @Test
    void pagingParametersGetUsableDefaults() {
        assertEquals("；用户没有提到就填 1", RuntimeHttpApiFlowService.optionalHint("pageNumber", "integer", false));
        assertEquals("；用户没有提到就填 10", RuntimeHttpApiFlowService.optionalHint("pageSize", "integer", false));
        assertEquals("；用户没有提到就不要填", RuntimeHttpApiFlowService.optionalHint("keyword", "string", false));
        assertEquals("", RuntimeHttpApiFlowService.optionalHint("orderId", "integer", true));
    }
}
