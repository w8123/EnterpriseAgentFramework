package com.enterprise.ai.agent.service;

import com.enterprise.ai.agent.tools.ToolRegistry;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionService;
import com.enterprise.ai.skill.AiTool;
import com.enterprise.ai.skill.ToolParameter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LightweightToolCallerTest {

    @Test
    void buildsDescriptionsFromDatabaseWhitelist() {
        ToolRegistry registry = new ToolRegistry(List.of(new KnowledgeTool()));
        ToolDefinitionService definitionService = mock(ToolDefinitionService.class);
        when(definitionService.listLightweightEnabledToolNames()).thenReturn(List.of("search_knowledge"));

        LightweightToolCaller caller = new LightweightToolCaller(registry, definitionService);

        String descriptions = caller.buildToolDescriptions();

        assertTrue(descriptions.contains("search_knowledge"));
        assertFalse(descriptions.contains("call_business_api"));
    }

    @Test
    void executesOnlyToolsAllowedByMetadata() {
        ToolRegistry registry = new ToolRegistry(List.of(new KnowledgeTool()));
        ToolDefinitionService definitionService = mock(ToolDefinitionService.class);
        when(definitionService.isLightweightCallable("search_knowledge")).thenReturn(true);
        when(definitionService.isLightweightCallable("call_business_api")).thenReturn(false);

        LightweightToolCaller caller = new LightweightToolCaller(registry, definitionService);

        var executed = caller.parseAndExecute("[TOOL_CALL]{\"name\": \"search_knowledge\", \"args\": {\"query\": \"制度\"}}");
        var blocked = caller.parseAndExecute("[TOOL_CALL]{\"name\": \"call_business_api\", \"args\": {\"api_path\": \"/x\"}}");

        assertTrue(executed.isPresent());
        assertEquals("知识:制度", executed.get().result());
        assertTrue(blocked.isEmpty());
    }

    private static final class KnowledgeTool implements AiTool {
        @Override
        public String name() {
            return "search_knowledge";
        }

        @Override
        public String description() {
            return "知识检索";
        }

        @Override
        public Object execute(Map<String, Object> args) {
            return "知识:" + args.get("query");
        }

        @Override
        public List<ToolParameter> parameters() {
            return List.of(ToolParameter.required("query", "string", "问题"));
        }
    }
}
