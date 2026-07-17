package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.client.model.ModelStreamSubscription;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatData;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest.ChatMessage;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import com.enterprise.ai.runtime.client.model.RuntimeModelStreamHttpClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelStreamHttpClient.ModelStreamEventDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Shared AgentScope chat model bridge for Runtime Supervisor and Workflow Authoring. */
public final class ReachAiAgentScopeChatModel extends ChatModelBase {

    public static final String PUBLIC_FINAL_TOOL_CALL_CODE = "SUPERVISOR_PUBLIC_FINAL_TOOL_CALL";

    private static final Logger log = LoggerFactory.getLogger(ReachAiAgentScopeChatModel.class);

    private final String modelInstanceId;
    private final RuntimeModelServiceClient modelClient;
    private final RuntimeModelStreamHttpClient streamClient;
    private final ObjectMapper objectMapper;
    private final Consumer<String> contentDeltaSink;
    private final SupervisorAnswerPhase answerPhase;
    private final AtomicBoolean contentStreamed = new AtomicBoolean(false);
    /** 实际写出到下游的公开 content.delta / message.delta 次数（不含 INTERNAL）。 */
    private final AtomicInteger publicContentDeltaCount = new AtomicInteger(0);
    private final AtomicReference<String> streamMode = new AtomicReference<>("none");
    private final AtomicBoolean fallbackUsed = new AtomicBoolean(false);
    private final AtomicReference<String> fallbackReason = new AtomicReference<>();
    private final AtomicReference<ModelStreamSubscription> activeSubscription = new AtomicReference<>();

    public ReachAiAgentScopeChatModel(String modelInstanceId,
                              RuntimeModelServiceClient modelClient,
                              ObjectMapper objectMapper) {
        this(modelInstanceId, modelClient, null, objectMapper, null, null);
    }

    public ReachAiAgentScopeChatModel(String modelInstanceId,
                              RuntimeModelServiceClient modelClient,
                              RuntimeModelStreamHttpClient streamClient,
                              ObjectMapper objectMapper,
                              Consumer<String> contentDeltaSink) {
        this(modelInstanceId, modelClient, streamClient, objectMapper, contentDeltaSink, null);
    }

    public ReachAiAgentScopeChatModel(String modelInstanceId,
                              RuntimeModelServiceClient modelClient,
                              RuntimeModelStreamHttpClient streamClient,
                              ObjectMapper objectMapper,
                              Consumer<String> contentDeltaSink,
                              SupervisorAnswerPhase answerPhase) {
        this.modelInstanceId = modelInstanceId;
        this.modelClient = modelClient;
        this.streamClient = streamClient;
        this.objectMapper = objectMapper;
        this.contentDeltaSink = contentDeltaSink;
        this.answerPhase = answerPhase == null ? new SupervisorAnswerPhase() : answerPhase;
    }

    boolean didStreamContent() {
        return contentStreamed.get();
    }

    int publicContentDeltaCount() {
        return publicContentDeltaCount.get();
    }

    boolean usedSyncFallback() {
        return fallbackUsed.get();
    }

