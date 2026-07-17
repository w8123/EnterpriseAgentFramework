package com.enterprise.ai.model.runtime;

import com.enterprise.ai.model.service.ChatResponse;
import com.enterprise.ai.model.service.ModelStreamEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * OpenAI 兼容 SSE chunk 解析。可单测，不含 HTTP / 凭证逻辑。
 */
public final class OpenAiCompatibleStreamParser {

    private final ObjectMapper objectMapper;
    private final OpenAiToolCallStreamAggregator toolCalls;
    private String finishReason;

    public OpenAiCompatibleStreamParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.toolCalls = new OpenAiToolCallStreamAggregator(objectMapper);
    }

    public String finishReason() {
        return finishReason;
    }

    /**
     * 解析单个 data JSON，向 sink 发出结构化事件。返回本 chunk 产生的事件（便于单测）。
     */
    public List<ModelStreamEvent> acceptChunk(JsonNode root, Consumer<ModelStreamEvent> sink) {
        List<ModelStreamEvent> emitted = new ArrayList<>();
        JsonNode choice = firstChoice(root);
        JsonNode delta = choice.path("delta");
        if (delta.hasNonNull("reasoning_content")) {
            String reasoning = delta.get("reasoning_content").asText("");
            if (!reasoning.isEmpty()) {
                ModelStreamEvent event = ModelStreamEvent.reasoningDelta(reasoning);
                emitted.add(event);
                sink.accept(event);
            }
        }
        if (delta.hasNonNull("content")) {
            String content = delta.get("content").asText("");
            if (!content.isEmpty()) {
                ModelStreamEvent event = ModelStreamEvent.contentDelta(content);
                emitted.add(event);
                sink.accept(event);
            }
        }
        JsonNode toolCallNodes = delta.path("tool_calls");
        if (toolCallNodes.isArray()) {
            for (JsonNode toolCallNode : toolCallNodes) {
                ModelStreamEvent.ToolCallDelta toolDelta = toolCalls.accept(toolCallNode);
                if (toolDelta != null) {
                    ModelStreamEvent event = ModelStreamEvent.toolCallDelta(toolDelta);
                    emitted.add(event);
                    sink.accept(event);
                }
            }
        }
        if (choice.hasNonNull("finish_reason")) {
            String reason = choice.get("finish_reason").asText();
            if (reason != null && !reason.isBlank()) {
                finishReason = reason.trim();
            }
        }
        if (root.has("usage") && !root.get("usage").isNull()) {
            ModelStreamEvent event = ModelStreamEvent.usage(parseUsage(root));
            emitted.add(event);
            sink.accept(event);
        }
        return emitted;
    }

    public static ChatResponse.Usage parseUsage(JsonNode root) {
        JsonNode usage = root.path("usage");
        Integer reasoningTokens = null;
        JsonNode details = usage.path("completion_tokens_details");
        if (details.has("reasoning_tokens") && !details.get("reasoning_tokens").isNull()) {
            reasoningTokens = details.get("reasoning_tokens").asInt();
        } else if (usage.has("reasoning_tokens") && !usage.get("reasoning_tokens").isNull()) {
            reasoningTokens = usage.get("reasoning_tokens").asInt();
        }
        return ChatResponse.Usage.builder()
                .promptTokens(usage.path("prompt_tokens").asInt(0))
                .completionTokens(usage.path("completion_tokens").asInt(0))
                .totalTokens(usage.path("total_tokens").asInt(0))
                .reasoningTokens(reasoningTokens)
                .build();
    }

    private JsonNode firstChoice(JsonNode root) {
        JsonNode choices = root.path("choices");
        return choices.isArray() && !choices.isEmpty() ? choices.get(0) : objectMapper.createObjectNode();
    }

    /** 测试辅助：从 JSON 字符串解析。 */
    public List<ModelStreamEvent> acceptChunkJson(String json) throws Exception {
        List<ModelStreamEvent> events = new ArrayList<>();
        acceptChunk(objectMapper.readTree(json), events::add);
        return events;
    }

    public ObjectNode emptyChoiceRoot() {
        return objectMapper.createObjectNode();
    }
}
