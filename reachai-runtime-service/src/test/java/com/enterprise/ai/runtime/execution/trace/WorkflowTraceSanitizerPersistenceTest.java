package com.enterprise.ai.runtime.execution.trace;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.runops.RuntimeGuardDecisionLogEntity;
import com.enterprise.ai.runtime.runops.RuntimeGuardDecisionLogMapper;
import com.enterprise.ai.runtime.runops.RuntimeRunEntity;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.runops.RuntimeRunMapper;
import com.enterprise.ai.runtime.supervisor.SupervisorExecutionTraceService;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogEntity;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogMapper;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanEntity;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WorkflowTraceSanitizerPersistenceTest {

    @Test
    void preservesSafeInteractionVariantWithoutPersistingCardData() {
        String businessValue = uuid();

        Map<String, Object> sanitized = WorkflowTraceSanitizer.sanitizeNodeTrace(
                Map.of(
                        "nodeId", "present-output",
                        "nodeType", "INTERACTION",
                        "status", "SUCCESS",
                        "interactionType", "PRESENT_OUTPUT",
                        "uiRequest", Map.of(
                                "component", "list_card",
                                "data", List.of(Map.of(
                                        "name", businessValue)))));

        assertEquals("PRESENT_OUTPUT", sanitized.get("interactionType"));
        assertFalse(sanitized.containsKey("uiRequest"));
        assertFalse(String.valueOf(sanitized).contains(businessValue));
    }

    @Test
    void preservesBusinessTerminalClassificationWithoutPersistingHandlerMessage() {
        String handlerMessage = uuid();

        Map<String, Object> sanitized = WorkflowTraceSanitizer.sanitizeNodeTrace(Map.of(
                "nodeId", "page-action",
                "nodeType", "PAGE_ACTION",
                "status", "BUSINESS_TERMINAL",
                "outcomeClass", "BUSINESS_TERMINAL",
                "businessOutcome", "NO_DATA",
                "traceSummary", Map.of(
                        "actionKey", "orders.search",
                        "status", "NO_DATA",
                        "outcomeClass", "BUSINESS_TERMINAL",
                        "businessOutcome", "NO_DATA",
                        "total", 0,
                        "empty", true,
                        "message", handlerMessage,
                        "rows", List.of(Map.of("secret", handlerMessage)))));

        assertEquals("BUSINESS_TERMINAL", sanitized.get("outcomeClass"));
        assertEquals("NO_DATA", sanitized.get("businessOutcome"));
        assertTrue(String.valueOf(sanitized).contains("NO_DATA"));
        assertFalse(String.valueOf(sanitized).contains(handlerMessage));
    }

    @Test
    void persistenceNeverReceivesRawCallerContentInAnyStringField() {
        RuntimeTraceSpanMapper spanMapper = mock(RuntimeTraceSpanMapper.class);
        RuntimeToolCallLogMapper toolLogMapper = mock(RuntimeToolCallLogMapper.class);
        RuntimeRunMapper runMapper = mock(RuntimeRunMapper.class);
        RuntimeGuardDecisionLogMapper guardLogMapper = mock(RuntimeGuardDecisionLogMapper.class);
        RuntimeRunLifecycleService lifecycle = new RuntimeRunLifecycleService(runMapper, new ObjectMapper());
        SupervisorExecutionTraceService service = new SupervisorExecutionTraceService(
                spanMapper, toolLogMapper, guardLogMapper, lifecycle, new ObjectMapper());

        String message = uuid();
        String inputText = uuid();
        String knowledgeQuery = uuid();
        String hitContent = uuid();
        String httpBody = uuid();
        String queryParam = uuid();
        String q = uuid();
        String searchText = uuid();
        String customerValue = uuid();
        String planReason = uuid();
        String rejectionMessage = uuid();
        RuntimeAgentView agent = agent();
        RuntimeAgentConfigVersionEntity config = config();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("message", message);
        input.put("input", inputText);
        input.put("sessionId", "session-1");
        input.put("agentId", agent.id());
        input.put("traceId", "trace-1");
        input.put("entryType", "API");

        SupervisorExecutionTraceService.TraceHandle handle = service.begin(agent, config, List.of(), input);
        service.plan(handle, agent, config, input, 1, Map.of(
                "reason", planReason,
                "steps", List.of(Map.of("toolName", "wf_tool", "reason", planReason))));
        service.guard(handle, agent, input, "wf_tool", "DENY", planReason, Map.of(
                "policyProfile", "DEFAULT",
                "args", Map.of("q", q, "searchText", searchText, "customerValue", customerValue)));

        Map<String, Object> resultMetadata = new LinkedHashMap<>();
        resultMetadata.put("answer", httpBody);
        resultMetadata.put("body", httpBody);
        resultMetadata.put("query", knowledgeQuery);
        resultMetadata.put("hits", List.of(Map.of("content", hitContent)));
        resultMetadata.put("workflowNodeTraces", List.of(
                Map.of(
                        "nodeId", "http1",
                        "nodeType", "HTTP_REQUEST",
                        "status", "FAILED",
                        "attempt", 2,
                        "latencyMs", 12,
                        "traceSummary", Map.of(
                                "statusCode", 500,
                                "body", httpBody,
                                "url", "https://user:pass@example.com/x?q=" + queryParam,
                                "bodyBytes", 42)),
                Map.of(
                        "nodeId", "knowledge1",
                        "nodeType", "KNOWLEDGE_RETRIEVAL",
                        "status", "SUCCESS",
                        "traceSummary", Map.of(
                                "queryLength", knowledgeQuery.length(),
                                "hitCount", 3,
                                "topK", 5,
                                "query", knowledgeQuery,
                                "hits", List.of(Map.of("content", hitContent))))));
        service.workflow(handle, agent, config, input, "wf_tool", "wf-1", 2L, "v1",
                Map.of(
                        "q", q,
                        "searchText", searchText,
                        "customerValue", customerValue,
                        "nested", Map.of("q", q),
                        "toolName", "wf_tool"),
                false, "RUNTIME_HTTP_STATUS_500", httpBody, 12L, resultMetadata);
        lifecycle.rejectAgent("rejected-trace", agent, input, "AGENT_POLICY_DENIED", rejectionMessage);

        ArgumentCaptor<RuntimeTraceSpanEntity> spans = ArgumentCaptor.forClass(RuntimeTraceSpanEntity.class);
        ArgumentCaptor<RuntimeToolCallLogEntity> toolLogs = ArgumentCaptor.forClass(RuntimeToolCallLogEntity.class);
        ArgumentCaptor<RuntimeRunEntity> runs = ArgumentCaptor.forClass(RuntimeRunEntity.class);
        ArgumentCaptor<RuntimeGuardDecisionLogEntity> guardLogs =
                ArgumentCaptor.forClass(RuntimeGuardDecisionLogEntity.class);
        verify(spanMapper, atLeast(5)).insert(spans.capture());
        verify(toolLogMapper).insert(toolLogs.capture());
        verify(runMapper, atLeast(2)).insert(runs.capture());
        verify(guardLogMapper).insert(guardLogs.capture());

        RuntimeTraceSpanEntity planSpan = spans.getAllValues().stream()
                .filter(span -> "PLAN".equals(span.getSpanType()))
                .findFirst()
                .orElseThrow();
        assertFalse(planSpan.getInputSummary().contains(message));
        assertTrue(planSpan.getInputSummary().contains("\"inputType\":\"message\""));
        assertTrue(planSpan.getInputSummary().contains("\"messageLength\":" + message.length()));

        String persisted = Stream.of(
                        stringFields(spans.getAllValues()),
                        stringFields(toolLogs.getAllValues()),
                        stringFields(runs.getAllValues()),
                        stringFields(guardLogs.getAllValues()))
                .flatMap(List::stream)
                .reduce("", String::concat);
        for (String sensitive : List.of(message, inputText, knowledgeQuery, hitContent, httpBody, queryParam,
                q, searchText, customerValue, planReason, rejectionMessage)) {
            assertFalse(persisted.contains(sensitive), () -> "Persisted sensitive value: " + sensitive);
        }
        assertTrue(persisted.contains("\"statusCode\":500"));
        assertTrue(persisted.contains("\"hitCount\":3"));
        assertTrue(persisted.contains("\"stepCount\":1"));
        assertTrue(persisted.contains("\"messageLength\":" + message.length()));
        assertTrue(persisted.contains("[rejected:AGENT_POLICY_DENIED]"));
    }

    private static List<String> stringFields(List<?> entities) {
        return entities.stream()
                .flatMap(entity -> java.util.Arrays.stream(entity.getClass().getDeclaredFields())
                        .filter(field -> field.getType() == String.class)
                        .map(field -> fieldValue(field, entity)))
                .toList();
    }

    private static String fieldValue(Field field, Object entity) {
        try {
            field.setAccessible(true);
            Object value = field.get(entity);
            return value == null ? "" : (String) value;
        } catch (IllegalAccessException ex) {
            throw new AssertionError(ex);
        }
    }

    private static RuntimeAgentView agent() {
        return new RuntimeAgentView(
                "agent-1", 1L, "demo", "agent", "Agent", null, "PROJECT", null, true,
                10L, 10L, 1, "ACTIVE", "AGENTSCOPE", 1, null, null);
    }

    private static RuntimeAgentConfigVersionEntity config() {
        RuntimeAgentConfigVersionEntity config = new RuntimeAgentConfigVersionEntity();
        config.setId(10L);
        config.setVersionNo(1);
        config.setPolicyProfile("DEFAULT");
        config.setToolCatalogMode("WHITELIST");
        config.setModelInstanceId("model-1");
        return config;
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }
}
