package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.runtime.GraphRuntimeContext;
import com.enterprise.ai.agent.runtime.LangGraph4jRuntimeAdapter;
import com.enterprise.ai.agent.workflow.WorkflowRuntimeGraphAdapter;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionEntity;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/workflows/studio")
@RequiredArgsConstructor
public class WorkflowStudioDebugController {

    private final LangGraph4jRuntimeAdapter langGraph4jRuntimeAdapter;
    private final WorkflowDefinitionService workflowDefinitionService;
    private final WorkflowRuntimeGraphAdapter workflowRuntimeGraphAdapter;

    @PostMapping("/debug-node")
    public ResponseEntity<LangGraph4jRuntimeAdapter.NodeDebugResult> debugNode(@RequestBody NodeDebugRequest request) {
        if (request == null || !StringUtils.hasText(request.getNodeId())) {
            throw new IllegalArgumentException("nodeId is required");
        }
        DebugPayload payload = resolveDebugPayload(request);
        return ResponseEntity.ok(langGraph4jRuntimeAdapter.debugNode(
                payload.graphSpec(),
                payload.runtimeContext(),
                request.getNodeId(),
                request.getMessage(),
                request.getState()));
    }

    @PostMapping("/debug-run")
    public ResponseEntity<LangGraph4jRuntimeAdapter.WorkflowDebugRunResult> debugRun(@RequestBody DebugRunRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("workflow debug request is required");
        }
        DebugPayload payload = resolveDebugPayload(request);
        return ResponseEntity.ok(langGraph4jRuntimeAdapter.debugRun(
                payload.graphSpec(),
                payload.runtimeContext(),
                request.getMessage(),
                request.getInputParams(),
                request.getDebugOptions()));
    }

    private DebugPayload resolveDebugPayload(WorkflowDebugRequest request) {
        WorkflowDefinitionEntity workflow = null;
        if (StringUtils.hasText(request.getWorkflowId())) {
            workflow = workflowDefinitionService.findById(request.getWorkflowId())
                    .orElse(null);
        }
        String graphSpecJson = firstText(
                request.getGraphSpecJson(),
                workflow == null ? null : workflow.getGraphSpecJson());
        if (!StringUtils.hasText(graphSpecJson)) {
            throw new IllegalArgumentException("graphSpecJson or workflowId is required");
        }
        GraphSpec graphSpec = workflowRuntimeGraphAdapter.readGraphSpec(graphSpecJson);
        String workflowId = firstText(request.getWorkflowId(), workflow == null ? null : workflow.getId());
        String workflowKeySlug = firstText(request.getWorkflowKeySlug(), workflow == null ? null : workflow.getKeySlug());
        String workflowName = firstText(request.getWorkflowName(), workflow == null ? null : workflow.getName());
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("workflowDebug", true);
        if (StringUtils.hasText(workflowId)) {
            extra.put("workflowId", workflowId);
        }
        if (StringUtils.hasText(workflowKeySlug)) {
            extra.put("workflowKeySlug", workflowKeySlug);
        }
        GraphRuntimeContext runtimeContext = GraphRuntimeContext.builder()
                .sourceType("WORKFLOW_DRAFT")
                .sourceId(workflowId)
                .sourceKeySlug(workflowKeySlug)
                .name(firstText(workflowName, graphSpec.getName(), "Workflow Debug"))
                .intentType(firstText(request.getWorkflowType(), workflow == null ? null : workflow.getWorkflowType(), "WORKFLOW"))
                .projectId(workflow == null ? null : workflow.getProjectId())
                .projectCode(firstText(request.getProjectCode(), workflow == null ? null : workflow.getProjectCode()))
                .runtimeType(firstText(request.getRuntimeType(), workflow == null ? null : workflow.getRuntimeType(), "LANGGRAPH4J"))
                .runtimePlacement("CENTRAL")
                .modelInstanceId(firstText(request.getModelInstanceId(),
                        workflow == null ? null : workflow.getDefaultModelInstanceId()))
                .canvasJson(firstText(request.getCanvasJson(), workflow == null ? null : workflow.getCanvasJson()))
                .extra(extra)
                .build();
        return new DebugPayload(graphSpec, runtimeContext);
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private record DebugPayload(GraphSpec graphSpec, GraphRuntimeContext runtimeContext) {
    }

    public interface WorkflowDebugRequest {
        String getWorkflowId();

        String getWorkflowKeySlug();

        String getWorkflowName();

        String getWorkflowType();

        String getProjectCode();

        String getRuntimeType();

        String getModelInstanceId();

        String getGraphSpecJson();

        String getCanvasJson();
    }

    @Data
    public static class NodeDebugRequest implements WorkflowDebugRequest {
        private String workflowId;
        private String workflowKeySlug;
        private String workflowName;
        private String workflowType;
        private String projectCode;
        private String runtimeType;
        private String modelInstanceId;
        private String graphSpecJson;
        private String canvasJson;
        private String nodeId;
        private String message;
        private Map<String, Object> state;
    }

    @Data
    public static class DebugRunRequest implements WorkflowDebugRequest {
        private String workflowId;
        private String workflowKeySlug;
        private String workflowName;
        private String workflowType;
        private String projectCode;
        private String runtimeType;
        private String modelInstanceId;
        private String graphSpecJson;
        private String canvasJson;
        private String message;
        private Map<String, Object> inputParams;
        private Map<String, Object> debugOptions;
    }
}
