package com.enterprise.ai.model.runtime;

import com.enterprise.ai.model.service.ChatResponse;
import com.enterprise.ai.model.service.ModelStreamEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleStreamParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesReasoningContentAndStopCompleted() throws Exception {
        OpenAiCompatibleStreamParser parser = new OpenAiCompatibleStreamParser(objectMapper);
        List<ModelStreamEvent> events = new ArrayList<>();
        events.addAll(parser.acceptChunkJson("""
                {"choices":[{"delta":{"reasoning_content":"think"},"finish_reason":null}]}
                """));
        events.addAll(parser.acceptChunkJson("""
                {"choices":[{"delta":{"content":"answer"},"finish_reason":"stop"}]}
                """));

        assertEquals(ModelStreamEvent.REASONING_DELTA, events.get(0).getType());
        assertEquals("think", events.get(0).getText());
        assertEquals(ModelStreamEvent.CONTENT_DELTA, events.get(1).getType());
        assertEquals("answer", events.get(1).getText());
        assertEquals("stop", parser.finishReason());
        // 解析器本身不持久化 reasoning 到别的字段
        assertNull(events.get(0).getUsage());
    }

    @Test
    void parsesLengthFinishReasonWithReasoningOnly() throws Exception {
        OpenAiCompatibleStreamParser parser = new OpenAiCompatibleStreamParser(objectMapper);
        List<ModelStreamEvent> events = parser.acceptChunkJson("""
                {"choices":[{"delta":{"reasoning_content":"long-chain"},"finish_reason":"length"}]}
                """);
        assertEquals(1, events.size());
        assertEquals(ModelStreamEvent.REASONING_DELTA, events.get(0).getType());
        assertEquals("length", parser.finishReason());
    }

    @Test
    void parsesContentFilterFinishReason() throws Exception {
        OpenAiCompatibleStreamParser parser = new OpenAiCompatibleStreamParser(objectMapper);
        parser.acceptChunkJson("""
                {"choices":[{"delta":{},"finish_reason":"content_filter"}]}
                """);
        assertEquals("content_filter", parser.finishReason());
    }

    @Test
    void parsesInsufficientSystemResourceFinishReason() throws Exception {
        OpenAiCompatibleStreamParser parser = new OpenAiCompatibleStreamParser(objectMapper);
        parser.acceptChunkJson("""
                {"choices":[{"delta":{},"finish_reason":"insufficient_system_resource"}]}
                """);
        assertEquals("insufficient_system_resource", parser.finishReason());
    }

    @Test
    void parsesUsageWithReasoningTokens() throws Exception {
        OpenAiCompatibleStreamParser parser = new OpenAiCompatibleStreamParser(objectMapper);
        List<ModelStreamEvent> events = parser.acceptChunkJson("""
                {"choices":[{"delta":{},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":10,"completion_tokens":100,"total_tokens":110,
                          "completion_tokens_details":{"reasoning_tokens":80}}}
                """);
        ModelStreamEvent usageEvent = events.stream()
                .filter(e -> ModelStreamEvent.USAGE.equals(e.getType()))
                .findFirst()
                .orElse(null);
        assertNotNull(usageEvent);
        assertNotNull(usageEvent.getUsage());
        assertEquals(10, usageEvent.getUsage().getPromptTokens());
        assertEquals(100, usageEvent.getUsage().getCompletionTokens());
        assertEquals(110, usageEvent.getUsage().getTotalTokens());
        assertEquals(80, usageEvent.getUsage().getReasoningTokens());
    }

    @Test
    void aggregatesToolCallDeltas() throws Exception {
        OpenAiCompatibleStreamParser parser = new OpenAiCompatibleStreamParser(objectMapper);
        List<ModelStreamEvent> events = new ArrayList<>();
        String first = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"plan\",\"arguments\":\"{\\\"a\\\"\"}}]},\"finish_reason\":null}]}";
        String second = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\":1}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}]}";
        events.addAll(parser.acceptChunkJson(first));
        events.addAll(parser.acceptChunkJson(second));
        List<ModelStreamEvent> toolEvents = events.stream()
                .filter(e -> ModelStreamEvent.TOOL_CALL_DELTA.equals(e.getType()))
                .toList();
        assertFalse(toolEvents.isEmpty());
        assertEquals("tool_calls", parser.finishReason());
        assertTrue(toolEvents.stream().anyMatch(e -> e.getToolCall() != null && "plan".equals(e.getToolCall().getName())));
    }

    @Test
    void doesNotExposeReasoningInUsageOrCompletedFields() throws Exception {
        OpenAiCompatibleStreamParser parser = new OpenAiCompatibleStreamParser(objectMapper);
        List<ModelStreamEvent> events = parser.acceptChunkJson("""
                {"choices":[{"delta":{"reasoning_content":"secret-chain"},"finish_reason":"length"}],
                 "usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}
                """);
        String joined = objectMapper.writeValueAsString(events);
        // usage/completed 结构不应额外挂载 reasoning 正文以外的泄露通道；
        // delta 文本本身是流事件载荷，由上游消费方负责不落库/不回传。
        assertTrue(joined.contains("reasoning.delta"));
        assertFalse(joined.contains("reasoningContent"));
    }

    @Test
    void startsWithoutFinishReasonAndMissingChoicesEmitNoEvents() throws Exception {
        OpenAiCompatibleStreamParser parser = new OpenAiCompatibleStreamParser(objectMapper);

        assertNull(parser.finishReason());
        List<ModelStreamEvent> events = parser.acceptChunkJson("{}");

        assertEquals(0, events.size());
        assertNull(parser.finishReason());
    }

    @Test
    void emitsReasoningBeforeContentAndMirrorsSameEventsToSink() throws Exception {
        OpenAiCompatibleStreamParser parser = new OpenAiCompatibleStreamParser(objectMapper);
        List<ModelStreamEvent> sink = new ArrayList<>();
        JsonNode root = objectMapper.readTree("""
                {"choices":[{"delta":{"reasoning_content":"think","content":"answer"},"finish_reason":null}]}
                """);

        List<ModelStreamEvent> emitted = parser.acceptChunk(root, sink::add);

        assertEquals(2, emitted.size());
        assertEquals(ModelStreamEvent.REASONING_DELTA, emitted.get(0).getType());
        assertEquals("think", emitted.get(0).getText());
        assertEquals(ModelStreamEvent.CONTENT_DELTA, emitted.get(1).getType());
        assertEquals("answer", emitted.get(1).getText());

        assertEquals(2, sink.size());
        assertSame(emitted.get(0), sink.get(0));
        assertSame(emitted.get(1), sink.get(1));
        assertEquals(ModelStreamEvent.REASONING_DELTA, sink.get(0).getType());
        assertEquals(ModelStreamEvent.CONTENT_DELTA, sink.get(1).getType());
    }

    @Test
    void ignoresEmptyReasoningAndContentDeltas() throws Exception {
        OpenAiCompatibleStreamParser parser = new OpenAiCompatibleStreamParser(objectMapper);

        List<ModelStreamEvent> events = parser.acceptChunkJson(
                "{\"choices\":[{\"delta\":{\"reasoning_content\":\"\",\"content\":\"\"},\"finish_reason\":null}]}");

        assertEquals(0, events.size());
    }

    @Test
    void readsOnlyFirstChoice() throws Exception {
        OpenAiCompatibleStreamParser parser = new OpenAiCompatibleStreamParser(objectMapper);

        List<ModelStreamEvent> events = parser.acceptChunkJson("""
                {"choices":[
                  {"delta":{"content":"first"},"finish_reason":null},
                  {"delta":{"content":"second"},"finish_reason":null}
                ]}
                """);

        assertEquals(1, events.size());
        assertEquals(ModelStreamEvent.CONTENT_DELTA, events.get(0).getType());
        assertEquals("first", events.get(0).getText());
    }

    @Test
    void trimsFinishReasonAndBlankDoesNotOverwritePreviousValue() throws Exception {
        OpenAiCompatibleStreamParser parser = new OpenAiCompatibleStreamParser(objectMapper);

        parser.acceptChunkJson("{\"choices\":[{\"delta\":{},\"finish_reason\":\" stop \"}]}");
        assertEquals("stop", parser.finishReason());

        parser.acceptChunkJson("{\"choices\":[{\"delta\":{},\"finish_reason\":\"   \"}]}");
        assertEquals("stop", parser.finishReason());
    }

    @Test
    void usageDefaultsMissingTokenCountsToZeroAndReasoningToNull() throws Exception {
        OpenAiCompatibleStreamParser parser = new OpenAiCompatibleStreamParser(objectMapper);

        List<ModelStreamEvent> events = parser.acceptChunkJson("{\"choices\":[],\"usage\":{}}");

        assertEquals(1, events.size());
        assertEquals(ModelStreamEvent.USAGE, events.get(0).getType());
        ChatResponse.Usage usage = events.get(0).getUsage();
        assertNotNull(usage);
        assertEquals(0, usage.getPromptTokens());
        assertEquals(0, usage.getCompletionTokens());
        assertEquals(0, usage.getTotalTokens());
        assertNull(usage.getReasoningTokens());
    }

    @Test
    void usesTopLevelReasoningTokensWhenDetailsAreMissing() throws Exception {
        OpenAiCompatibleStreamParser parser = new OpenAiCompatibleStreamParser(objectMapper);

        List<ModelStreamEvent> events = parser.acceptChunkJson("""
                {"choices":[],"usage":{"prompt_tokens":5,"completion_tokens":6,"total_tokens":11,"reasoning_tokens":7}}
                """);

        assertEquals(1, events.size());
        assertEquals(ModelStreamEvent.USAGE, events.get(0).getType());
        ChatResponse.Usage usage = events.get(0).getUsage();
        assertNotNull(usage);
        assertEquals(5, usage.getPromptTokens());
        assertEquals(6, usage.getCompletionTokens());
        assertEquals(11, usage.getTotalTokens());
        assertEquals(7, usage.getReasoningTokens());
    }

    @Test
    void prefersNestedReasoningTokensOverTopLevelFallback() throws Exception {
        OpenAiCompatibleStreamParser parser = new OpenAiCompatibleStreamParser(objectMapper);

        List<ModelStreamEvent> events = parser.acceptChunkJson("""
                {"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3,
                 "reasoning_tokens":99,"completion_tokens_details":{"reasoning_tokens":42}}}
                """);

        assertEquals(1, events.size());
        assertEquals(ModelStreamEvent.USAGE, events.get(0).getType());
        assertNotNull(events.get(0).getUsage());
        assertEquals(42, events.get(0).getUsage().getReasoningTokens());
    }

    @Test
    void ignoresNullUsageWithoutSuppressingContentEvent() throws Exception {
        OpenAiCompatibleStreamParser parser = new OpenAiCompatibleStreamParser(objectMapper);

        List<ModelStreamEvent> events = parser.acceptChunkJson("""
                {"choices":[{"delta":{"content":"hi"},"finish_reason":null}],"usage":null}
                """);

        assertEquals(1, events.size());
        assertEquals(ModelStreamEvent.CONTENT_DELTA, events.get(0).getType());
        assertEquals("hi", events.get(0).getText());
    }

    @Test
    void malformedJsonFailsWithoutMutatingFinishReason() throws Exception {
        OpenAiCompatibleStreamParser parser = new OpenAiCompatibleStreamParser(objectMapper);

        parser.acceptChunkJson("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}");
        assertEquals("stop", parser.finishReason());

        assertThrows(JsonProcessingException.class, () -> parser.acceptChunkJson("{bad"));

        assertEquals("stop", parser.finishReason());
    }
}
