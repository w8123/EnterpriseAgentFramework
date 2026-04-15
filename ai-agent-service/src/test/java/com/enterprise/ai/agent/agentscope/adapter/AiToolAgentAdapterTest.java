package com.enterprise.ai.agent.agentscope.adapter;

import com.enterprise.ai.skill.AiTool;
import com.enterprise.ai.skill.ToolParameter;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiToolAgentAdapterTest {

    @Test
    void convertsToolParametersToJsonSchemaAndExecutesTool() {
        AiTool tool = new DemoTool();
        AiToolAgentAdapter adapter = new AiToolAgentAdapter(tool);
        ToolCallParam param = mock(ToolCallParam.class);
        when(param.getInput()).thenReturn(Map.of("keyword", "合同"));

        Map<String, Object> schema = adapter.getParameters();
        var resultBlock = adapter.callAsync(param).block();

        assertEquals("demo_tool", adapter.getName());
        assertEquals("演示工具", adapter.getDescription());
        assertEquals("object", schema.get("type"));
        assertTrue(((Map<?, ?>) schema.get("properties")).containsKey("keyword"));
        assertEquals(List.of("keyword"), schema.get("required"));
        assertEquals("合同", ((TextBlock) resultBlock.getOutput().get(0)).getText());
    }

    @Test
    void returnsErrorBlockWhenToolExecutionFails() {
        AiTool tool = new AiTool() {
            @Override
            public String name() {
                return "broken_tool";
            }

            @Override
            public String description() {
                return "总是失败";
            }

            @Override
            public Object execute(Map<String, Object> args) {
                throw new IllegalStateException("boom");
            }
        };

        AiToolAgentAdapter adapter = new AiToolAgentAdapter(tool);
        ToolCallParam param = mock(ToolCallParam.class);
        when(param.getInput()).thenReturn(Map.of());

        var resultBlock = adapter.callAsync(param).block();

        assertInstanceOf(TextBlock.class, resultBlock.getOutput().get(0));
        assertTrue(((TextBlock) resultBlock.getOutput().get(0)).getText().contains("boom"));
    }

    private static final class DemoTool implements AiTool {
        @Override
        public String name() {
            return "demo_tool";
        }

        @Override
        public String description() {
            return "演示工具";
        }

        @Override
        public Object execute(Map<String, Object> args) {
            return args.get("keyword");
        }

        @Override
        public List<ToolParameter> parameters() {
            return List.of(ToolParameter.required("keyword", "string", "关键词"));
        }
    }
}
