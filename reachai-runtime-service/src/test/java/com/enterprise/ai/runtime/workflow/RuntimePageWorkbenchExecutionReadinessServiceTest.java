package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.runtime.runops.RuntimeRunEntity;
import com.enterprise.ai.runtime.runops.RuntimeRunMapper;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanEntity;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.enterprise.ai.runtime.workflow.RuntimePageWorkbenchExecutionReadinessService.ExecutionReadinessView;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowResourceBindingService.BindingView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimePageWorkbenchExecutionReadinessServiceTest {

    private RuntimeRunMapper runMapper;
    private RuntimeTraceSpanMapper spanMapper;
    private RuntimeWorkflowDefinitionMapper workflowMapper;
    private RuntimeWorkflowVersionMapper versionMapper;
    private RuntimeWorkflowResourceBindingService bindingService;
    private ObjectMapper objectMapper;
    private RuntimePageWorkbenchExecutionReadinessService service;

    @BeforeEach
    void setUp() {
        runMapper = mock(RuntimeRunMapper.class);
        spanMapper = mock(RuntimeTraceSpanMapper.class);
        workflowMapper = mock(RuntimeWorkflowDefinitionMapper.class);
        versionMapper = mock(RuntimeWorkflowVersionMapper.class);
        bindingService = mock(RuntimeWorkflowResourceBindingService.class);
        objectMapper = new ObjectMapper();
        service = new RuntimePageWorkbenchExecutionReadinessService(
                runMapper,
                spanMapper,
                workflowMapper,
                versionMapper,
                bindingService,
                objectMapper);

        when(workflowMapper.selectById("wf-orders"))
                .thenReturn(workflow());
        when(versionMapper.selectById(21L)).thenReturn(version());
        when(bindingService.list("wf-orders")).thenReturn(List.of(
                binding("orders.detail")));
        when(runMapper.selectOne(any())).thenReturn(run());
    }

    @Test
    void passesOnlyTheExactPublishedWorkflowVersion() throws Exception {
        when(spanMapper.selectList(any())).thenReturn(List.of(
                workflowSpan("SUCCESS", 21L)));

        ExecutionReadinessView result = evaluate();

        assertEquals("PASS", result.status());
        assertEquals("PAGE_WORKFLOW_TRACE_READY", result.code());
        assertTrue(result.workflowObserved());
    }

    @Test
    void doesNotInferPageActionCoverageFromTheWorkflowGraph()
            throws Exception {
        RuntimeWorkflowVersionEntity version = version();
        version.setGraphSpecSnapshotJson(
                "{\"schemaVersion\":2,\"nodes\":["
                        + "{\"id\":\"search\",\"type\":\"PAGE_ACTION\","
                        + "\"config\":{\"pageKey\":\"orders.detail\","
                        + "\"actionKey\":\"search\"}}],\"edges\":[]}");
        when(versionMapper.selectById(21L)).thenReturn(version);
        when(spanMapper.selectList(any())).thenReturn(List.of(
                workflowSpan("SUCCESS", 21L)));

        ExecutionReadinessView result = evaluate();

        assertEquals("PASS", result.status());
        assertEquals("PAGE_WORKFLOW_TRACE_READY", result.code());
        assertTrue(result.pageActionRequired());
        assertTrue(!result.pageActionObserved());
    }

    @Test
    void exposesOnlyNodeTypesActuallyObservedInTheExactWorkflowTrace()
            throws Exception {
        RuntimeWorkflowVersionEntity version = version();
        version.setGraphSpecSnapshotJson(
                "{\"schemaVersion\":2,\"nodes\":["
                        + "{\"id\":\"query-api\",\"type\":\"TOOL\"},"
                        + "{\"id\":\"refresh-page\",\"type\":\"PAGE_ACTION\"}],"
                        + "\"edges\":[]}");
        when(versionMapper.selectById(21L)).thenReturn(version);
        when(spanMapper.selectList(any())).thenReturn(List.of(
                workflowSpan("SUCCESS", 21L),
                nodeSpan("TOOL", "SUCCESS"),
                nodeSpan("PAGE_ACTION", "SUCCESS")));

        ExecutionReadinessView result = evaluate();

        assertEquals("PASS", result.status());
        assertTrue(result.capabilityRequired());
        assertTrue(result.capabilityObserved());
        assertTrue(result.pageActionRequired());
        assertTrue(result.pageActionObserved());
    }

    @Test
    void recognizesBusinessTerminalPageActionAsObservedBridgeExecution()
            throws Exception {
        RuntimeWorkflowVersionEntity version = version();
        version.setGraphSpecSnapshotJson(
                "{\"schemaVersion\":2,\"nodes\":["
                        + "{\"id\":\"open\",\"type\":\"PAGE_ACTION\"}],\"edges\":[]}");
        when(versionMapper.selectById(21L)).thenReturn(version);
        when(spanMapper.selectList(any())).thenReturn(List.of(
                workflowSpan("BUSINESS_TERMINAL", 21L),
                nodeSpan("PAGE_ACTION", "BUSINESS_TERMINAL")));

        ExecutionReadinessView result = evaluate();

        assertEquals("PASS", result.status());
        assertTrue(result.workflowObserved());
        assertTrue(result.pageActionObserved());
        assertTrue(result.pageActionBusinessTerminalObserved());
    }

    @Test
    void rejectsStructuredPresentationDeclaredButNotExecuted()
            throws Exception {
        RuntimeWorkflowVersionEntity version = version();
        version.setGraphSpecSnapshotJson(
                "{\"schemaVersion\":2,\"nodes\":["
                        + "{\"id\":\"query\",\"type\":\"PAGE_ACTION\"},"
                        + "{\"id\":\"present\",\"type\":\"INTERACTION\","
                        + "\"config\":{\"interactionType\":\"PRESENT_OUTPUT\","
                        + "\"component\":\"list_card\"}}],\"edges\":[]}");
        when(versionMapper.selectById(21L)).thenReturn(version);
        when(spanMapper.selectList(any())).thenReturn(List.of(
                workflowSpan("SUCCESS", 21L),
                nodeSpan("PAGE_ACTION", "SUCCESS")));

        ExecutionReadinessView result = evaluate();

        assertEquals("FAIL", result.status());
        assertEquals("STRUCTURED_PRESENTATION_NOT_OBSERVED", result.code());
        assertTrue(result.structuredPresentationRequired());
        assertTrue(!result.structuredPresentationObserved());
    }

    @Test
    void acceptsExecutedPresentOutputNode()
            throws Exception {
        RuntimeWorkflowVersionEntity version = version();
        version.setGraphSpecSnapshotJson(
                "{\"schemaVersion\":2,\"nodes\":["
                        + "{\"id\":\"present\",\"type\":\"INTERACTION\","
                        + "\"config\":{\"interactionType\":\"PRESENT_OUTPUT\","
                        + "\"component\":\"list_card\"}}],\"edges\":[]}");
        when(versionMapper.selectById(21L)).thenReturn(version);
        when(spanMapper.selectList(any())).thenReturn(List.of(
                workflowSpan("SUCCESS", 21L),
                interactionNodeSpan("PRESENT_OUTPUT", "SUCCESS")));

        ExecutionReadinessView result = evaluate();

        assertEquals("PASS", result.status());
        assertTrue(result.structuredPresentationRequired());
        assertTrue(result.structuredPresentationObserved());
    }

    @Test
    void rejectsAWorkflowSpanFromAnotherVersion() throws Exception {
        when(spanMapper.selectList(any())).thenReturn(List.of(
                workflowSpan("SUCCESS", 22L)));

        ExecutionReadinessView result = evaluate();

        assertEquals("FAIL", result.status());
        assertEquals("WORKFLOW_EXECUTION_NOT_OBSERVED", result.code());
    }

    @Test
    void rejectsARunFromAnotherPageSession() throws Exception {
        RuntimeRunEntity run = run();
        run.setPageInstanceId("another-page-instance");
        when(runMapper.selectOne(any())).thenReturn(run);
        when(spanMapper.selectList(any())).thenReturn(List.of(
                workflowSpan("SUCCESS", 21L)));

        ExecutionReadinessView result = evaluate();

        assertEquals("FAIL", result.status());
        assertEquals("RUNTIME_RUN_IDENTITY_MISMATCH", result.code());
    }

    private ExecutionReadinessView evaluate() {
        return service.evaluate(
                "orders",
                "orders.detail",
                "embed-session",
                "page-instance",
                "trace-orders",
                "wf-orders",
                21L,
                "v1.0.0");
    }

    private RuntimeWorkflowDefinitionEntity workflow() {
        RuntimeWorkflowDefinitionEntity workflow =
                new RuntimeWorkflowDefinitionEntity();
        workflow.setId("wf-orders");
        workflow.setProjectCode("orders");
        workflow.setWorkflowKind("PAGE_ASSISTANT");
        workflow.setStatus("ACTIVE");
        return workflow;
    }

    private RuntimeWorkflowVersionEntity version() {
        RuntimeWorkflowVersionEntity version =
                new RuntimeWorkflowVersionEntity();
        version.setId(21L);
        version.setWorkflowId("wf-orders");
        version.setVersion("v1.0.0");
        version.setStatus("ACTIVE");
        return version;
    }

    private RuntimeRunEntity run() {
        RuntimeRunEntity run = new RuntimeRunEntity();
        run.setTraceId("trace-orders");
        run.setProjectCode("orders");
        run.setSessionId("embed-session");
        run.setPageInstanceId("page-instance");
        run.setEntryType("EMBED");
        run.setStatus("COMPLETED");
        return run;
    }

    private RuntimeTraceSpanEntity workflowSpan(
            String status,
            Long versionId) throws Exception {
        RuntimeTraceSpanEntity span = new RuntimeTraceSpanEntity();
        span.setSpanType("WORKFLOW_TOOL");
        span.setStatus(status);
        span.setMetadataJson(objectMapper.writeValueAsString(Map.of(
                "workflowId", "wf-orders",
                "workflowVersionId", versionId,
                "workflowVersion", "v1.0.0")));
        return span;
    }

    private RuntimeTraceSpanEntity nodeSpan(
            String nodeType,
            String status) throws Exception {
        RuntimeTraceSpanEntity span = new RuntimeTraceSpanEntity();
        span.setSpanType("WORKFLOW_NODE");
        span.setStatus(status);
        span.setMetadataJson(objectMapper.writeValueAsString(Map.of(
                "nodeType", nodeType)));
        return span;
    }

    private RuntimeTraceSpanEntity interactionNodeSpan(
            String interactionType,
            String status) throws Exception {
        RuntimeTraceSpanEntity span = new RuntimeTraceSpanEntity();
        span.setSpanType("WORKFLOW_NODE");
        span.setStatus(status);
        span.setMetadataJson(objectMapper.writeValueAsString(Map.of(
                "nodeType", "INTERACTION",
                "interactionType", interactionType)));
        return span;
    }

    private BindingView binding(String pageKey) {
        return new BindingView(
                1L,
                "wf-orders",
                7L,
                "orders",
                "PAGE",
                pageKey,
                "TARGET",
                "ACTIVE",
                LocalDateTime.now());
    }
}
