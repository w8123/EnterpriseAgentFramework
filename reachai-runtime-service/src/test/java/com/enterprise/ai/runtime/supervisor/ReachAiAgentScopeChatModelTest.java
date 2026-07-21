package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.client.model.ModelStreamSubscription;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatData;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelUsage;
import com.enterprise.ai.runtime.client.model.RuntimeModelStreamHttpClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelStreamHttpClient.ModelStreamEventDto;
import com.enterprise.ai.runtime.client.model.RuntimeModelStreamHttpClient.ToolCallDeltaDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReachAiAgentScopeChatModelTest {

    @Test
    void suppliesBothStructuredInputAndJsonContentRequiredByAgentScope2() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> arguments = Map.of("summary", "query", "steps", List.of("lookup"));
        var toolCalls = objectMapper.valueToTree(List.of(Map.of(
                "id", "call-1",
                "type", "function",
                "function", Map.of(
                        "name", "record_supervisor_plan",
                        "arguments", objectMapper.writeValueAsString(arguments)))));
        RuntimeModelServiceClient client = request -> new ModelChatResult(200, "success",
                new ModelChatData(null, "test", "test", new ModelUsage(1, 1, 2), null,
                        toolCalls, "tool_calls"));
        ReachAiAgentScopeChatModel model = new ReachAiAgentScopeChatModel("model-1", client, objectMapper);

        var response = model.stream(List.of(Msg.builder()
                        .name("user").role(MsgRole.USER).textContent("query").build()), List.of(), null)
                .blockFirst();
        ToolUseBlock call = (ToolUseBlock) response.getContent().get(0);

        assertEquals(Map.of("summary", "query", "steps", List.of("lookup")), call.getInput());
        assertEquals(objectMapper.valueToTree(arguments), objectMapper.readTree(call.getContent()));
    }

    @Test
    void doesNotEmitPublicDeltasWhenRoundContainsToolCalls() {
        ObjectMapper objectMapper = new ObjectMapper();
        List<String> publicDeltas = new CopyOnWriteArrayList<>();

        RuntimeModelStreamHttpClient streamClient = new RuntimeModelStreamHttpClient(objectMapper, "http://localhost") {
            @Override
            public void streamChatEvents(ModelChatRequest request, Consumer<ModelStreamEventDto> onEvent) {
                ModelStreamEventDto d1 = new ModelStreamEventDto();
                d1.type = "content.delta";
                d1.text = "planning ";
                onEvent.accept(d1);

                ModelStreamEventDto tool = new ModelStreamEventDto();
                tool.type = "tool_call.delta";
                tool.toolCall = new ToolCallDeltaDto();
                tool.toolCall.index = 0;
                tool.toolCall.id = "call-x";
                tool.toolCall.name = "plan";
                tool.toolCall.arguments = "{\"a\":1}";
                onEvent.accept(tool);

                ModelStreamEventDto done = new ModelStreamEventDto();
                done.type = "completed";
                done.finishReason = "tool_calls";
                onEvent.accept(done);
            }
        };

        ReachAiAgentScopeChatModel model = new ReachAiAgentScopeChatModel(
                "model-1",
                request -> {
                    throw new IllegalStateException("sync should not be used when stream succeeds");
                },
                streamClient,
                objectMapper,
                publicDeltas::add);

        List<io.agentscope.core.model.ChatResponse> responses = model.stream(
                        List.of(Msg.builder().name("user").role(MsgRole.USER).textContent("q").build()),
                        List.of(),
                        null)
                .collectList()
                .block();

        assertTrue(publicDeltas.isEmpty(), "intermediate ReAct content must not leak as message.delta");
        assertFalse(model.didStreamContent());
        assertTrue(responses != null && !responses.isEmpty());
        io.agentscope.core.model.ChatResponse last = responses.get(responses.size() - 1);
        List<ToolUseBlock> tools = new ArrayList<>();
        for (var block : last.getContent()) {
            if (block instanceof ToolUseBlock toolUse) tools.add(toolUse);
        }
        assertEquals(1, tools.size());
        assertEquals("plan", tools.get(0).getName());
    }

    @Test
    void allowsSyncFallbackWhenStreamFailsBeforeAnyEvent() {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicInteger syncCalls = new AtomicInteger();
        RuntimeModelStreamHttpClient streamClient = new RuntimeModelStreamHttpClient(objectMapper, "http://localhost") {
            @Override
            public void streamChatEvents(ModelChatRequest request, Consumer<ModelStreamEventDto> onEvent) {
                throw new IllegalStateException("stream boom before events");
            }
        };
        RuntimeModelServiceClient syncClient = request -> {
            syncCalls.incrementAndGet();
            return new ModelChatResult(200, "success",
                    new ModelChatData("fallback-answer", "test", "test",
                            new ModelUsage(1, 1, 2), null, null, "stop"));
        };

        ReachAiAgentScopeChatModel model = new ReachAiAgentScopeChatModel(
                "model-1", syncClient, streamClient, objectMapper, text -> {
        });

        var response = model.stream(
                        List.of(Msg.builder().name("user").role(MsgRole.USER).textContent("q").build()),
                        List.of(),
                        null)
                .blockLast();

        assertEquals(1, syncCalls.get());
        assertFalse(model.didStreamContent());
        assertTrue(response != null && response.getContent() != null && !response.getContent().isEmpty());
    }

    @Test
    void forbidsSyncFallbackAfterContentDelta() {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicInteger syncCalls = new AtomicInteger();
        RuntimeModelStreamHttpClient streamClient = new RuntimeModelStreamHttpClient(objectMapper, "http://localhost") {
            @Override
            public void streamChatEvents(ModelChatRequest request, Consumer<ModelStreamEventDto> onEvent) {
                ModelStreamEventDto d1 = new ModelStreamEventDto();
                d1.type = "content.delta";
                d1.text = "partial";
                onEvent.accept(d1);
                throw new IllegalStateException("cut after content.delta");
            }
        };
        RuntimeModelServiceClient syncClient = request -> {
            syncCalls.incrementAndGet();
            return new ModelChatResult(200, "success",
                    new ModelChatData("should-not", "test", "test",
                            new ModelUsage(1, 1, 2), null, null, "stop"));
        };

        ReachAiAgentScopeChatModel model = new ReachAiAgentScopeChatModel(
                "model-1", syncClient, streamClient, objectMapper, text -> {
        });

        assertThrows(Exception.class, () ->
                model.stream(
                                List.of(Msg.builder().name("user").role(MsgRole.USER).textContent("q").build()),
                                List.of(),
                                null)
                        .collectList()
                        .block());
        assertEquals(0, syncCalls.get(), "must not sync after content.delta");
    }

    @Test
    void forbidsSyncFallbackAfterToolCallDelta() {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicInteger syncCalls = new AtomicInteger();
        RuntimeModelStreamHttpClient streamClient = new RuntimeModelStreamHttpClient(objectMapper, "http://localhost") {
            @Override
            public void streamChatEvents(ModelChatRequest request, Consumer<ModelStreamEventDto> onEvent) {
                ModelStreamEventDto tool = new ModelStreamEventDto();
                tool.type = "tool_call.delta";
                tool.toolCall = new ToolCallDeltaDto();
                tool.toolCall.index = 0;
                tool.toolCall.id = "c1";
                tool.toolCall.name = "lookup";
                tool.toolCall.arguments = "{";
                onEvent.accept(tool);
                throw new IllegalStateException("cut after tool_call.delta");
            }
        };
        RuntimeModelServiceClient syncClient = request -> {
            syncCalls.incrementAndGet();
            return new ModelChatResult(200, "success",
                    new ModelChatData("should-not", "test", "test",
                            new ModelUsage(1, 1, 2), null, null, "stop"));
        };

        ReachAiAgentScopeChatModel model = new ReachAiAgentScopeChatModel(
                "model-1", syncClient, streamClient, objectMapper, text -> {
        });

        assertThrows(Exception.class, () ->
                model.stream(
                                List.of(Msg.builder().name("user").role(MsgRole.USER).textContent("q").build()),
                                List.of(),
                                null)
                        .collectList()
                        .block());
        assertEquals(0, syncCalls.get(), "must not sync after tool_call.delta");
    }

    @Test
    void reasoningThenStreamEndWithoutCompleted_isInterruptedAndForbidsFallback() {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicInteger syncCalls = new AtomicInteger();
        RuntimeModelStreamHttpClient streamClient = new RuntimeModelStreamHttpClient(objectMapper, "http://localhost") {
            @Override
            public void streamChatEvents(ModelChatRequest request, Consumer<ModelStreamEventDto> onEvent) {
                ModelStreamEventDto reasoning = new ModelStreamEventDto();
                reasoning.type = "reasoning.delta";
                reasoning.text = "secret-reasoning-body";
                onEvent.accept(reasoning);
                // 提前 EOF：无 completed
            }
        };
        RuntimeModelServiceClient syncClient = request -> {
            syncCalls.incrementAndGet();
            return new ModelChatResult(200, "success",
                    new ModelChatData("should-not", "test", "test",
                            new ModelUsage(1, 1, 2), null, null, "stop"));
        };
        ReachAiAgentScopeChatModel model = new ReachAiAgentScopeChatModel(
                "model-1", syncClient, streamClient, objectMapper, text -> {
        });

        Exception error = assertThrows(Exception.class, () ->
                model.stream(
                                List.of(Msg.builder().name("user").role(MsgRole.USER).textContent("q").build()),
                                List.of(),
                                null)
                        .collectList()
                        .block());
        ModelStreamFailure failure = ModelStreamFailure.findIn(error);
        assertEquals(ModelStreamFailure.MODEL_STREAM_INTERRUPTED, failure.code());
        assertEquals(0, syncCalls.get());
        assertFalse(failure.getMessage().contains("secret-reasoning-body"));
        assertFalse(String.valueOf(failure.toSafeMetadata()).contains("secret-reasoning-body"));
        assertEquals(1, failure.diagnostics().toSafeMap().get("reasoningDeltaCount"));
        assertEquals(false, failure.diagnostics().toSafeMap().get("completedReceived"));
    }

    @Test
    void contentThenStreamEndWithoutCompleted_isInterruptedNotSuccess() {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicInteger syncCalls = new AtomicInteger();
        List<String> publicDeltas = new CopyOnWriteArrayList<>();
        RuntimeModelStreamHttpClient streamClient = new RuntimeModelStreamHttpClient(objectMapper, "http://localhost") {
            @Override
            public void streamChatEvents(ModelChatRequest request, Consumer<ModelStreamEventDto> onEvent) {
                ModelStreamEventDto delta = new ModelStreamEventDto();
                delta.type = "content.delta";
                delta.text = "partial-only";
                onEvent.accept(delta);
            }
        };
        ReachAiAgentScopeChatModel model = new ReachAiAgentScopeChatModel(
                "model-1",
                request -> {
                    syncCalls.incrementAndGet();
                    return new ModelChatResult(200, "success",
                            new ModelChatData("should-not", "test", "test",
                                    new ModelUsage(1, 1, 2), null, null, "stop"));
                },
                streamClient,
                objectMapper,
                publicDeltas::add);

        Exception error = assertThrows(Exception.class, () ->
                model.stream(
                                List.of(Msg.builder().name("user").role(MsgRole.USER).textContent("q").build()),
                                List.of(),
                                null)
                        .collectList()
                        .block());
        ModelStreamFailure failure = ModelStreamFailure.findIn(error);
        assertEquals(ModelStreamFailure.MODEL_STREAM_INTERRUPTED, failure.code());
        assertEquals(0, syncCalls.get());
        assertTrue(publicDeltas.isEmpty(), "must not flush partial content as public answer");
        assertEquals(1, failure.diagnostics().toSafeMap().get("contentDeltaCount"));
        assertEquals(false, failure.diagnostics().toSafeMap().get("completedReceived"));
    }

    @Test
    void usageThenStreamEndWithoutCompleted_keepsUsageInDiagnostics() {
        ObjectMapper objectMapper = new ObjectMapper();
        RuntimeModelStreamHttpClient streamClient = new RuntimeModelStreamHttpClient(objectMapper, "http://localhost") {
            @Override
            public void streamChatEvents(ModelChatRequest request, Consumer<ModelStreamEventDto> onEvent) {
                ModelStreamEventDto usage = new ModelStreamEventDto();
                usage.type = "usage";
                usage.usage = Map.of("promptTokens", 11, "completionTokens", 22, "totalTokens", 33);
                onEvent.accept(usage);
            }
        };
        ReachAiAgentScopeChatModel model = new ReachAiAgentScopeChatModel(
                "model-1",
                request -> {
                    throw new IllegalStateException("sync should not run");
                },
                streamClient,
                objectMapper,
                text -> {
                });

        Exception error = assertThrows(Exception.class, () ->
                model.stream(
                                List.of(Msg.builder().name("user").role(MsgRole.USER).textContent("q").build()),
                                List.of(),
                                null)
                        .collectList()
                        .block());
        ModelStreamFailure failure = ModelStreamFailure.findIn(error);
        assertEquals(ModelStreamFailure.MODEL_STREAM_INTERRUPTED, failure.code());
        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) failure.toSafeMetadata().get("usage");
        assertEquals(11, usage.get("promptTokens"));
        assertEquals(22, usage.get("completionTokens"));
        assertEquals(false, failure.diagnostics().toSafeMap().get("completedReceived"));
    }

    @Test
    void upstreamInterruptedErrorEvent_mapsByStableCode() {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicInteger syncCalls = new AtomicInteger();
        RuntimeModelStreamHttpClient streamClient = new RuntimeModelStreamHttpClient(objectMapper, "http://localhost") {
            @Override
            public void streamChatEvents(ModelChatRequest request, Consumer<ModelStreamEventDto> onEvent) {
                ModelStreamEventDto reasoning = new ModelStreamEventDto();
                reasoning.type = "reasoning.delta";
                reasoning.text = "secret-reasoning-body";
                onEvent.accept(reasoning);
                ModelStreamEventDto error = new ModelStreamEventDto();
                error.type = "error";
                error.code = ModelStreamFailure.MODEL_STREAM_INTERRUPTED;
                error.message = "模型流在返回最终结果前中断，请重试。";
                onEvent.accept(error);
            }
        };
        ReachAiAgentScopeChatModel model = new ReachAiAgentScopeChatModel(
                "model-1",
                request -> {
                    syncCalls.incrementAndGet();
                    return new ModelChatResult(200, "success",
                            new ModelChatData("should-not", "test", "test",
                                    new ModelUsage(1, 1, 2), null, null, "stop"));
                },
                streamClient,
                objectMapper,
                text -> {
                });

        Exception error = assertThrows(Exception.class, () ->
                model.stream(
                                List.of(Msg.builder().name("user").role(MsgRole.USER).textContent("q").build()),
                                List.of(),
                                null)
                        .collectList()
                        .block());
        ModelStreamFailure failure = ModelStreamFailure.findIn(error);
        assertEquals(ModelStreamFailure.MODEL_STREAM_INTERRUPTED, failure.code());
        assertEquals(0, syncCalls.get());
        assertFalse(String.valueOf(failure.toSafeMetadata()).contains("secret-reasoning-body"));
    }

    @Test
    void mapsReasoningOnlyLengthToOutputTokenLimitWithoutSyncFallback() {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicInteger syncCalls = new AtomicInteger();
        RuntimeModelStreamHttpClient streamClient = new RuntimeModelStreamHttpClient(objectMapper, "http://localhost") {
            @Override
            public void streamChatEvents(ModelChatRequest request, Consumer<ModelStreamEventDto> onEvent) {
                ModelStreamEventDto reasoning = new ModelStreamEventDto();
                reasoning.type = "reasoning.delta";
                reasoning.text = "secret-reasoning-body";
                onEvent.accept(reasoning);
                ModelStreamEventDto usage = new ModelStreamEventDto();
                usage.type = "usage";
                usage.usage = Map.of("promptTokens", 20, "completionTokens", 4096, "reasoningTokens", 4000, "totalTokens", 4116);
                onEvent.accept(usage);
                ModelStreamEventDto done = new ModelStreamEventDto();
                done.type = "completed";
                done.finishReason = "length";
                onEvent.accept(done);
            }
        };
        RuntimeModelServiceClient syncClient = request -> {
            syncCalls.incrementAndGet();
            return new ModelChatResult(200, "success",
                    new ModelChatData("should-not", "test", "test",
                            new ModelUsage(1, 1, 2), null, null, "stop"));
        };

        ReachAiAgentScopeChatModel model = new ReachAiAgentScopeChatModel(
                "seed-deepseek-v4-flash", syncClient, streamClient, objectMapper, text -> {
        });

        Exception error = assertThrows(Exception.class, () ->
                model.stream(
                                List.of(Msg.builder().name("user").role(MsgRole.USER).textContent("q").build()),
                                List.of(),
                                null)
                        .collectList()
                        .block());
        ModelStreamFailure failure = ModelStreamFailure.findIn(error);
        assertInstanceOf(ModelStreamFailure.class, failure);
        assertEquals(ModelStreamFailure.MODEL_OUTPUT_TOKEN_LIMIT, failure.code());
        assertEquals(0, syncCalls.get());
        assertFalse(failure.getMessage().contains("secret-reasoning-body"));
        assertFalse(String.valueOf(failure.toSafeMetadata()).contains("secret-reasoning-body"));
    }

    @Test
    void mapsStopEmptyContentToEmptyResponse() {
        ObjectMapper objectMapper = new ObjectMapper();
        RuntimeModelStreamHttpClient streamClient = new RuntimeModelStreamHttpClient(objectMapper, "http://localhost") {
            @Override
            public void streamChatEvents(ModelChatRequest request, Consumer<ModelStreamEventDto> onEvent) {
                ModelStreamEventDto usage = new ModelStreamEventDto();
                usage.type = "usage";
                usage.usage = Map.of("promptTokens", 1, "completionTokens", 0, "totalTokens", 1);
                onEvent.accept(usage);
                ModelStreamEventDto done = new ModelStreamEventDto();
                done.type = "completed";
                done.finishReason = "stop";
                onEvent.accept(done);
            }
        };

        ReachAiAgentScopeChatModel model = new ReachAiAgentScopeChatModel(
                "model-1",
                request -> {
                    throw new IllegalStateException("sync should not run");
                },
                streamClient,
                objectMapper,
                text -> {
                });

        Exception error = assertThrows(Exception.class, () ->
                model.stream(
                                List.of(Msg.builder().name("user").role(MsgRole.USER).textContent("q").build()),
                                List.of(),
                                null)
                        .collectList()
                        .block());
        assertEquals(ModelStreamFailure.MODEL_EMPTY_RESPONSE, ModelStreamFailure.findIn(error).code());
    }

    @Test
    void mapsContentFilterAndInsufficientResource() {
        assertEquals(ModelStreamFailure.MODEL_CONTENT_FILTERED,
                emptyFinishFailure("content_filter").code());
        assertEquals(ModelStreamFailure.MODEL_INSUFFICIENT_SYSTEM_RESOURCE,
                emptyFinishFailure("insufficient_system_resource").code());
    }

    @Test
    void forbidsSyncFallbackAfterReasoningDelta() {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicInteger syncCalls = new AtomicInteger();
        RuntimeModelStreamHttpClient streamClient = new RuntimeModelStreamHttpClient(objectMapper, "http://localhost") {
            @Override
            public void streamChatEvents(ModelChatRequest request, Consumer<ModelStreamEventDto> onEvent) {
                ModelStreamEventDto reasoning = new ModelStreamEventDto();
                reasoning.type = "reasoning.delta";
                reasoning.text = "partial-reason";
                onEvent.accept(reasoning);
                throw new IllegalStateException("cut after reasoning");
            }
        };
        RuntimeModelServiceClient syncClient = request -> {
            syncCalls.incrementAndGet();
            return new ModelChatResult(200, "success",
                    new ModelChatData("should-not", "test", "test",
                            new ModelUsage(1, 1, 2), null, null, "stop"));
        };

        ReachAiAgentScopeChatModel model = new ReachAiAgentScopeChatModel(
                "model-1", syncClient, streamClient, objectMapper, text -> {
        });

        assertThrows(Exception.class, () ->
                model.stream(
                                List.of(Msg.builder().name("user").role(MsgRole.USER).textContent("q").build()),
                                List.of(),
                                null)
                        .collectList()
                        .block());
        assertEquals(0, syncCalls.get());
    }

    @Test
    void doesNotBatchFlushInternalContentWithoutPublicFinal() {
        ObjectMapper objectMapper = new ObjectMapper();
        List<String> publicDeltas = new CopyOnWriteArrayList<>();
        AtomicInteger streamCalls = new AtomicInteger();

        RuntimeModelStreamHttpClient streamClient = new RuntimeModelStreamHttpClient(objectMapper, "http://localhost") {
            @Override
            public void streamChatEvents(ModelChatRequest request, Consumer<ModelStreamEventDto> onEvent) {
                streamCalls.incrementAndGet();
                ModelStreamEventDto a = new ModelStreamEventDto();
                a.type = "content.delta";
                a.text = "internal-draft";
                onEvent.accept(a);
                ModelStreamEventDto done = new ModelStreamEventDto();
                done.type = "completed";
                done.finishReason = "stop";
                onEvent.accept(done);
            }
        };

        ReachAiAgentScopeChatModel model = new ReachAiAgentScopeChatModel(
                "model-1",
                request -> {
                    throw new IllegalStateException("sync should not run");
                },
                streamClient,
                objectMapper,
                publicDeltas::add);

        var messages = List.of(Msg.builder().name("user").role(MsgRole.USER).textContent("q").build());
        List<io.agentscope.core.model.ChatResponse> responses =
                model.stream(messages, List.of(), null).collectList().block();
        assertTrue(publicDeltas.isEmpty(), "INTERNAL content must never batch-flush as public delta");
        assertFalse(model.didStreamContent());
        assertEquals(1, streamCalls.get());
    }

    @Test
    void publicFinalPublishesEachDeltaBeforeCompleted() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        List<String> publicDeltas = new CopyOnWriteArrayList<>();
        List<Long> publicDeltaAts = new CopyOnWriteArrayList<>();
        AtomicReference<Long> completedAt = new AtomicReference<>();

        RuntimeModelStreamHttpClient streamClient = new RuntimeModelStreamHttpClient(objectMapper, "http://localhost") {
            @Override
            public void streamChatEvents(ModelChatRequest request, Consumer<ModelStreamEventDto> onEvent) {
                try {
                    ModelStreamEventDto a = new ModelStreamEventDto();
                    a.type = "content.delta";
                    a.text = "Hel";
                    onEvent.accept(a);
                    Thread.sleep(120);
                    ModelStreamEventDto b = new ModelStreamEventDto();
                    b.type = "content.delta";
                    b.text = "lo";
                    onEvent.accept(b);
                    Thread.sleep(120);
                    ModelStreamEventDto reason = new ModelStreamEventDto();
                    reason.type = "reasoning.delta";
                    reason.text = "secret-chain";
                    onEvent.accept(reason);
                    ModelStreamEventDto done = new ModelStreamEventDto();
                    done.type = "completed";
                    done.finishReason = "stop";
                    completedAt.set(System.nanoTime());
                    onEvent.accept(done);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            }
        };

        SupervisorAnswerPhase phase = new SupervisorAnswerPhase();
        phase.enterPublicFinal();
        ReachAiAgentScopeChatModel model = new ReachAiAgentScopeChatModel(
                "model-1",
                request -> {
                    throw new IllegalStateException("sync should not run");
                },
                streamClient,
                objectMapper,
                text -> {
                    publicDeltas.add(text);
                    publicDeltaAts.add(System.nanoTime());
                },
                phase);

        var messages = List.of(Msg.builder().name("user").role(MsgRole.USER).textContent("q").build());
        List<io.agentscope.core.model.ChatResponse> responses =
                model.stream(messages, List.of(), null).collectList().block();

        assertEquals(List.of("Hel", "lo"), publicDeltas);
        assertTrue(model.didStreamContent());
        assertEquals(2, model.publicContentDeltaCount());
        assertFalse(model.usedSyncFallback());
        assertEquals(Boolean.TRUE, model.safeStreamMetadata().get("tokenStreaming"));
        assertEquals("token_stream", model.safeStreamMetadata().get("streamMode"));
        assertFalse(publicDeltas.contains("secret-chain"));
        List<TextBlock> textBlocks = responses.stream()
                .flatMap(response -> response.getContent().stream())
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .toList();
        assertEquals(1, textBlocks.size(), "stream deltas must not become AgentScope TextBlocks");
        assertEquals("Hello", textBlocks.get(0).getText());
        assertEquals("Hello", textBlocks.stream().map(TextBlock::getText).reduce("", String::concat));
        assertNotNull(completedAt.get());
        assertFalse(publicDeltaAts.isEmpty());
        assertTrue(publicDeltaAts.get(0) < completedAt.get(),
                "first public delta must arrive before completed");
    }

    @Test
    void syncFallbackMarksTokenStreamingFalse() {
        ObjectMapper objectMapper = new ObjectMapper();
        List<String> publicDeltas = new CopyOnWriteArrayList<>();
        RuntimeModelStreamHttpClient streamClient = new RuntimeModelStreamHttpClient(objectMapper, "http://localhost") {
            @Override
            public void streamChatEvents(ModelChatRequest request, Consumer<ModelStreamEventDto> onEvent) {
                throw new IllegalStateException("upstream unavailable");
            }
        };
        SupervisorAnswerPhase phase = new SupervisorAnswerPhase();
        phase.enterPublicFinal();
        ReachAiAgentScopeChatModel model = new ReachAiAgentScopeChatModel(
                "model-1",
                request -> new ModelChatResult(200, "success",
                        new ModelChatData("整段答案", "test", "test",
                                new ModelUsage(1, 1, 2), null, null, "stop")),
                streamClient,
                objectMapper,
                publicDeltas::add,
                phase);

        model.stream(
                        List.of(Msg.builder().name("user").role(MsgRole.USER).textContent("q").build()),
                        List.of(),
                        null)
                .collectList()
                .block();

        assertEquals(List.of("整段答案"), publicDeltas);
        assertTrue(model.didStreamContent());
        assertTrue(model.usedSyncFallback());
        assertEquals("sync_fallback", model.safeStreamMetadata().get("streamMode"));
        assertEquals(Boolean.FALSE, model.safeStreamMetadata().get("tokenStreaming"));
        assertEquals(1, model.publicContentDeltaCount());
    }

    @Test
    void cancelActiveStreamStopsFurtherPublicDeltasAndSkipsSyncFallback() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SupervisorAnswerPhase phase = new SupervisorAnswerPhase();
        phase.enterPublicFinal();
        CountDownLatch firstDelta = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        AtomicInteger syncCalls = new AtomicInteger();
        RuntimeModelStreamHttpClient streamClient = new RuntimeModelStreamHttpClient(objectMapper, "http://localhost") {
            @Override
            public void streamChatEvents(ModelChatRequest request, Consumer<ModelStreamEventDto> onEvent,
                                         ModelStreamSubscription subscription) {
                ModelStreamEventDto d1 = new ModelStreamEventDto();
                d1.type = "content.delta";
                d1.text = "Hel";
                onEvent.accept(d1);
                firstDelta.countDown();
                try {
                    hold.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (subscription != null && subscription.isCancelled()) {
                    return;
                }
                ModelStreamEventDto d2 = new ModelStreamEventDto();
                d2.type = "content.delta";
                d2.text = "lo";
                onEvent.accept(d2);
            }
        };
        List<String> publicDeltas = new ArrayList<>();
        ReachAiAgentScopeChatModel model = new ReachAiAgentScopeChatModel(
                "model-1",
                request -> {
                    syncCalls.incrementAndGet();
                    throw new IllegalStateException("sync fallback must not run after cancel");
                },
                streamClient,
                objectMapper,
                publicDeltas::add,
                phase);

        var messages = List.of(Msg.builder().name("user").role(MsgRole.USER).textContent("q").build());
        var future = model.stream(messages, List.of(), null).collectList().toFuture();
        assertTrue(firstDelta.await(3, TimeUnit.SECONDS));
        model.cancelActiveStream();
        hold.countDown();
        List<?> responses = future.get(5, TimeUnit.SECONDS);
        assertEquals(List.of("Hel"), publicDeltas);
        assertEquals(0, syncCalls.get());
        assertTrue(phase.get() == SupervisorAnswerPhase.Phase.CANCELLED);
        assertNotNull(responses);
    }

    @Test
    void publicFinalToolCallIsProtocolError() {
        ObjectMapper objectMapper = new ObjectMapper();
        SupervisorAnswerPhase phase = new SupervisorAnswerPhase();
        phase.enterPublicFinal();
        RuntimeModelStreamHttpClient streamClient = new RuntimeModelStreamHttpClient(objectMapper, "http://localhost") {
            @Override
            public void streamChatEvents(ModelChatRequest request, Consumer<ModelStreamEventDto> onEvent) {
                ModelStreamEventDto tool = new ModelStreamEventDto();
                tool.type = "tool_call.delta";
                tool.toolCall = new ToolCallDeltaDto();
                tool.toolCall.index = 0;
                tool.toolCall.id = "c1";
                tool.toolCall.name = "lookup";
                tool.toolCall.arguments = "{}";
                onEvent.accept(tool);
            }
        };
        ReachAiAgentScopeChatModel model = new ReachAiAgentScopeChatModel(
                "model-1",
                request -> {
                    throw new IllegalStateException("sync should not run");
                },
                streamClient,
                objectMapper,
                text -> {
                },
                phase);

        Exception error = assertThrows(Exception.class, () ->
                model.stream(
                                List.of(Msg.builder().name("user").role(MsgRole.USER).textContent("q").build()),
                                List.of(),
                                null)
                        .collectList()
                        .block());
        ModelStreamFailure failure = ModelStreamFailure.findIn(error);
        assertEquals(ReachAiAgentScopeChatModel.PUBLIC_FINAL_TOOL_CALL_CODE, failure.code());
    }

    private static ModelStreamFailure emptyFinishFailure(String finishReason) {
        ObjectMapper objectMapper = new ObjectMapper();
        RuntimeModelStreamHttpClient streamClient = new RuntimeModelStreamHttpClient(objectMapper, "http://localhost") {
            @Override
            public void streamChatEvents(ModelChatRequest request, Consumer<ModelStreamEventDto> onEvent) {
                ModelStreamEventDto reasoning = new ModelStreamEventDto();
                reasoning.type = "reasoning.delta";
                reasoning.text = "x";
                onEvent.accept(reasoning);
                ModelStreamEventDto done = new ModelStreamEventDto();
                done.type = "completed";
                done.finishReason = finishReason;
                onEvent.accept(done);
            }
        };
        ReachAiAgentScopeChatModel model = new ReachAiAgentScopeChatModel(
                "model-1",
                request -> {
                    throw new IllegalStateException("sync should not run");
                },
                streamClient,
                objectMapper,
                text -> {
                });
        Exception error = assertThrows(Exception.class, () ->
                model.stream(
                                List.of(Msg.builder().name("user").role(MsgRole.USER).textContent("q").build()),
                                List.of(),
                                null)
                        .collectList()
                        .block());
        return ModelStreamFailure.findIn(error);
    }
}
