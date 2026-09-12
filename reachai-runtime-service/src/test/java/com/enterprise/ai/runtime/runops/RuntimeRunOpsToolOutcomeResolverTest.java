package com.enterprise.ai.runtime.runops;

import com.enterprise.ai.runtime.trace.RuntimeTraceRecords;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeRunOpsToolOutcomeResolverTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final LocalDateTime now = LocalDateTime.now();

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "FAILED", "CANCELLED", "TIMED_OUT", "BUSINESS_TERMINAL", "WAITING_USER"})
    void resolvesOnlyTheExactInvocationAndPreservesObservation(String status) throws Exception {
        var original = tool("RUNTIME_GRAPH_INTERACTION_WAITING", "interaction-1", false);
        var result = resolver(List.of(
                parent("unrelated", "SUCCESS", "trace", "workflow", 7, "lookup"),
                child("unrelated-node", "unrelated", "interaction-other", "trace", "workflow", 7),
                parent("parent", status, "trace", "workflow", 7, "lookup"),
                child("node", "parent", "interaction-1", "trace", "workflow", 7)))
                .resolve(original);
        assertEquals(status, result.status());
        assertEquals("parent", result.sourceSpanId());
        assertFalse(original.success());
    }

    @ParameterizedTest
    @ValueSource(strings = {"interaction", "trace", "childVersion", "parentVersion", "workflow", "toolName", "missingParent", "duplicateChild", "duplicateParent", "unfinishedTerminal"})
    void incompleteOrAmbiguousEvidenceCannotClaimFailureOrSuccess(String mismatch) throws Exception {
        var parent = parent("parent", "SUCCESS", "trace", "workflow",
                mismatch.equals("parentVersion") ? 8 : 7, mismatch.equals("toolName") ? "other" : "lookup");
        var child = child("node", mismatch.equals("missingParent") ? "missing" : "parent",
                mismatch.equals("interaction") ? "other" : "interaction-1",
                mismatch.equals("trace") ? "other-trace" : "trace",
                mismatch.equals("workflow") ? "other-workflow" : "workflow",
                mismatch.equals("childVersion") ? 8 : 7);
        var spans = new java.util.ArrayList<>(List.of(parent, child));
        if (mismatch.equals("duplicateChild")) spans.add(child);
        if (mismatch.equals("duplicateParent")) spans.add(parent);
        if (mismatch.equals("unfinishedTerminal")) {
            spans.set(0, new RuntimeTraceRecords.Span(parent.id(), parent.traceId(), parent.agentName(),
                    parent.spanId(), parent.parentSpanId(), parent.spanType(), parent.runtimeType(), parent.nodeId(),
                    parent.toolName(), "SUCCESS", null, null, parent.metadataJson(), null, null, 0, 0, now, null, now));
        }
        var result = resolver(spans).resolve(tool("RUNTIME_GRAPH_INTERACTION_WAITING", "interaction-1", false));
        assertEquals("UNKNOWN", result.status());
        assertNull(result.sourceSpanId());
    }

    @Test
    void laterSuccessNeverHidesARealFailedCall() throws Exception {
        var result = resolver(List.of(parent("parent", "SUCCESS", "trace", "workflow", 7, "lookup"),
                child("node", "parent", "interaction-1", "trace", "workflow", 7)))
                .resolve(tool("CAPABILITY_CALL_FAILED", "interaction-1", false));
        assertEquals("FAILED", result.status());
        assertNull(result.sourceSpanId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"code\":\"WAITING_USER\"}", "{\"code\":\"RUNTIME_INTERACTION_WAITING\",\"workflowId\":\"workflow\",\"workflowVersionId\":7,\"summary\":{}}"})
    void missingMetadataDoesNotInventACorrelatedSpan(String json) {
        var original = new RuntimeTraceRecords.ToolCall(1L, "trace", "lookup", "agent", "session", "user",
                null, "project", false, "{}", "", null, 1, 0, json, now);
        var result = resolver(List.of()).resolve(original);
        assertEquals(json.equals("{}") ? "FAILED" : "UNKNOWN", result.status());
        assertNull(result.sourceSpanId());
    }

    private RuntimeRunOpsToolOutcomeResolver resolver(List<RuntimeTraceRecords.Span> spans) {
        return new RuntimeRunOpsToolOutcomeResolver(mapper, spans);
    }

    private RuntimeTraceRecords.ToolCall tool(String code, String interaction, boolean success) throws Exception {
        String json = mapper.writeValueAsString(Map.of("workflowId", "workflow", "workflowVersionId", 7,
                "code", code, "summary", Map.of("workflowNodeTraces", List.of(Map.of("nodeId", "input",
                        "interactionId", interaction, "status", "WAITING_USER")))));
        return new RuntimeTraceRecords.ToolCall(1L, "trace", "lookup", "agent", "session", "user", null,
                "project", success, "{}", "", null, 1, 0, json, now);
    }

    private RuntimeTraceRecords.Span parent(String id, String status, String trace, String workflow, int version, String tool) throws Exception {
        return span(id, null, "WORKFLOW_TOOL", status, trace, workflow, tool,
                Map.of("workflowId", workflow, "workflowVersionId", version));
    }

    private RuntimeTraceRecords.Span child(String id, String parent, String interaction, String trace, String workflow, int version) throws Exception {
        return span(id, parent, "WORKFLOW_NODE", "SUCCESS", trace, "input", null,
                Map.of("workflowId", workflow, "workflowVersionId", version, "interactionId", interaction));
    }

    private RuntimeTraceRecords.Span span(String id, String parent, String type, String status, String trace,
                                         String node, String tool, Map<String, Object> metadata) throws Exception {
        return new RuntimeTraceRecords.Span(1L, trace, "agent", id, parent, type, "LANGGRAPH4J", node, tool,
                status, null, null, mapper.writeValueAsString(metadata), null, null, 1, 0, now,
                status.equals("WAITING_USER") ? null : now.plusSeconds(1), now);
    }
}
