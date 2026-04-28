package com.enterprise.ai.agent.tools.dynamic;

import com.enterprise.ai.agent.agentscope.adapter.AiToolAgentAdapter;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionParameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicHttpAiToolLlmSchemaTest {

    @Test
    void llmParametersJsonSchema_nestedBodyChildrenBecomeProperties() throws Exception {
        String parametersJson = """
                [
                  {
                    "name": "body_json",
                    "type": "object",
                    "description": "请求体",
                    "required": true,
                    "location": "BODY",
                    "children": [
                      {
                        "name": "teamName",
                        "type": "string",
                        "description": "班组名称",
                        "required": false,
                        "location": null
                      },
                      {
                        "name": "page",
                        "type": "integer",
                        "description": "页码",
                        "required": false,
                        "location": null
                      }
                    ]
                  }
                ]
                """;

        ToolDefinitionEntity entity = new ToolDefinitionEntity();
        entity.setName("page");
        entity.setBaseUrl("http://127.0.0.1");
        entity.setEndpointPath("/teams/page");
        entity.setHttpMethod("POST");
        entity.setParametersJson(parametersJson);

        DynamicHttpAiTool tool = new DynamicHttpAiTool(entity, new ObjectMapper());
        Map<String, Object> root = tool.llmParametersJsonSchema();

        assertEquals("object", root.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) root.get("properties");
        assertTrue(props.containsKey("body_json"));

        @SuppressWarnings("unchecked")
        Map<String, Object> bodyProp = (Map<String, Object>) props.get("body_json");
        assertEquals("object", bodyProp.get("type"));
        assertEquals("请求体", bodyProp.get("description"));

        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) bodyProp.get("properties");
        assertEquals(2, nested.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> teamName = (Map<String, Object>) nested.get("teamName");
        assertEquals("string", teamName.get("type"));
        assertEquals("班组名称", teamName.get("description"));

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) root.get("required");
        assertEquals(List.of("body_json"), required);

        @SuppressWarnings("unchecked")
        List<String> bodyRequired = (List<String>) bodyProp.get("required");
        assertTrue(bodyRequired == null || bodyRequired.isEmpty());
    }

    @Test
    void buildRootParametersSchema_arrayUsesFirstChildAsItems() {
        var body = new ToolDefinitionParameter(
                "ids",
                "array",
                "id 列表",
                true,
                "QUERY",
                List.of(new ToolDefinitionParameter(
                        "item",
                        "string",
                        "单个 id",
                        false,
                        null,
                        List.of()
                ))
        );
        Map<String, Object> root = DynamicHttpAiTool.buildRootParametersSchema(List.of(body));
        @SuppressWarnings("unchecked")
        Map<String, Object> idsProp = (Map<String, Object>) ((Map<String, Object>) root.get("properties")).get("ids");
        assertEquals("array", idsProp.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) idsProp.get("items");
        assertEquals("string", items.get("type"));
    }

    @Test
    void aiToolAgentAdapter_delegatesToLlmJsonSchemaProvider() {
        ToolDefinitionEntity entity = new ToolDefinitionEntity();
        entity.setName("t");
        entity.setBaseUrl("http://127.0.0.1");
        entity.setEndpointPath("/x");
        entity.setHttpMethod("POST");
        entity.setParametersJson("[{\"name\":\"body_json\",\"type\":\"object\",\"description\":\"b\",\"required\":true,\"location\":\"BODY\",\"children\":[{\"name\":\"k\",\"type\":\"string\",\"description\":\"key\",\"required\":true,\"location\":null}]}]");

        var adapter = new AiToolAgentAdapter(new DynamicHttpAiTool(entity, new ObjectMapper()));
        Map<String, Object> schema = adapter.getParameters();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ((Map<String, Object>) schema.get("properties")).get("body_json");
        @SuppressWarnings("unchecked")
        Map<String, Object> nestedProps = (Map<String, Object>) body.get("properties");
        assertTrue(nestedProps.containsKey("k"));
    }
}
