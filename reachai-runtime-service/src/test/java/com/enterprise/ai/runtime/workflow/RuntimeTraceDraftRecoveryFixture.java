package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.runtime.api.*;
import com.enterprise.ai.runtime.internal.RuntimeTraceWorkflowCandidateInternalController;
import com.enterprise.ai.runtime.route.RuntimeRouteEvaluationService;
import com.enterprise.ai.runtime.runops.*;
import com.enterprise.ai.runtime.supervisor.RuntimeAgentExecutionService;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.trace.*;
import com.enterprise.ai.runtime.workflow.aicoding.RuntimeWorkflowAiCodingService;
import com.enterprise.ai.runtime.workflow.node.RuntimeWorkflowNodeCapabilityRegistry;
import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalEditService;
import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalGenerationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.support.GenericApplicationContext;

import java.time.LocalDateTime;
import java.util.Map;

import static org.mockito.Mockito.mock;

/** Real owning RunOps/version queries and validation behind the Trace recovery HTTP scenario. */
final class RuntimeTraceDraftRecoveryFixture {
    static final String GRAPH = """
            {"schemaVersion":2,"nodes":[{"id":"read","type":"TOOL","ref":{"qualifiedName":"orders.read"}}],
             "edges":[],"entryNodeId":"read","exitNodeIds":["read"]}
            """;

    static void configure(GenericApplicationContext context, ObjectMapper json) {
        context.registerBean(RuntimeTraceWorkflowCandidateDraftService.class, () -> new RuntimeTraceWorkflowCandidateDraftService(
                context.getBean(RuntimeWorkflowAiCodingService.class), context.getBean(RuntimeWorkflowReleaseValidationService.class),
                json, context.getBean(RuntimeWorkflowDraftSubmissionService.class)));
        context.registerBean(RuntimeTraceWorkflowCandidateInternalController.class,
                () -> new RuntimeTraceWorkflowCandidateInternalController(context.getBean(RuntimeTraceWorkflowCandidateDraftService.class)));
        context.registerBean(RuntimeWorkflowManagementService.class, () -> new RuntimeWorkflowManagementService(
                context.getBean(RuntimeWorkflowDefinitionService.class), context.getBean(RuntimeWorkflowVersionService.class),
                context.getBean(RuntimeWorkflowReleaseValidationService.class)));
        context.registerBean(RuntimeWorkflowVersionPublicController.class,
                () -> new RuntimeWorkflowVersionPublicController(context.getBean(RuntimeWorkflowManagementService.class)));
        context.registerBean(RuntimeWorkflowPublicController.class, () -> new RuntimeWorkflowPublicController(
                context.getBean(RuntimeWorkflowManagementService.class), mock(RuntimeWorkflowStudioService.class),
                mock(RuntimeWorkflowDebugService.class), mock(RuntimeWorkflowProposalGenerationService.class),
                mock(RuntimeWorkflowProposalEditService.class), new RuntimeWorkflowNodeCapabilityRegistry()));
        context.registerBean(RuntimeWorkflowAiCodingPublicController.class,
                () -> new RuntimeWorkflowAiCodingPublicController(context.getBean(RuntimeWorkflowAiCodingService.class)));
        context.registerBean(RuntimePublicController.class, () -> new RuntimePublicController(
                context.getBean(RuntimeTraceQueryService.class), mock(RuntimeAgentExecutionService.class),
                mock(RuntimeRouteEvaluationService.class), context.getBean(RuntimeRunOpsQueryService.class),
                mock(RuntimeRunOpsReplayService.class), mock(SseHeartbeatSupport.class), 8000));
    }

    static void seed(GenericApplicationContext context, RuntimeQueryTestDatabase db, ObjectMapper json) throws Exception {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        var source = new RuntimeWorkflowDefinitionEntity();
        source.setId("wf-source"); source.setName("源只读流程"); source.setKeySlug("source-read");
        source.setProjectId(7L); source.setProjectCode("orders"); source.setWorkflowKind("GENERAL");
        source.setExecutionEngine("GRAPH_SPEC"); source.setGraphSpecJson(GRAPH);
        context.getBean(RuntimeWorkflowDefinitionService.class).create(source);
        var version = new RuntimeWorkflowVersionEntity();
        version.setId(22L); version.setWorkflowId("wf-source"); version.setVersion("v1.0.1"); version.setStatus("ACTIVE");
        version.setSnapshotJson("{}"); version.setGraphSpecSnapshotJson(GRAPH); version.setRolloutPercent(100);
        version.setPublishedBy("fixture"); version.setPublishedAt(now);
        db.mapper(RuntimeWorkflowVersionMapper.class).insert(version);
        var run = new RuntimeRunEntity();
        run.setTraceId("trace-1"); run.setRunType("AGENT"); run.setEntryType("API"); run.setStatus("COMPLETED");
        run.setRuntimeType("AGENTSCOPE"); run.setProjectId(7L); run.setProjectCode("orders");
        run.setPlanCount(1); run.setReplanCount(0); run.setWorkflowCallCount(1); run.setToolCallCount(1);
        run.setGuardDenyCount(0); run.setApprovalCount(0); run.setStartedAt(now); run.setEndedAt(now.plusSeconds(1));
        db.mapper(RuntimeRunMapper.class).insert(run);
        var workflowSpan = new RuntimeTraceSpanEntity();
        workflowSpan.setTraceId("trace-1"); workflowSpan.setSpanId("workflow-span"); workflowSpan.setSpanType("WORKFLOW_TOOL");
        workflowSpan.setStatus("SUCCESS"); workflowSpan.setToolName("orders_read"); workflowSpan.setStartedAt(now);
        workflowSpan.setMetadataJson(json.writeValueAsString(Map.of("workflowId", "wf-source", "workflowVersionId", 22,
                "workflowVersion", "v1.0.1")));
        db.mapper(RuntimeTraceSpanMapper.class).insert(workflowSpan);
        var nodeSpan = new RuntimeTraceSpanEntity();
        nodeSpan.setTraceId("trace-1"); nodeSpan.setSpanId("read-span"); nodeSpan.setParentSpanId("workflow-span");
        nodeSpan.setSpanType("WORKFLOW_NODE"); nodeSpan.setStatus("SUCCESS"); nodeSpan.setNodeId("read"); nodeSpan.setStartedAt(now);
        db.mapper(RuntimeTraceSpanMapper.class).insert(nodeSpan);
        var call = new RuntimeToolCallLogEntity();
        call.setTraceId("trace-1"); call.setToolName("orders.read"); call.setSuccess(true); call.setCreateTime(now);
        db.mapper(RuntimeToolCallLogMapper.class).insert(call);
        var guard = new RuntimeGuardDecisionLogEntity();
        guard.setTraceId("trace-1"); guard.setDecisionType("SUPERVISOR_TOOL_POLICY"); guard.setTargetKind("WORKFLOW_TOOL");
        guard.setTargetName("orders_read"); guard.setDecision("ALLOW"); guard.setProjectCode("orders");
        guard.setMetadataJson(json.writeValueAsString(Map.of("readOnly", true, "riskLevel", "READ")));
        db.mapper(RuntimeGuardDecisionLogMapper.class).insert(guard);
    }
}
