package com.enterprise.ai.model.runtime;

import com.enterprise.ai.model.service.ModelStreamEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiToolCallStreamAggregatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void aggregatesFragmentedToolCallNameAndArgumentsByIndex() throws Exception {
        OpenAiToolCallStreamAggregator aggregator = new OpenAiToolCallStreamAggregator(objectMapper);

        aggregator.accept(objectMapper.readTree("""
                {"index":0,"id":"call_1","type":"function","function":{"name":"look","arguments":""}}
                """));
        aggregator.accept(objectMapper.readTree("""
                {"index":0,"function":{"name":"up","arguments":"{\\"q\\":"}}
                """));
        aggregator.accept(objectMapper.readTree("""
                {"index":0,"function":{"arguments":"\\"demo\\"}"}}
                """));

        assertTrue(aggregator.hasToolCalls());
        var assembled = aggregator.assembledToolCalls();
        assertEquals(1, assembled.size());
        assertEquals("call_1", assembled.get(0).path("id").asText());
        assertEquals("lookup", assembled.get(0).path("function").path("name").asText());
        assertEquals("{\"q\":\"demo\"}", assembled.get(0).path("function").path("arguments").asText());
    }

    @Test
    void emitsToolCallDeltaFragments() throws Exception {
        OpenAiToolCallStreamAggregator aggregator = new OpenAiToolCallStreamAggregator(objectMapper);
        ModelStreamEvent.ToolCallDelta delta = aggregator.accept(objectMapper.readTree("""
                {"index":1,"id":"c2","function":{"name":"plan","arguments":"{"}}
                """));
        assertEquals(1, delta.getIndex());
        assertEquals("c2", delta.getId());
        assertEquals("plan", delta.getName());
        assertEquals("{", delta.getArguments());
    }
}
