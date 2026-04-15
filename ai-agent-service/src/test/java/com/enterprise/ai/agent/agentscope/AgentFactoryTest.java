package com.enterprise.ai.agent.agentscope;

import com.enterprise.ai.agent.agent.AgentDefinition;
import com.enterprise.ai.agent.config.LLMConfig;
import com.enterprise.ai.agent.tools.ToolRegistry;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionService;
import com.enterprise.ai.skill.AiTool;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentFactoryTest {

    @Test
    void createsToolkitOnlyForAgentVisibleEnabledTools() {
        ToolRegistry registry = new ToolRegistry(List.of(new DemoTool("query_database"), new DemoTool("search_knowledge")));
        ToolDefinitionService definitionService = mock(ToolDefinitionService.class);
        when(definitionService.isAgentCallable("query_database")).thenReturn(true);
        when(definitionService.isAgentCallable("search_knowledge")).thenReturn(false);

        LLMConfig llmConfig = new LLMConfig();
        llmConfig.setMaxSteps(5);

        AgentFactory factory = new AgentFactory(mock(Model.class), mock(Model.class), registry, definitionService, llmConfig);

        Toolkit toolkit = factory.createToolkit(List.of("query_database", "search_knowledge"));

        assertEquals(1, toolkit.getToolNames().size());
        assertTrue(toolkit.getToolNames().contains("query_database"));
    }

    @Test
    void usesDefinitionToolsToDecideWhetherToolkitIsAttached() {
        ToolRegistry registry = new ToolRegistry(List.of());
        ToolDefinitionService definitionService = mock(ToolDefinitionService.class);
        LLMConfig llmConfig = new LLMConfig();
        llmConfig.setMaxSteps(5);

        AgentFactory factory = new AgentFactory(mock(Model.class), mock(Model.class), registry, definitionService, llmConfig);
        AgentDefinition definition = AgentDefinition.builder()
                .name("chat")
                .systemPrompt("hello")
                .tools(List.of())
                .build();

        var agent = factory.buildFromDefinition(definition);

        assertTrue(agent != null);
    }

    private record DemoTool(String toolName) implements AiTool {
        @Override
        public String name() {
            return toolName;
        }

        @Override
        public String description() {
            return "desc";
        }

        @Override
        public Object execute(Map<String, Object> args) {
            return "ok";
        }
    }
}