    /**
     * 安全流式诊断字段：不含 reasoning 正文。供 execution.completed.metadata 合并。
     */
    Map<String, Object> safeStreamMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("answerPhase", answerPhase.get().name());
        metadata.put("streamMode", streamMode.get());
        metadata.put("contentStreamed", contentStreamed.get());
        metadata.put("contentDeltaCount", publicContentDeltaCount.get());
        metadata.put("tokenStreaming", "token_stream".equals(streamMode.get()) && publicContentDeltaCount.get() > 0);
        metadata.put("fallbackUsed", fallbackUsed.get());
        if (fallbackReason.get() != null) {
            metadata.put("fallbackReason", fallbackReason.get());
        }
        return metadata;
    }

    SupervisorAnswerPhase answerPhase() {
        return answerPhase;
    }

    /** 取消当前上游模型流读取（幂等）。 */
    public void cancelActiveStream() {
        answerPhase.markCancelled();
        ModelStreamSubscription subscription = activeSubscription.get();
        if (subscription != null) {
            subscription.cancel();
        }
    }

    @Override
    public String getModelName() {
        return modelInstanceId;
    }

    @Override
    protected Flux<ChatResponse> doStream(List<Msg> messages,
                                          List<ToolSchema> tools,
                                          GenerateOptions options) {
        boolean publicFinalRound = isPublicFinalRound(tools, options);
        List<ToolSchema> effectiveTools = publicFinalRound ? List.of() : tools;
        ModelChatRequest request = ModelChatRequest.builder()
                .modelInstanceId(modelInstanceId)
                .messages(toModelMessages(messages))
                .options(toOptions(options))
                .tools(toTools(effectiveTools))
                .toolChoice(publicFinalRound ? objectMapper.getNodeFactory().textNode("none") : null)
                .build();

        if (streamClient == null) {
            return Flux.defer(() -> Flux.just(completeFromSync(request, publicFinalRound)));
        }

        return Flux.<ChatResponse>create(sink -> {
            ModelStreamSubscription subscription = new ModelStreamSubscription();
            activeSubscription.set(subscription);
            sink.onCancel(subscription::cancel);
            sink.onDispose(subscription::cancel);
            Schedulers.boundedElastic().schedule(() -> {
                AtomicBoolean roundConsumed = new AtomicBoolean(false);
                try {
                    if (subscription.isCancelled()) {
                        sink.complete();
                        return;
                    }
                    streamIntoSink(request, sink, roundConsumed, publicFinalRound, subscription);
                } catch (Exception ex) {
                    if (subscription.isCancelled()
                            || answerPhase.get() == SupervisorAnswerPhase.Phase.CANCELLED
                            || isCancellationThrowable(ex)) {
                        // 取消不得触发 sync fallback，也不得映射为 MODEL_STREAM_INTERRUPTED 重试
                        sink.complete();
                        return;
                    }
                    // 本轮已消费上游事件 / 已向 AgentScope 发出 ChatResponse，或此前已公开过 delta → 禁止 sync
                    if (roundConsumed.get() || contentStreamed.get()) {
                        log.error("ReachAI model stream failed after events were already consumed; "
                                        + "sync fallback is disabled: modelInstanceId={}, code={}",
                                modelInstanceId,
                                ex instanceof ModelStreamFailure failure ? failure.code() : ex.getClass().getSimpleName(),
                                ex);
                        sink.error(ex);
                        return;
                    }
                    log.warn("ReachAI model stream failed before consumption; falling back to sync chat: modelInstanceId={}",
                            modelInstanceId, ex);
                    try {
                        fallbackUsed.set(true);
                        streamMode.set("sync_fallback");
                        fallbackReason.set(rootMessage(ex));
                        sink.next(completeFromSync(request, publicFinalRound));
                        sink.complete();
                    } catch (Exception syncError) {
                        syncError.addSuppressed(ex);
                        log.error("ReachAI model sync fallback also failed: modelInstanceId={}",
                                modelInstanceId, syncError);
                        sink.error(syncError);
                    }
                } finally {
                    activeSubscription.compareAndSet(subscription, null);
                }
            });
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    private boolean isPublicFinalRound(List<ToolSchema> tools, GenerateOptions options) {
        if (answerPhase.isPublicFinal()) {
            return true;
        }
        if (options != null && options.getToolChoice() instanceof ToolChoice.None) {
            return true;
        }
        return false;
    }

    private void streamIntoSink(ModelChatRequest request,
                                FluxSink<ChatResponse> sink,
                                AtomicBoolean roundConsumed,
                                boolean publicFinalRound,
                                ModelStreamSubscription subscription) {
        StringBuilder content = new StringBuilder();
        AtomicInteger contentDeltaCount = new AtomicInteger();
        AtomicInteger reasoningDeltaCount = new AtomicInteger();
        AtomicInteger reasoningLength = new AtomicInteger();
        AtomicInteger toolCallDeltaCount = new AtomicInteger();
        Map<Integer, PartialToolCall> toolCalls = new LinkedHashMap<>();
        AtomicReference<String> finishReason = new AtomicReference<>();
        AtomicReference<ChatUsage> usage = new AtomicReference<>();
        AtomicReference<Integer> promptTokens = new AtomicReference<>();
        AtomicReference<Integer> completionTokens = new AtomicReference<>();
        AtomicReference<Integer> reasoningTokens = new AtomicReference<>();
        AtomicReference<Integer> totalTokens = new AtomicReference<>();
        AtomicBoolean completedReceived = new AtomicBoolean(false);
        String responseId = UUID.randomUUID().toString();

        streamClient.streamChatEvents(request, event -> {
            if (subscription.isCancelled()) {
                return;
            }
            if (event == null || event.type == null) {
                return;
            }
            switch (event.type) {
                case "content.delta" -> {
                    if (event.text != null && !event.text.isEmpty()) {
                        roundConsumed.set(true);
                        contentDeltaCount.incrementAndGet();
                        content.append(event.text);
                        if (publicFinalRound) {
                            publishPublicDelta(event.text);
                        }
                        // INTERNAL content 仅供 AgentScope 内部消费，绝不公开
                        if (!subscription.isCancelled()) {
                            sink.next(ChatResponse.builder()
                                    .id(responseId)
                                    .content(List.of(TextBlock.builder().text(event.text).build()))
                                    .build());
                        }
                    }
                }
                case "reasoning.delta" -> {
                    if (event.text != null && !event.text.isEmpty()) {
                        roundConsumed.set(true);
                        reasoningDeltaCount.incrementAndGet();
                        reasoningLength.addAndGet(event.text.length());
                        // 仅累计长度，不保存 reasoning 正文，不公开
                    }
                }
                case "tool_call.delta" -> {
                    roundConsumed.set(true);
                    toolCallDeltaCount.incrementAndGet();
                    if (publicFinalRound) {
                        throw new ModelStreamFailure(
                                PUBLIC_FINAL_TOOL_CALL_CODE,
                                "PUBLIC_FINAL 阶段禁止再调用工具。",
                                "tool_calls",
                                buildDiagnostics(
                                        "tool_calls",
                                        contentDeltaCount.get(),
                                        content.length(),
                                        reasoningDeltaCount.get(),
                                        reasoningLength.get(),
                                        toolCallDeltaCount.get(),
                                        0,
                                        promptTokens.get(),
                                        completionTokens.get(),
                                        reasoningTokens.get(),
                                        totalTokens.get(),
                                        true,
                                        false));
                    }
                    acceptToolCallDelta(toolCalls, event);
                }
                case "usage" -> {
                    roundConsumed.set(true);
                    if (event.usage != null) {
                        Integer prompt = event.usage.get("promptTokens");
                        Integer completion = event.usage.get("completionTokens");
                        Integer reasoning = event.usage.get("reasoningTokens");
                        Integer total = event.usage.get("totalTokens");
                        if (prompt != null) promptTokens.set(prompt);
                        if (completion != null) completionTokens.set(completion);
                        if (reasoning != null) reasoningTokens.set(reasoning);
                        if (total != null) totalTokens.set(total);
                        usage.set(ChatUsage.builder()
                                .inputTokens(prompt == null ? 0 : prompt)
                                .outputTokens(completion == null ? 0 : completion)
                                .build());
                    }
                }
                case "completed" -> {
                    completedReceived.set(true);
                    finishReason.set(event.finishReason);
                }
                case "error" -> {
                    ModelStreamDiagnostics diagnostics = buildDiagnostics(
                            finishReason.get(),
                            contentDeltaCount.get(),
                            content.length(),
                            reasoningDeltaCount.get(),
                            reasoningLength.get(),
                            toolCallDeltaCount.get(),
                            0,
                            promptTokens.get(),
                            completionTokens.get(),
                            reasoningTokens.get(),
                            totalTokens.get(),
                            roundConsumed.get(),
                            false);
                    if (ModelStreamFailure.MODEL_STREAM_INTERRUPTED.equals(event.code)) {
                        log.warn("ReachAI model stream interrupted by upstream: modelInstanceId={}, "
                                        + "contentDeltaCount={}, reasoningDeltaCount={}, reasoningLength={}, "
                                        + "toolCallDeltaCount={}, completionTokens={}",
                                modelInstanceId,
                                contentDeltaCount.get(),
                                reasoningDeltaCount.get(),
                                reasoningLength.get(),
                                toolCallDeltaCount.get(),
                                completionTokens.get());
                        throw ModelStreamFailure.interrupted(diagnostics);
                    }
                    throw new IllegalStateException(
                            event.message == null ? "Model stream error" : event.message);
                }
                default -> {
                }
            }
        }, subscription);

        if (subscription.isCancelled()) {
            sink.complete();
            return;
        }

        // 未收到 completed：即使已有部分 content，也不得当作完整成功答案
        if (!completedReceived.get()) {
            ModelStreamDiagnostics diagnostics = buildDiagnostics(
                    finishReason.get(),
                    contentDeltaCount.get(),
                    content.length(),
                    reasoningDeltaCount.get(),
                    reasoningLength.get(),
                    toolCallDeltaCount.get(),
                    0,
                    promptTokens.get(),
                    completionTokens.get(),
                    reasoningTokens.get(),
                    totalTokens.get(),
                    roundConsumed.get(),
                    false);
            log.warn("ReachAI model stream ended without completed: modelInstanceId={}, "
                            + "contentDeltaCount={}, reasoningDeltaCount={}, reasoningLength={}, "
                            + "toolCallDeltaCount={}, completionTokens={}",
                    modelInstanceId,
                    contentDeltaCount.get(),
                    reasoningDeltaCount.get(),
                    reasoningLength.get(),
                    toolCallDeltaCount.get(),
                    completionTokens.get());
            throw ModelStreamFailure.interrupted(diagnostics);
        }

        List<ContentBlock> blocks = new ArrayList<>();
        if (!content.isEmpty()) {
            blocks.add(TextBlock.builder().text(content.toString()).build());
        }
        List<ContentBlock> toolUseBlocks = toToolUseBlocks(assembledToolCalls(toolCalls));
        blocks.addAll(toolUseBlocks);
        if (blocks.isEmpty()) {
            ModelStreamDiagnostics diagnostics = buildDiagnostics(
                    finishReason.get(),
                    contentDeltaCount.get(),
                    content.length(),
                    reasoningDeltaCount.get(),
                    reasoningLength.get(),
                    toolCallDeltaCount.get(),
                    0,
                    promptTokens.get(),
                    completionTokens.get(),
                    reasoningTokens.get(),
                    totalTokens.get(),
                    roundConsumed.get(),
                    true);
            ModelStreamFailure failure = ModelStreamFailure.emptyResponse(diagnostics);
            log.warn("ReachAI model stream empty response: code={}, finishReason={}, modelInstanceId={}, "
                            + "contentDeltaCount={}, reasoningDeltaCount={}, reasoningLength={}, "
                            + "toolCallDeltaCount={}, completionTokens={}, reasoningTokens={}, completedReceived={}",
                    failure.code(),
                    diagnostics.finishReason(),
                    modelInstanceId,
                    diagnostics.toSafeMap().get("contentDeltaCount"),
                    diagnostics.toSafeMap().get("reasoningDeltaCount"),
                    diagnostics.toSafeMap().get("reasoningLength"),
                    diagnostics.toSafeMap().get("toolCallDeltaCount"),
                    completionTokens.get(),
                    reasoningTokens.get(),
                    completedReceived.get());
            throw failure;
        }
        // 禁止在 INTERNAL 结束后批量 flush；公开内容仅允许 PUBLIC_FINAL 即时发送
        if (publicFinalRound && contentStreamed.get() && !fallbackUsed.get()) {
            streamMode.set("token_stream");
        } else if (!fallbackUsed.get() && "none".equals(streamMode.get())) {
            streamMode.set(publicFinalRound ? "token_stream" : "internal");
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.putAll(safeStreamMetadata());
        metadata.put("publicFinalRound", publicFinalRound);
        // upstream 收到的 content.delta（含 INTERNAL）；公开次数见 contentDeltaCount / publicContentDeltaCount
        metadata.put("upstreamContentDeltaCount", contentDeltaCount.get());
        metadata.put("reasoningDeltaCount", reasoningDeltaCount.get());
        metadata.put("reasoningLength", reasoningLength.get());
        metadata.put("toolCallDeltaCount", toolCallDeltaCount.get());
        metadata.put("assembledToolCallCount", toolUseBlocks.size());
        if (finishReason.get() != null) {
            metadata.put("finishReason", finishReason.get());
        }
        roundConsumed.set(true);
        if (!subscription.isCancelled()) {
            sink.next(ChatResponse.builder()
                    .id(responseId)
                    .content(blocks)
                    .usage(usage.get())
                    .metadata(metadata)
                    .finishReason(finishReason.get())
                    .build());
            sink.complete();
        }
    }

    private void publishPublicDelta(String text) {
        if (contentDeltaSink == null || text == null || text.isEmpty()) {
            return;
        }
        contentDeltaSink.accept(text);
        contentStreamed.set(true);
        publicContentDeltaCount.incrementAndGet();
    }

    private ChatResponse completeFromSync(ModelChatRequest request, boolean publicFinalRound) {
        ModelChatResult result = modelClient.chat(request);
        ModelChatData data = result == null ? null : result.getData();
        if (data == null) {
            throw new IllegalStateException("ReachAI model service returned no data");
        }
        // streamClient==null 的确定性同步路径，或首事件前失败的 sync fallback
        if (!fallbackUsed.get() && streamClient == null) {
            streamMode.set("sync_fallback");
            fallbackUsed.set(true);
            fallbackReason.set("stream_client_unavailable");
        } else if (!fallbackUsed.get()) {
            streamMode.set("sync_fallback");
            fallbackUsed.set(true);
            if (fallbackReason.get() == null) {
                fallbackReason.set("stream_failed_before_events");
            }
        }
        List<ContentBlock> content = new ArrayList<>();
        if (data.getContent() != null && !data.getContent().isBlank()) {
            content.add(TextBlock.builder().text(data.getContent()).build());
            // sync fallback：仅在尚未消费流事件时发生；PUBLIC_FINAL 下一次性公开整段
            if (publicFinalRound) {
                publishPublicDelta(data.getContent());
            }
        }
        content.addAll(toToolUseBlocks(data.getToolCalls()));
        if (content.isEmpty()) {
            Integer prompt = data.getUsage() == null ? null : data.getUsage().getPromptTokens();
            Integer completion = data.getUsage() == null ? null : data.getUsage().getCompletionTokens();
            Integer total = data.getUsage() == null ? null : data.getUsage().getTotalTokens();
            Integer reasoningTok = data.getUsage() == null ? null : data.getUsage().getReasoningTokens();
            int reasoningLen = data.getReasoningContent() == null ? 0 : data.getReasoningContent().length();
            ModelStreamDiagnostics diagnostics = ModelStreamDiagnostics.builder()
                    .modelInstanceId(modelInstanceId)
                    .finishReason(data.getFinishReason())
                    .contentDeltaCount(0)
                    .contentLength(0)
                    .reasoningDeltaCount(reasoningLen > 0 ? 1 : 0)
                    .reasoningLength(reasoningLen)
                    .toolCallDeltaCount(0)
                    .assembledToolCallCount(0)
                    .promptTokens(prompt)
                    .completionTokens(completion)
                    .reasoningTokens(reasoningTok)
                    .totalTokens(total)
                    .consumedAnyEvent(true)
                    .completedReceived(true)
                    .emittedPublicContent(false)
                    .build();
            throw ModelStreamFailure.emptyResponse(diagnostics);
        }
        ChatUsage usage = data.getUsage() == null ? null : ChatUsage.builder()
                .inputTokens(data.getUsage().getPromptTokens())
                .outputTokens(data.getUsage().getCompletionTokens())
                .build();
        Map<String, Object> metadata = new LinkedHashMap<>();
        put(metadata, "provider", data.getProvider());
        put(metadata, "model", data.getModel());
        // 不把 reasoning 正文写入 metadata
        if (data.getReasoningContent() != null && !data.getReasoningContent().isEmpty()) {
            metadata.put("reasoningLength", data.getReasoningContent().length());
        }
        metadata.putAll(safeStreamMetadata());
        return ChatResponse.builder()
                .id(UUID.randomUUID().toString())
                .content(content)
                .usage(usage)
                .metadata(metadata)
                .finishReason(data.getFinishReason())
                .build();
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private ModelStreamDiagnostics buildDiagnostics(String finishReason,
                                                    int contentDeltaCount,
                                                    int contentLength,
                                                    int reasoningDeltaCount,
                                                    int reasoningLength,
                                                    int toolCallDeltaCount,
                                                    int assembledToolCallCount,
                                                    Integer promptTokens,
                                                    Integer completionTokens,
                                                    Integer reasoningTokens,
                                                    Integer totalTokens,
                                                    boolean consumedAnyEvent,
                                                    boolean completedReceived) {
        return ModelStreamDiagnostics.builder()
                .modelInstanceId(modelInstanceId)
                .finishReason(finishReason)
                .contentDeltaCount(contentDeltaCount)
                .contentLength(contentLength)
                .reasoningDeltaCount(reasoningDeltaCount)
                .reasoningLength(reasoningLength)
                .toolCallDeltaCount(toolCallDeltaCount)
                .assembledToolCallCount(assembledToolCallCount)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .reasoningTokens(reasoningTokens)
                .totalTokens(totalTokens)
                .consumedAnyEvent(consumedAnyEvent)
                .completedReceived(completedReceived)
                .emittedPublicContent(contentStreamed.get())
                .build();
    }

    private void acceptToolCallDelta(Map<Integer, PartialToolCall> toolCalls, ModelStreamEventDto event) {
        if (event.toolCall == null) {
            return;
        }
        int index = event.toolCall.index == null ? toolCalls.size() : event.toolCall.index;
        PartialToolCall partial = toolCalls.computeIfAbsent(index, ignored -> new PartialToolCall());
        if (event.toolCall.id != null) partial.id = event.toolCall.id;
        if (event.toolCall.type != null) partial.type = event.toolCall.type;
        if (event.toolCall.name != null) {
            partial.name = (partial.name == null ? "" : partial.name) + event.toolCall.name;
        }
        if (event.toolCall.arguments != null) {
            partial.arguments = (partial.arguments == null ? "" : partial.arguments) + event.toolCall.arguments;
        }
    }

    private JsonNode assembledToolCalls(Map<Integer, PartialToolCall> toolCalls) {
        if (toolCalls.isEmpty()) {
            return null;
        }
        ArrayNode array = objectMapper.createArrayNode();
        for (PartialToolCall partial : toolCalls.values()) {
            ObjectNode node = array.addObject();
            if (partial.id != null) node.put("id", partial.id);
            node.put("type", partial.type == null ? "function" : partial.type);
            ObjectNode function = node.putObject("function");
            function.put("name", partial.name == null ? "" : partial.name);
            function.put("arguments", partial.arguments == null ? "{}" : partial.arguments);
        }
        return array;
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

    private String text(Object value) {
        if (value == null) return "";
        if (value instanceof String s) return s;
        if (value instanceof List<?> blocks) {
            return blocks.stream()
                    .map(block -> block instanceof TextBlock text ? text.getText() : String.valueOf(block))
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");
        }
        return writeJson(value);
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

    private static boolean isCancellationThrowable(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof java.util.concurrent.CancellationException
                    || current instanceof InterruptedException
                    || "SUPERVISOR_CANCELLED".equals(current.getMessage())) {
                return true;
            }
            String name = current.getClass().getName();
            if (name.contains("Cancellation") || name.contains("ClosedByInterrupt")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static final class PartialToolCall {
        private String id;
        private String type;
        private String name;
        private String arguments;
    }
}
