package com.enterprise.ai.model.runtime;

import com.enterprise.ai.model.service.ModelStreamEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void startsEmptyAndAssemblesEmptyArray() {
        OpenAiToolCallStreamAggregator aggregator = new OpenAiToolCallStreamAggregator(objectMapper);

        assertFalse(aggregator.hasToolCalls());
        JsonNode assembled = aggregator.assembledToolCalls();
        assertNotNull(assembled);
        assertTrue(assembled.isArray());
        assertEquals(0, assembled.size());
    }

    @Test
    void ignoresJavaNullAndJsonNullWithoutCreatingState() {
        OpenAiToolCallStreamAggregator aggregator = new OpenAiToolCallStreamAggregator(objectMapper);

        assertNull(aggregator.accept(null));
        assertNull(aggregator.accept(objectMapper.nullNode()));

        assertFalse(aggregator.hasToolCalls());
        assertEquals(0, aggregator.assembledToolCalls().size());
    }

    @Test
    void defaultsMissingTypeToFunctionInDeltaAndAssembly() throws Exception {
        OpenAiToolCallStreamAggregator aggregator = new OpenAiToolCallStreamAggregator(objectMapper);

        ModelStreamEvent.ToolCallDelta delta = aggregator.accept(objectMapper.readTree("""
                {"index":0,"id":"c3","function":{"name":"do","arguments":""}}
                """));

        assertEquals("function", delta.getType());

        JsonNode assembled = aggregator.assembledToolCalls();
        assertEquals(1, assembled.size());
        assertEquals("function", assembled.get(0).path("type").asText());
    }

    @Test
    void emitsOnlyCurrentFragmentsWhileAssemblyAccumulatesAllFragments() throws Exception {
        OpenAiToolCallStreamAggregator aggregator = new OpenAiToolCallStreamAggregator(objectMapper);

        ModelStreamEvent.ToolCallDelta first = aggregator.accept(objectMapper.readTree("""
                {"index":0,"id":"call_9","type":"function","function":{"name":"get","arguments":"{\\"a\\":"}}
                """));
        ModelStreamEvent.ToolCallDelta second = aggregator.accept(objectMapper.readTree("""
                {"index":0,"function":{"name":"User","arguments":"1}"}}
                """));

        assertNotNull(first);
        assertEquals("User", second.getName());
        assertEquals("1}", second.getArguments());
        assertEquals("call_9", second.getId());

        JsonNode assembled = aggregator.assembledToolCalls();
        assertEquals(1, assembled.size());
        assertEquals("getUser", assembled.get(0).path("function").path("name").asText());
        assertEquals("{\"a\":1}", assembled.get(0).path("function").path("arguments").asText());
        assertEquals("call_9", assembled.get(0).path("id").asText());
    }

    @Test
    void keepsInterleavedIndicesIndependent() throws Exception {
        OpenAiToolCallStreamAggregator aggregator = new OpenAiToolCallStreamAggregator(objectMapper);

        aggregator.accept(objectMapper.readTree("{\"index\":0,\"id\":\"c0\",\"function\":{\"name\":\"a\",\"arguments\":\"left-\"}}"));
        aggregator.accept(objectMapper.readTree("{\"index\":1,\"id\":\"c1\",\"function\":{\"name\":\"x\",\"arguments\":\"right-\"}}"));
        aggregator.accept(objectMapper.readTree("{\"index\":0,\"function\":{\"name\":\"b\",\"arguments\":\"0\"}}"));
        aggregator.accept(objectMapper.readTree("{\"index\":1,\"function\":{\"name\":\"y\",\"arguments\":\"1\"}}"));

        JsonNode assembled = aggregator.assembledToolCalls();
        assertEquals(2, assembled.size());

        JsonNode node0 = findById(assembled, "c0");
        JsonNode node1 = findById(assembled, "c1");
        assertNotNull(node0);
        assertNotNull(node1);
        assertEquals("ab", node0.path("function").path("name").asText());
        assertEquals("xy", node1.path("function").path("name").asText());
        assertEquals("left-0", node0.path("function").path("arguments").asText());
        assertEquals("right-1", node1.path("function").path("arguments").asText());
    }

    @Test
    void reflectsLaterIdAndTypeUpdatesWithoutLosingFunctionFragments() throws Exception {
        OpenAiToolCallStreamAggregator aggregator = new OpenAiToolCallStreamAggregator(objectMapper);

        aggregator.accept(objectMapper.readTree("{\"index\":0,\"id\":\"old_id\",\"type\":\"function\",\"function\":{\"name\":\"get\",\"arguments\":\"x\"}}"));
        aggregator.accept(objectMapper.readTree("{\"index\":0,\"id\":\"new_id\",\"type\":\"code_interpreter\"}"));

        JsonNode assembled = aggregator.assembledToolCalls();
        assertEquals(1, assembled.size());
        assertEquals("new_id", assembled.get(0).path("id").asText());
        assertEquals("code_interpreter", assembled.get(0).path("type").asText());
        assertEquals("get", assembled.get(0).path("function").path("name").asText());
        assertEquals("x", assembled.get(0).path("function").path("arguments").asText());
    }

    @Test
    void assemblesDefaultEmptyNameAndObjectArgumentsWhenFragmentsAreMissing() throws Exception {
        OpenAiToolCallStreamAggregator aggregator = new OpenAiToolCallStreamAggregator(objectMapper);

        aggregator.accept(objectMapper.readTree("{\"index\":0,\"id\":\"c7\",\"type\":\"function\"}"));

        JsonNode assembled = aggregator.assembledToolCalls();
        assertEquals(1, assembled.size());
        assertEquals("", assembled.get(0).path("function").path("name").asText());
        assertEquals("{}", assembled.get(0).path("function").path("arguments").asText());
    }

    @Test
    void doesNotMutatePreviouslyReturnedDeltaWhenLaterFragmentsArrive() throws Exception {
        OpenAiToolCallStreamAggregator aggregator = new OpenAiToolCallStreamAggregator(objectMapper);

        ModelStreamEvent.ToolCallDelta first = aggregator.accept(objectMapper.readTree("""
                {"index":0,"id":"c8","type":"function","function":{"name":"get","arguments":"{\\"a\\":"}}
                """));
        aggregator.accept(objectMapper.readTree("""
                {"index":0,"function":{"name":"User","arguments":"1}"}}
                """));

        assertEquals(0, first.getIndex());
        assertEquals("c8", first.getId());
        assertEquals("function", first.getType());
        assertEquals("get", first.getName());
        assertEquals("{\"a\":", first.getArguments());
    }

    private static JsonNode findById(JsonNode array, String id) {
        for (JsonNode node : array) {
            if (id.equals(node.path("id").asText(null))) {
                return node;
            }
        }
        return null;
    }
}
