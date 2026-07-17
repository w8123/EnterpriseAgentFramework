package com.enterprise.ai.model.runtime;

import com.enterprise.ai.model.service.ModelStreamEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 聚合 OpenAI-compatible tool_calls 流式增量（按 index）。
 */
public final class OpenAiToolCallStreamAggregator {

    private final ObjectMapper objectMapper;
    private final Map<Integer, PartialToolCall> byIndex = new LinkedHashMap<>();

    public OpenAiToolCallStreamAggregator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ModelStreamEvent.ToolCallDelta accept(JsonNode toolCallNode) {
        if (toolCallNode == null || toolCallNode.isNull()) {
            return null;
        }
        int index = toolCallNode.path("index").asInt(byIndex.size());
        PartialToolCall partial = byIndex.computeIfAbsent(index, ignored -> new PartialToolCall());
        if (toolCallNode.hasNonNull("id")) {
            partial.id = toolCallNode.get("id").asText();
        }
        if (toolCallNode.hasNonNull("type")) {
            partial.type = toolCallNode.get("type").asText();
        }
        JsonNode function = toolCallNode.path("function");
        String nameDelta = null;
        String argsDelta = null;
        if (function.hasNonNull("name")) {
            nameDelta = function.get("name").asText();
            partial.name = (partial.name == null ? "" : partial.name) + nameDelta;
        }
        if (function.hasNonNull("arguments")) {
            argsDelta = function.get("arguments").asText();
            partial.arguments = (partial.arguments == null ? "" : partial.arguments) + argsDelta;
        }
        return ModelStreamEvent.ToolCallDelta.builder()
                .index(index)
                .id(partial.id)
                .type(partial.type == null ? "function" : partial.type)
                .name(nameDelta)
                .arguments(argsDelta)
                .build();
    }

    public JsonNode assembledToolCalls() {
        ArrayNode array = objectMapper.createArrayNode();
        for (PartialToolCall partial : byIndex.values()) {
            ObjectNode node = array.addObject();
            if (partial.id != null) node.put("id", partial.id);
            node.put("type", partial.type == null ? "function" : partial.type);
            ObjectNode function = node.putObject("function");
            function.put("name", partial.name == null ? "" : partial.name);
            function.put("arguments", partial.arguments == null ? "{}" : partial.arguments);
        }
        return array;
    }

    public boolean hasToolCalls() {
        return !byIndex.isEmpty();
    }

    private static final class PartialToolCall {
        private String id;
        private String type;
        private String name;
        private String arguments;
    }
}
