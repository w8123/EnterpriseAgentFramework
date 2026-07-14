package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatData;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest.ChatMessage;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class ReachAiAgentScopeChatModel extends ChatModelBase {

    private final String modelInstanceId;
    private final RuntimeModelServiceClient modelClient;
    private final ObjectMapper objectMapper;

    ReachAiAgentScopeChatModel(String modelInstanceId,
                              RuntimeModelServiceClient modelClient,
                              ObjectMapper objectMapper) {
        this.modelInstanceId = modelInstanceId;
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getModelName() {
        return modelInstanceId;
    }

    @Override
    protected Flux<ChatResponse> doStream(List<Msg> messages,
                                          List<ToolSchema> tools,
                                          GenerateOptions options) {
        return Flux.defer(() -> {
            ModelChatRequest request = ModelChatRequest.builder()
                    .modelInstanceId(modelInstanceId)
                    .messages(toModelMessages(messages))
                    .options(toOptions(options))
                    .tools(toTools(tools))
                    .build();
            ModelChatResult result = modelClient.chat(request);
            ModelChatData data = result == null ? null : result.getData();
            if (data == null) {
                return Flux.error(new IllegalStateException("ReachAI model service returned no data"));
            }
            List<ContentBlock> content = new ArrayList<>();
            if (data.getContent() != null && !data.getContent().isBlank()) {
                content.add(TextBlock.builder().text(data.getContent()).build());
            }
            content.addAll(toToolUseBlocks(data.getToolCalls()));
            if (content.isEmpty()) {
                return Flux.error(new IllegalStateException("ReachAI model service returned empty content and no tool calls"));
            }
            ChatUsage usage = data.getUsage() == null ? null : ChatUsage.builder()
                    .inputTokens(data.getUsage().getPromptTokens())
                    .outputTokens(data.getUsage().getCompletionTokens())
                    .build();
            Map<String, Object> metadata = new LinkedHashMap<>();
            put(metadata, "provider", data.getProvider());
            put(metadata, "model", data.getModel());
            put(metadata, "reasoningContent", data.getReasoningContent());
            return Flux.just(ChatResponse.builder()
                    .id(UUID.randomUUID().toString())
                    .content(content)
                    .usage(usage)
                    .metadata(metadata)
                    .finishReason(data.getFinishReason())
                    .build());
        });
    }

    private List<ChatMessage> toModelMessages(List<Msg> messages) {
        List<ChatMessage> converted = new ArrayList<>();
        if (messages == null) {
            return converted;
        }
        for (Msg message : messages) {
            List<ToolResultBlock> results = message.getContentBlocks(ToolResultBlock.class);
            if (!results.isEmpty()) {
                for (ToolResultBlock result : results) {
                    converted.add(ChatMessage.builder()
                            .role("tool")
                            .name(result.getName())
                            .toolCallId(result.getId())
                            .content(text(result.getOutput()))
                            .build());
                }
                continue;
            }
            ChatMessage.ChatMessageBuilder builder = ChatMessage.builder()
                    .role(message.getRole().name().toLowerCase(Locale.ROOT))
                    .content(blankToNull(message.getTextContent()));
            List<ToolUseBlock> calls = message.getContentBlocks(ToolUseBlock.class);
            if (!calls.isEmpty()) {
                builder.toolCalls(objectMapper.valueToTree(calls.stream().map(call -> Map.of(
                        "id", call.getId(),
                        "type", "function",
                        "function", Map.of(
                                "name", call.getName(),
                                "arguments", writeJson(call.getInput())))).toList()));
            }
            converted.add(builder.build());
        }
        return converted;
    }

    private JsonNode toTools(List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        return objectMapper.valueToTree(tools.stream().map(tool -> {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.getName());
            function.put("description", tool.getDescription());
            function.put("parameters", tool.getParameters());
            if (tool.getStrict() != null) function.put("strict", tool.getStrict());
            return Map.of("type", "function", "function", function);
        }).toList());
    }

    private List<ContentBlock> toToolUseBlocks(JsonNode toolCalls) {
        if (toolCalls == null || !toolCalls.isArray()) {
            return List.of();
        }
        List<ContentBlock> blocks = new ArrayList<>();
        for (JsonNode call : toolCalls) {
            JsonNode function = call.path("function");
            String name = function.path("name").asText(null);
            if (name == null || name.isBlank()) {
                continue;
            }
            String id = call.path("id").asText(UUID.randomUUID().toString());
            Map<String, Object> input = readArguments(function.get("arguments"));
            blocks.add(ToolUseBlock.builder()
                    .id(id)
                    .name(name)
                    .input(input)
                    .content(writeJson(input))
                    .build());
        }
        return blocks;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readArguments(JsonNode arguments) {
        if (arguments == null || arguments.isNull()) return Map.of();
        try {
            if (arguments.isObject()) return objectMapper.convertValue(arguments, Map.class);
            String text = arguments.asText("{}");
            return objectMapper.readValue(text, Map.class);
        } catch (Exception ex) {
            return Map.of("raw", arguments.asText());
        }
    }

    private Map<String, Object> toOptions(GenerateOptions options) {
        if (options == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        put(result, "temperature", options.getTemperature());
        put(result, "top_p", options.getTopP());
        put(result, "max_tokens", options.getMaxTokens());
        put(result, "parallel_tool_calls", options.getParallelToolCalls());
        if (options.getAdditionalBodyParams() != null) result.putAll(options.getAdditionalBodyParams());
        return result.isEmpty() ? null : result;
    }

    private String text(List<ContentBlock> blocks) {
        if (blocks == null) return "";
        return blocks.stream().map(block -> block instanceof TextBlock text ? text.getText() : String.valueOf(block))
                .reduce((left, right) -> left + "\n" + right).orElse("");
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void put(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }
}
