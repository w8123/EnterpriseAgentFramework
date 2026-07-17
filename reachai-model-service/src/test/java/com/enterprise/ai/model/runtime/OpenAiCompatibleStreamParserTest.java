package com.enterprise.ai.model.runtime;

import com.enterprise.ai.model.service.ModelStreamEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
}
