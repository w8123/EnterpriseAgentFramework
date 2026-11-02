package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SupervisorKnowledgeTerminalAnswerTest {
    private static final String ANSWER = "当前未找到可用资料。";
    static final String REQUIRED_GRAPH = """
            {"nodes":[{"id":"kr","type":"KNOWLEDGE_RETRIEVAL","config":{"evidencePolicy":"REQUIRED"}}]}
            """;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static Map<String, Object> trace(String id, String type) {
        return Map.of("nodeId", id, "nodeType", type, "status", "SUCCESS");
    }
    private static Map<String, Object> knowledge() {
        return Map.of("nodeId", "kr", "nodeType", "KNOWLEDGE_RETRIEVAL", "status", "SUCCESS", "nextNodeId", "none");
    }
    static RuntimeGraphSpecExecutionResult noEvidence() {
        return result(List.of(trace("input", "USER_INPUT"), knowledge(), trace("none", "ANSWER")),
                Map.of("outcome", "NO_EVIDENCE", "empty", true, "hits", List.of()));
    }
    private static RuntimeGraphSpecExecutionResult result(List<Map<String, Object>> traces, Map<String, Object> output) {
        return new RuntimeGraphSpecExecutionResult(true, "RUNTIME_GRAPH_EXECUTED", ANSWER,
                "none", "ANSWER", List.of(), Map.of("workflowNodeTraces", traces), Map.of("nodeOutput.kr", output));
    }
    private static String select(RuntimeGraphSpecExecutionResult result, boolean readOnly, String graph) {
        return SupervisorKnowledgeTerminalAnswer.from(result, readOnly, graph, MAPPER);
    }
    @Test void acceptsOnlyTheVerifiedDeterministicNoEvidencePath() {
        var empty = Map.<String, Object>of("outcome", "NO_EVIDENCE", "empty", true, "hits", List.of());
        assertEquals(ANSWER, select(noEvidence(), true, REQUIRED_GRAPH));
        assertNull(select(noEvidence(), false, REQUIRED_GRAPH));
        assertNull(select(noEvidence(), true, REQUIRED_GRAPH.replace("REQUIRED", "OPTIONAL")));
        assertNull(select(result(List.of(knowledge(), trace("none", "ANSWER")), Map.of("outcome", "HIT", "empty", false, "hits", List.of(1))), true, REQUIRED_GRAPH));
        assertNull(select(result(List.of(trace("write", "TOOL"), knowledge(), trace("none", "ANSWER")), empty), true, REQUIRED_GRAPH));
        assertNull(select(result(List.of(knowledge(), trace("llm", "LLM"), trace("none", "ANSWER")), empty), true, REQUIRED_GRAPH));
    }

    @Test void unavailableRequiresAnExactReadOnlyRetrievalFailure() {
        var trace = Map.<String, Object>of("nodeId", "kr", "nodeType", "KNOWLEDGE_RETRIEVAL", "status", "FAILED");
        var failed = new RuntimeGraphSpecExecutionResult(false, "RUNTIME_KNOWLEDGE_FAILED", "private upstream error",
                "kr", "KNOWLEDGE_RETRIEVAL", List.of(), Map.of("workflowNodeTraces", List.of(trace)));
        assertEquals("知识检索暂时不可用，请稍后重试。", SupervisorKnowledgeTerminalAnswer.unavailableFrom(failed, true));
        assertNull(SupervisorKnowledgeTerminalAnswer.unavailableFrom(failed, false));
        var wrong = new RuntimeGraphSpecExecutionResult(false, "RUNTIME_KNOWLEDGE_QUERY_REQUIRED", "bad query",
                "kr", "KNOWLEDGE_RETRIEVAL", List.of(), Map.of("workflowNodeTraces", List.of(trace)));
        assertNull(SupervisorKnowledgeTerminalAnswer.unavailableFrom(wrong, true));
        var sideEffect = new RuntimeGraphSpecExecutionResult(false, "RUNTIME_KNOWLEDGE_FAILED", "error",
                "kr", "KNOWLEDGE_RETRIEVAL", List.of(), Map.of("workflowNodeTraces", List.of(trace("write", "TOOL"), trace)));
        assertNull(SupervisorKnowledgeTerminalAnswer.unavailableFrom(sideEffect, true));
    }
}
