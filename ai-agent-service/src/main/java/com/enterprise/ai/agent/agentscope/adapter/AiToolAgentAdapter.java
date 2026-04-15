package com.enterprise.ai.agent.agentscope.adapter;

import com.enterprise.ai.skill.AiTool;
import com.enterprise.ai.skill.ToolParameter;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class AiToolAgentAdapter implements AgentTool {

    private final AiTool aiTool;

    public AiToolAgentAdapter(AiTool aiTool) {
        this.aiTool = Objects.requireNonNull(aiTool, "aiTool must not be null");
    }

    @Override
    public String getName() {
        return aiTool.name();
    }

    @Override
    public String getDescription() {
        return aiTool.description();
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = aiTool.parameters().stream()
                .filter(ToolParameter::required)
                .map(ToolParameter::name)
                .toList();

        for (ToolParameter parameter : aiTool.parameters()) {
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", normalizeType(parameter.type()));
            property.put("description", parameter.description());
            properties.put(parameter.name(), property);
        }

        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            try {
                Object result = aiTool.execute(param.getInput() == null ? Map.of() : param.getInput());
                return ToolResultBlock.text(result == null ? "" : String.valueOf(result));
            } catch (Exception ex) {
                return ToolResultBlock.error(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            }
        });
    }

    private String normalizeType(String rawType) {
        String normalized = rawType == null ? "string" : rawType;
        return switch (normalized) {
            case "integer", "number", "boolean", "object", "array", "string" -> normalized;
            case "json" -> "object";
            default -> "string";
        };
    }
}
