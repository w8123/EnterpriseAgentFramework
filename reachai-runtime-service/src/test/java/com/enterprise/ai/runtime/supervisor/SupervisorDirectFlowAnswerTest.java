package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupervisorDirectFlowAnswerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DIRECT = """
            {"nodes":[{"id":"t","type":"TOOL","config":{}},
                      {"id":"a","type":"ANSWER","config":{"directAnswer":true}},
                      {"id":"b","type":"ANSWER","config":{"answerConfig":{"directAnswer":false}}}]}
            """;

    private static RuntimeGraphSpecExecutionResult result(boolean success, String answer, String nodeId, String type) {
        return new RuntimeGraphSpecExecutionResult(success, "RUNTIME_GRAPH_EXECUTED", answer,
                nodeId, type, List.of(), Map.of(), Map.of());
    }

    @Test
    void deliversOnlyTheAnswerNodeThatDeclaresIt() {
        assertEquals("库存 12 件", SupervisorDirectFlowAnswer.from(result(true, "库存 12 件", "a", "ANSWER"), DIRECT, MAPPER));
        assertNull(SupervisorDirectFlowAnswer.from(result(true, "未找到", "b", "ANSWER"), DIRECT, MAPPER));
        assertNull(SupervisorDirectFlowAnswer.from(result(false, "失败", "a", "ANSWER"), DIRECT, MAPPER));
        assertNull(SupervisorDirectFlowAnswer.from(result(true, "x", "t", "TOOL"), DIRECT, MAPPER));
        assertNull(SupervisorDirectFlowAnswer.from(result(true, " ", "a", "ANSWER"), DIRECT, MAPPER));
        assertNull(SupervisorDirectFlowAnswer.from(
                result(true, "x".repeat(SupervisorDirectFlowAnswer.MAX_ANSWER_LENGTH + 1), "a", "ANSWER"), DIRECT, MAPPER));
        assertNull(SupervisorDirectFlowAnswer.from(result(true, "x", "a", "ANSWER"), "not json", MAPPER));
    }

    @Test
    void declaredNeedsAnAnswerNodeWithTheFlag() {
        assertTrue(SupervisorDirectFlowAnswer.declared(DIRECT, MAPPER));
        assertFalse(SupervisorDirectFlowAnswer.declared(DIRECT.replace("\"directAnswer\":true", "\"directAnswer\":false"), MAPPER));
        assertFalse(SupervisorDirectFlowAnswer.declared("{\"nodes\":[{\"id\":\"t\",\"type\":\"TOOL\",\"config\":{\"directAnswer\":true}}]}", MAPPER));
        assertFalse(SupervisorDirectFlowAnswer.declared("", MAPPER));
    }
}
