package com.enterprise.ai.agent.runops;

import com.enterprise.ai.agent.agentscope.AgentRouter;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.governance.GuardDecisionLogService;
import com.enterprise.ai.agent.model.AgentResult;
import com.enterprise.ai.agent.runtime.GraphRuntimeContext;
import com.enterprise.ai.agent.tool.log.ToolCallLogService;
import com.enterprise.ai.agent.trace.AgentTraceSpanEntity;
import com.enterprise.ai.agent.trace.AgentTraceSpanMapper;
import com.enterprise.ai.agent.trace.AgentTraceSpanService;
import com.enterprise.ai.agent.workflow.AgentEntryEntity;
import com.enterprise.ai.agent.workflow.AgentEntryService;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionEntity;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionService;
import com.enterprise.ai.agent.workflow.WorkflowRuntimeGraphAdapter;
import com.enterprise.ai.agent.workflow.WorkflowVersionEntity;
import com.enterprise.ai.agent.workflow.WorkflowVersionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunOpsServiceTest {

    @Mock
    private ToolCallLogService toolCallLogService;
    @Mock
    private AgentTraceSpanService traceSpanService;
    @Mock
    private AgentTraceSpanMapper traceSpanMapper;
    @Mock
    private GuardDecisionLogService guardDecisionLogService;
    @Mock
    private AgentEntryService agentEntryService;
    @Mock
    private WorkflowRuntimeGraphAdapter workflowRuntimeGraphAdapter;
    @Mock
    private AgentRouter agentRouter;
    @Mock
    private WorkflowDefinitionService workflowDefinitionService;
    @Mock
    private WorkflowVersionMapper workflowVersionMapper;

    private RunOpsService runOpsService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        runOpsService = new RunOpsService(
                toolCallLogService,
                traceSpanService,
                traceSpanMapper,
                guardDecisionLogService,
                agentEntryService,
                workflowRuntimeGraphAdapter,
                agentRouter,
                workflowDefinitionService,
                workflowVersionMapper,
                objectMapper);
    }

    @Test
    void groupIdentityKey_prefersWorkflowIdForWorkflowRun() {
        RunOpsService.RunSummary summary = workflowSummary("wf-1", "WORKFLOW_VERSION", 11L);

        assertEquals("wf-1", runOpsService.groupIdentityKey(summary));
        assertEquals("11", runOpsService.groupVersionKey(summary));
    }

    @Test
    void groupIdentityKey_keepsAgentKeyForLegacyAgentRun() {
        RunOpsService.RunSummary summary = agentSummary("agent-1", "demo-agent", 3L, "v1.0");

        assertEquals("agent-1", runOpsService.groupIdentityKey(summary));
        assertEquals("3", runOpsService.groupVersionKey(summary));
    }

    @Test
    void detail_extractsWorkflowFieldsFromSpanMetadata() {
        AgentTraceSpanEntity span = new AgentTraceSpanEntity();
        span.setId(1L);
        span.setTraceId("trace-wf-1");
        span.setSpanId("span-1");
        span.setSpanType("NODE");
        span.setNodeId("answer");
        span.setStatus("SUCCESS");
        span.setStartedAt(LocalDateTime.of(2026, 6, 14, 10, 0));
        span.setEndedAt(LocalDateTime.of(2026, 6, 14, 10, 1));
        span.setMetadataJson("""
                {
                  "sourceType":"WORKFLOW_VERSION",
                  "sourceId":"wf-1",
                  "workflowId":"wf-1",
                  "workflowKeySlug":"orders",
                  "workflowVersion":"v2",
                  "workflowVersionId":22,
                  "entryAgentId":"entry-1",
                  "entryAgentKeySlug":"orders-entry",
                  "runtimeType":"LANGGRAPH4J",
                  "runtimePlacement":"PLATFORM"
                }
                """);

        when(toolCallLogService.getTraceLogs("trace-wf-1")).thenReturn(List.of());
        when(traceSpanService.listByTraceId("trace-wf-1")).thenReturn(List.of(span));
        when(guardDecisionLogService.search(any())).thenReturn(List.of());

        RunOpsService.RunDetail detail = runOpsService.detail("trace-wf-1");
        RunOpsService.RunSummary summary = detail.summary();

        assertEquals("wf-1", summary.workflowId());
        assertEquals("orders", summary.workflowKeySlug());
        assertEquals("v2", summary.workflowVersion());
        assertEquals(22L, summary.workflowVersionId());
        assertEquals("entry-1", summary.entryAgentId());
        assertEquals("orders-entry", summary.entryAgentKeySlug());
        assertEquals("WORKFLOW_VERSION", summary.sourceType());
        assertEquals("wf-1", summary.sourceId());
        assertEquals("LANGGRAPH4J", summary.runtimeType());
        assertEquals("PLATFORM", summary.runtimePlacement());
        assertEquals("wf-1", summary.metadata().get("workflowId"));
    }

    @Test
    void detail_keepsLegacyAgentFieldsWhenNoWorkflowMetadata() {
        AgentTraceSpanEntity span = new AgentTraceSpanEntity();
        span.setId(2L);
        span.setTraceId("trace-agent-1");
        span.setSpanId("span-2");
        span.setSpanType("AGENT_RUN");
        span.setAgentId("agent-1");
        span.setAgentName("demo-agent");
        span.setStatus("SUCCESS");
        span.setStartedAt(LocalDateTime.of(2026, 6, 14, 9, 0));
        span.setMetadataJson("""
                {
                  "version":"v1.0",
                  "versionId":3,
                  "runtimeType":"LANGGRAPH4J"
                }
                """);

        when(toolCallLogService.getTraceLogs("trace-agent-1")).thenReturn(List.of());
        when(traceSpanService.listByTraceId("trace-agent-1")).thenReturn(List.of(span));
        when(guardDecisionLogService.search(any())).thenReturn(List.of());

        RunOpsService.RunSummary summary = runOpsService.detail("trace-agent-1").summary();

        assertEquals("agent-1", summary.agentId());
        assertEquals("demo-agent", summary.agentName());
        assertEquals("v1.0", summary.version());
        assertEquals(3L, summary.versionId());
        assertNull(summary.workflowId());
        assertNull(summary.sourceType());
    }

    @Test
    void replay_usesGraphSpecNativePathForWorkflowTrace() {
        AgentTraceSpanEntity span = workflowSpan("trace-wf-replay", "approve this");
        when(toolCallLogService.getTraceLogs("trace-wf-replay")).thenReturn(List.of());
        when(traceSpanService.listByTraceId("trace-wf-replay")).thenReturn(List.of(span));

        WorkflowDefinitionEntity workflow = new WorkflowDefinitionEntity();
        workflow.setId("wf-1");
        workflow.setKeySlug("orders");
        workflow.setName("Orders Workflow");

        WorkflowVersionEntity version = new WorkflowVersionEntity();
        version.setId(22L);
        version.setWorkflowId("wf-1");
        version.setVersion("v2");

        AgentEntryEntity entryAgent = new AgentEntryEntity();
        entryAgent.setId("entry-1");
        entryAgent.setKeySlug("orders-entry");

        GraphSpec graphSpec = GraphSpec.builder().code("orders").entry("start").build();
        GraphRuntimeContext runtimeContext = GraphRuntimeContext.builder()
                .sourceType("WORKFLOW_VERSION")
                .sourceId("wf-1")
                .sourceKeySlug("orders")
                .sourceVersion("v2")
                .sourceVersionId(22L)
                .name("Orders Workflow")
                .extra(new LinkedHashMap<>(Map.of(
                        "workflowId", "wf-1",
                        "entryAgentId", "entry-1")))
                .build();

        when(workflowDefinitionService.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(workflowVersionMapper.selectById(22L)).thenReturn(version);
        when(agentEntryService.findById("entry-1")).thenReturn(Optional.of(entryAgent));
        when(workflowRuntimeGraphAdapter.toRuntimeGraph(eq(entryAgent), eq(workflow), eq(version), any()))
                .thenReturn(new WorkflowRuntimeGraphAdapter.RuntimeGraph(graphSpec, runtimeContext));
        when(agentRouter.executeByGraphSpec(eq(graphSpec), eq(runtimeContext), any(), any(), any(), any(), any()))
                .thenReturn(AgentResult.builder()
                        .success(true)
                        .answer("replayed")
                        .metadata(Map.of("traceId", "trace-replay-new"))
                        .build());

        RunOpsService.ReplayResult result = runOpsService.replay("trace-wf-replay", new RunOpsService.ReplayRequest(null, null, null, null, true));

        assertEquals("GRAPH_SPEC", result.executionPath());
        assertEquals("wf-1", result.workflowId());
        assertEquals("entry-1", result.entryAgentId());
        assertEquals("trace-replay-new", result.replayTraceId());
        verify(agentRouter).executeByGraphSpec(eq(graphSpec), eq(runtimeContext), any(), any(), eq("approve this"), any(), any());
    }

    private static AgentTraceSpanEntity workflowSpan(String traceId, String input) {
        AgentTraceSpanEntity span = new AgentTraceSpanEntity();
        span.setId(10L);
        span.setTraceId(traceId);
        span.setSpanId("span-root");
        span.setSpanType("AGENT_RUN");
        span.setInputSummary(input);
        span.setStatus("SUCCESS");
        span.setMetadataJson("""
                {
                  "sourceType":"WORKFLOW_VERSION",
                  "sourceId":"wf-1",
                  "workflowId":"wf-1",
                  "workflowKeySlug":"orders",
                  "workflowVersion":"v2",
                  "workflowVersionId":22,
                  "entryAgentId":"entry-1",
                  "entryAgentKeySlug":"orders-entry"
                }
                """);
        return span;
    }

    private static RunOpsService.RunSummary workflowSummary(String workflowId, String sourceType, Long workflowVersionId) {
        return new RunOpsService.RunSummary(
                "trace-wf",
                "ERROR",
                "entry-1",
                "orders-entry",
                null,
                null,
                "LANGGRAPH4J",
                "PLATFORM",
                null,
                null,
                null,
                null,
                LocalDateTime.now(),
                LocalDateTime.now(),
                100,
                0,
                1,
                0,
                1,
                false,
                null,
                null,
                workflowId,
                "orders",
                "v2",
                workflowVersionId,
                "entry-1",
                "orders-entry",
                sourceType,
                workflowId,
                Map.of("workflowId", workflowId, "sourceType", sourceType));
    }

    private static RunOpsService.RunSummary agentSummary(String agentId, String agentName, Long versionId, String version) {
        return new RunOpsService.RunSummary(
                "trace-agent",
                "SUCCESS",
                agentId,
                agentName,
                version,
                versionId,
                "LANGGRAPH4J",
                "PLATFORM",
                "demo",
                null,
                null,
                null,
                LocalDateTime.now(),
                LocalDateTime.now(),
                50,
                0,
                1,
                0,
                0,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of());
    }
}
