package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionEventSink;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanEntity;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RuntimeWorkflowDebugService {

    private final RuntimeWorkflowDefinitionService workflowDefinitionService;
    private final RuntimeGraphSpecExecutor graphSpecExecutor;
    private final RuntimeRunLifecycleService runLifecycleService;
    private final RuntimeTraceSpanMapper spanMapper;
    private final ObjectMapper objectMapper;

    public DebugRunResult debugRun(DebugRunRequest request) {
        return debugRun(request, RuntimeGraphSpecExecutionEventSink.NOOP, RuntimeGraphSpecExecutionCancellation.none());
    }

    public DebugRunResult debugRun(DebugRunRequest request,
                                   RuntimeGraphSpecExecutionEventSink eventSink,
                                   RuntimeGraphSpecExecutionCancellation cancellation) {
        DebugRunRequest actual = request == null ? DebugRunRequest.empty() : request;
        RuntimeGraphSpecExecutionEventSink sink = eventSink == null
                ? RuntimeGraphSpecExecutionEventSink.NOOP : eventSink;
        RuntimeGraphSpecExecutionCancellation cancel = cancellation == null
                ? RuntimeGraphSpecExecutionCancellation.none() : cancellation;
        String runId = firstText(text(debugOption(actual.debugOptions(), "runId")),
                "studio-debug-run-" + UUID.randomUUID());
        String traceId = firstText(text(debugOption(actual.debugOptions(), "traceId")), runId);
        long started = System.currentTimeMillis();
        Map<String, Object> context = inputContext(actual.message(), actual.modelInstanceId(), actual.inputParams());
        WorkflowTraceHandle trace = beginWorkflowTrace(traceId, actual.workflowId(), actual.workflowKeySlug(),
                actual.workflowName(), actual.projectCode(), actual.runtimeType(), actual.graphSpecJson(), context);

        GraphSpecResolution resolved = resolveGraphSpec(actual.workflowId(), actual.graphSpecJson());
        if (!resolved.success()) {
            RuntimeGraphSpecExecutionResult failure = failure(resolved.code(), resolved.message(), null, null);
            finishWorkflowTrace(trace, failure, actual, context);
            return toDebugRunResult(runId, traceId, actual, Map.of(), null, failure, started);
        }

        String entryNodeId = text(debugOption(actual.debugOptions(), "entryNodeId"));
        RuntimeGraphSpecExecutionResult execution = StringUtils.hasText(entryNodeId)
                ? graphSpecExecutor.executeFromNode(resolved.graphSpecJson(), context, entryNodeId, sink, cancel)
                : graphSpecExecutor.execute(resolved.graphSpecJson(), context, sink, cancel);
        finishWorkflowTrace(trace, execution, actual, context);
        return toDebugRunResult(runId, traceId, actual, context, resolved.graph(), execution, started);
    }

    public NodeDebugResult debugNode(NodeDebugRequest request) {
        NodeDebugRequest actual = request == null ? NodeDebugRequest.empty() : request;
        long started = System.currentTimeMillis();
        String traceId = "studio-debug-node-" + UUID.randomUUID();
        Map<String, Object> context = stateContext(actual.message(), actual.modelInstanceId(), actual.state());
        WorkflowTraceHandle trace = beginWorkflowTrace(traceId, actual.workflowId(), actual.workflowKeySlug(),
                actual.workflowName(), actual.projectCode(), actual.runtimeType(), actual.graphSpecJson(), context);
        if (!StringUtils.hasText(actual.nodeId())) {
            finishWorkflowTrace(trace, failure("WORKFLOW_DEBUG_NODE_REQUIRED", "debug nodeId is required", null, null),
                    actual.workflowId(), actual.workflowKeySlug(), actual.workflowName(), context);
            return new NodeDebugResult(
                    null,
                    null,
                    false,
                    elapsed(started),
                    Map.of(),
                    Map.of(),
                    null,
                    null,
                    "WORKFLOW_DEBUG_NODE_REQUIRED",
                    "debug nodeId is required",
                    traceId);
        }

        GraphSpecResolution resolved = resolveGraphSpec(actual.workflowId(), actual.graphSpecJson());
        if (!resolved.success()) {
            finishWorkflowTrace(trace, failure(resolved.code(), resolved.message(), actual.nodeId(), null),
                    actual.workflowId(), actual.workflowKeySlug(), actual.workflowName(), context);
            return new NodeDebugResult(
                    actual.nodeId(),
                    null,
                    false,
                    elapsed(started),
                    Map.copyOf(context),
                    outputState(context, null, resolved.code(), resolved.message()),
                    null,
                    null,
                    resolved.code(),
                    resolved.message(),
                    traceId);
        }

        RuntimeGraphSpecExecutionResult execution =
                graphSpecExecutor.executeFromNode(resolved.graphSpecJson(), context, actual.nodeId());
        finishWorkflowTrace(trace, execution, actual.workflowId(), actual.workflowKeySlug(), actual.workflowName(), context);
        GraphSpec.Node node = nodeById(resolved.graph(), firstText(execution.nodeId(), actual.nodeId()));
        String nodeType = firstText(execution.nodeType(), normalizeNodeType(node));
        return new NodeDebugResult(
                firstText(execution.nodeId(), actual.nodeId()),
                nodeType,
                execution.success(),
                elapsed(started),
                Map.copyOf(context),
                outputState(context, execution.answer(), execution.code(), execution.success() ? null : execution.answer()),
                execution.success() ? execution.answer() : null,
                executionRoute(execution),
                execution.success() ? null : execution.code(),
                execution.success() ? null : execution.answer(),
                traceId);
    }

    private DebugRunResult toDebugRunResult(String runId,
                                            String traceId,
                                            DebugRunRequest request,
                                            Map<String, Object> inputContext,
                                            GraphSpec graph,
                                            RuntimeGraphSpecExecutionResult execution,
                                            long started) {
        String status = status(execution);
        Map<String, Object> finalState = outputState(inputContext, execution.answer(), execution.code(),
                execution.success() ? null : execution.answer());
        List<DebugMessage> messages = new ArrayList<>();
        String userMessage = firstText(request.message(), text(inputContext.get("message")), text(inputContext.get("input")));
        if (StringUtils.hasText(userMessage)) {
            messages.add(new DebugMessage("user", userMessage, null, Instant.ofEpochMilli(started).toString()));
        }
        if (StringUtils.hasText(execution.answer())) {
            messages.add(new DebugMessage("assistant", execution.answer(), execution.nodeId(), Instant.now().toString()));
        }
        return new DebugRunResult(
                runId,
                traceId,
                null,
                "WORKFLOW",
                execution.success(),
                status,
                execution.success() ? execution.answer() : null,
                execution.nodeId(),
                messages,
                interactionRequest(execution),
                debugSteps(graph, execution),
                finalState,
                execution.success() ? null : execution.code(),
                execution.success() ? null : execution.answer());
    }

    private List<DebugStepResult> debugSteps(GraphSpec graph, RuntimeGraphSpecExecutionResult execution) {
        List<Map<String, Object>> rawSteps = execution.steps() == null ? List.of() : execution.steps();
        List<DebugStepResult> steps = new ArrayList<>();
        for (int i = 0; i < rawSteps.size(); i++) {
            Map<String, Object> rawStep = rawSteps.get(i);
            String nodeId = firstText(text(rawStep.get("detail")), text(rawStep.get("nodeId")));
            GraphSpec.Node node = nodeById(graph, nodeId);
            boolean failedStep = !execution.success() && nodeId != null && nodeId.equals(execution.nodeId());
            Map<String, Object> output = new LinkedHashMap<>();
            if (nodeId != null && nodeId.equals(execution.nodeId()) && StringUtils.hasText(execution.answer())) {
                output.put("answer", execution.answer());
            }
            steps.add(new DebugStepResult(
                    i,
                    nodeId,
                    firstText(normalizeNodeType(node), nodeId != null && nodeId.equals(execution.nodeId())
                            ? execution.nodeType()
                            : null),
                    node == null ? null : node.getName(),
                    failedStep ? "ERROR" : "SUCCESS",
                    null,
                    null,
                    0L,
                    Map.of(),
                    output,
                    output.isEmpty() ? null : output,
                    Map.of(),
                    Map.of(),
                    "execute-node",
                    null,
                    null,
                    text(rawStep.get("route")),
                    null,
                    null,
                    failedStep ? execution.code() : null,
                    failedStep ? execution.answer() : null));
        }
        return steps;
    }

    private GraphSpecResolution resolveGraphSpec(String workflowId, String graphSpecJson) {
        if (StringUtils.hasText(graphSpecJson)) {
            return parseGraphSpec(graphSpecJson);
        }
        if (!StringUtils.hasText(workflowId)) {
            return GraphSpecResolution.failure("WORKFLOW_DEBUG_GRAPH_REQUIRED",
                    "debug request requires graphSpecJson or workflowId");
        }
        return workflowDefinitionService.findById(workflowId)
                .map(workflow -> {
                    if (!StringUtils.hasText(workflow.getGraphSpecJson())) {
                        return GraphSpecResolution.failure("WORKFLOW_DEBUG_GRAPH_REQUIRED",
                                "workflow has no GraphSpec: " + workflowId);
                    }
                    return parseGraphSpec(workflow.getGraphSpecJson());
                })
                .orElseGet(() -> GraphSpecResolution.failure("WORKFLOW_NOT_FOUND",
                        "workflow not found: " + workflowId));
    }

    private GraphSpecResolution parseGraphSpec(String graphSpecJson) {
        try {
            GraphSpec graph = objectMapper.readValue(graphSpecJson, GraphSpec.class);
            return new GraphSpecResolution(true, graphSpecJson, graph, null, null);
        } catch (Exception ex) {
            return GraphSpecResolution.failure("RUNTIME_WORKFLOW_GRAPH_INVALID",
                    "Workflow GraphSpec JSON is invalid: " + ex.getMessage());
        }
    }

    private RuntimeGraphSpecExecutionResult failure(String code, String message, String nodeId, String nodeType) {
        return new RuntimeGraphSpecExecutionResult(false, code, message, nodeId, nodeType, List.of(), Map.of());
    }

    private WorkflowTraceHandle beginWorkflowTrace(String traceId,
                                                    String workflowId,
                                                    String workflowKeySlug,
                                                    String workflowName,
                                                    String projectCode,
                                                    String runtimeType,
                                                    String graphSpecJson,
                                                    Map<String, Object> input) {
        String rootSpanId = compactId(16);
        LocalDateTime now = LocalDateTime.now();
        RuntimeTraceSpanEntity root = new RuntimeTraceSpanEntity();
        root.setTraceId(traceId);
        root.setSpanId(rootSpanId);
        root.setSpanType("WORKFLOW");
        root.setRuntimeType(firstText(runtimeType, "LANGGRAPH4J"));
        root.setAgentId(workflowId);
        root.setAgentName(workflowName);
        root.setNodeId(workflowId);
        root.setProjectCode(projectCode);
        root.setStatus("RUNNING");
        root.setInputSummary(limit(firstText(text(input.get("message")), text(input.get("input"))), 4000));
        root.setMetadataJson(json(Map.of(
                "sourceType", "WORKFLOW_STUDIO",
                "workflowId", workflowId == null ? "" : workflowId,
                "workflowKeySlug", workflowKeySlug == null ? "" : workflowKeySlug,
                "workflowName", workflowName == null ? "" : workflowName)));
        root.setStartedAt(now);
        root.setCreatedAt(now);
        try { spanMapper.insert(root); } catch (Exception ignored) { }
        runLifecycleService.beginWorkflow(traceId, rootSpanId, "WORKFLOW_STUDIO", workflowId,
                workflowKeySlug, workflowName, projectCode, runtimeType, graphSpecJson, input);
        return new WorkflowTraceHandle(traceId, rootSpanId, root.getId(), now);
    }

    private void finishWorkflowTrace(WorkflowTraceHandle trace,
                                     RuntimeGraphSpecExecutionResult execution,
                                     DebugRunRequest request,
                                     Map<String, Object> input) {
        finishWorkflowTrace(trace, execution, request.workflowId(), request.workflowKeySlug(),
                request.workflowName(), input);
    }

    @SuppressWarnings("unchecked")
    private void finishWorkflowTrace(WorkflowTraceHandle trace,
                                     RuntimeGraphSpecExecutionResult execution,
                                     String workflowId,
                                     String workflowKeySlug,
                                     String workflowName,
                                     Map<String, Object> input) {
        LocalDateTime ended = LocalDateTime.now();
        String status = runStatus(execution);
        if (trace.rootId() != null) {
            RuntimeTraceSpanEntity root = spanMapper.selectById(trace.rootId());
            if (root != null) {
                root.setStatus(status);
                root.setOutputSummary(limit(execution.answer(), 4000));
                root.setErrorCode(execution.success() ? null : execution.code());
                root.setErrorMessage(execution.success() ? null : limit(execution.answer(), 4000));
                root.setLatencyMs((int) Math.min(Integer.MAX_VALUE,
                        java.time.temporal.ChronoUnit.MILLIS.between(trace.startedAt(), ended)));
                root.setEndedAt(ended);
                try { spanMapper.updateById(root); } catch (Exception ignored) { }
            }
        }
        List<Map<String, Object>> steps = execution.steps() == null ? List.of() : execution.steps();
        for (int index = 0; index < steps.size(); index++) {
            Map<String, Object> step = steps.get(index) == null ? Map.of() : steps.get(index);
            String nodeId = firstText(text(step.get("nodeId")), text(step.get("detail")));
            RuntimeTraceSpanEntity child = new RuntimeTraceSpanEntity();
            child.setTraceId(trace.traceId());
            child.setSpanId(compactId(16));
            child.setParentSpanId(trace.rootSpanId());
            child.setSpanType("WORKFLOW_NODE");
            child.setRuntimeType("LANGGRAPH4J");
            child.setAgentId(workflowId);
            child.setAgentName(workflowName);
            child.setNodeId(nodeId);
            boolean failed = !execution.success() && index == steps.size() - 1;
            child.setStatus(failed ? status : "SUCCESS");
            child.setMetadataJson(json(Map.of("step", step, "workflowKeySlug",
                    workflowKeySlug == null ? "" : workflowKeySlug)));
            child.setErrorCode(failed ? execution.code() : null);
            child.setLatencyMs(0);
            child.setStartedAt(ended);
            child.setEndedAt(ended);
            child.setCreatedAt(ended);
            try { spanMapper.insert(child); } catch (Exception ignored) { }
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceType", "WORKFLOW_STUDIO");
        metadata.put("workflowId", workflowId);
        metadata.put("workflowKeySlug", workflowKeySlug);
        metadata.put("workflowName", workflowName);
        metadata.put("nodeCount", steps.size());
        metadata.put("result", execution.metadata());
        runLifecycleService.finishWorkflow(trace.traceId(), execution.success(), execution.code(),
                execution.answer(), steps.size(), metadata);
    }

    private String runStatus(RuntimeGraphSpecExecutionResult execution) {
        if (execution.success()) return "SUCCESS";
        if ("RUNTIME_GRAPH_INTERACTION_WAITING".equalsIgnoreCase(execution.code())) return "WAITING_APPROVAL";
        if (execution.code() != null && execution.code().toUpperCase().contains("TIMEOUT")) return "TIMEOUT";
        return "FAILED";
    }

    private String compactId(int length) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, length);
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception ex) { return "{}"; }
    }

    private String limit(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private record WorkflowTraceHandle(String traceId,
                                       String rootSpanId,
                                       Long rootId,
                                       LocalDateTime startedAt) {
    }

    private Map<String, Object> inputContext(String message, String modelInstanceId, Map<String, Object> inputParams) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (inputParams != null) {
            context.putAll(inputParams);
        }
        if (StringUtils.hasText(modelInstanceId)) {
            context.put("modelInstanceId", modelInstanceId);
        }
        if (StringUtils.hasText(message)) {
            context.put("message", message);
            context.put("input", message);
        } else {
            String input = firstText(text(context.get("input")), text(context.get("message")));
            if (StringUtils.hasText(input)) {
                context.put("input", input);
                context.put("message", input);
            }
        }
        return context;
    }

    private Map<String, Object> stateContext(String message, String modelInstanceId, Map<String, Object> state) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (state != null) {
            context.putAll(state);
        }
        if (StringUtils.hasText(modelInstanceId)) {
            context.put("modelInstanceId", modelInstanceId);
        }
        if (StringUtils.hasText(message)) {
            context.putIfAbsent("message", message);
            context.putIfAbsent("input", message);
        }
        return context;
    }

    private Map<String, Object> outputState(Map<String, Object> inputContext,
                                            String answer,
                                            String code,
                                            String errorMessage) {
        Map<String, Object> state = new LinkedHashMap<>(inputContext == null ? Map.of() : inputContext);
        if (StringUtils.hasText(answer)) {
            state.put("lastOutput", answer);
        }
        if (StringUtils.hasText(code)) {
            state.put("runtimeCode", code);
        }
        if (StringUtils.hasText(errorMessage)) {
            state.put("lastError", errorMessage);
        }
        return state;
    }

    private Object debugOption(Map<String, Object> options, String key) {
        return options == null ? null : options.get(key);
    }

    private Object interactionRequest(RuntimeGraphSpecExecutionResult execution) {
        return execution.metadata() == null ? null : execution.metadata().get("uiRequest");
    }

    private String executionRoute(RuntimeGraphSpecExecutionResult execution) {
        if (execution == null || execution.metadata() == null) {
            return null;
        }
        return firstText(text(execution.metadata().get("lastRoute")), text(execution.metadata().get("route")));
    }

    private String status(RuntimeGraphSpecExecutionResult execution) {
        return mapExecutionStatus(execution);
    }

    /**
     * 将 GraphSpec 执行结果映射为 Debug 会话业务状态。
     * {@code RUNTIME_GRAPH_CANCELLED} 必须为 {@code CANCELLED}，不得落入 {@code ERROR}。
     */
    static String mapExecutionStatus(RuntimeGraphSpecExecutionResult execution) {
        if (execution == null) {
            return "ERROR";
        }
        if (execution.success()) {
            return "SUCCESS";
        }
        if ("RUNTIME_GRAPH_INTERACTION_WAITING".equals(execution.code())) {
            return "WAITING_USER";
        }
        if ("RUNTIME_GRAPH_CANCELLED".equals(execution.code())) {
            return "CANCELLED";
        }
        return "ERROR";
    }

    private GraphSpec.Node nodeById(GraphSpec graph, String nodeId) {
        if (graph == null || graph.getNodes() == null || !StringUtils.hasText(nodeId)) {
            return null;
        }
        return graph.getNodes().stream()
                .filter(node -> node != null && nodeId.equals(node.getId()))
                .findFirst()
                .orElse(null);
    }

    private String normalizeNodeType(GraphSpec.Node node) {
        return node == null ? null : AgentGraphNodeType.normalize(node.getType());
    }

    private long elapsed(long started) {
        return Math.max(0L, System.currentTimeMillis() - started);
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    public record DebugRunRequest(String workflowId,
                                  String workflowKeySlug,
                                  String workflowName,
                                  String workflowType,
                                  String projectCode,
                                  String runtimeType,
                                  String modelInstanceId,
                                  String graphSpecJson,
                                  String canvasJson,
                                  String message,
                                  Map<String, Object> inputParams,
                                  Map<String, Object> debugOptions) {
        private static DebugRunRequest empty() {
            return new DebugRunRequest(null, null, null, null, null, null, null, null, null, null, Map.of(), Map.of());
        }
    }

    public record NodeDebugRequest(String workflowId,
                                   String workflowKeySlug,
                                   String workflowName,
                                   String workflowType,
                                   String projectCode,
                                   String runtimeType,
                                   String modelInstanceId,
                                   String graphSpecJson,
                                   String canvasJson,
                                   String nodeId,
                                   String message,
                                   Map<String, Object> state) {
        private static NodeDebugRequest empty() {
            return new NodeDebugRequest(null, null, null, null, null, null, null, null, null, null, null, Map.of());
        }
    }

    public record DebugRunResult(String runId,
                                 String traceId,
                                 String sessionId,
                                 String targetType,
                                 boolean success,
                                 String status,
                                 String answer,
                                 String currentNodeId,
                                 List<DebugMessage> messages,
                                 Object uiRequest,
                                 List<DebugStepResult> steps,
                                 Map<String, Object> finalState,
                                 String errorCode,
                                 String errorMessage) {
    }

    public record NodeDebugResult(String nodeId,
                                  String nodeType,
                                  boolean success,
                                  long elapsedMs,
                                  Map<String, Object> inputState,
                                  Map<String, Object> outputState,
                                  String nodeOutput,
                                  String lastRoute,
                                  String errorCode,
                                  String errorMessage,
                                  String traceId) {
    }

    public record DebugStepResult(Integer index,
                                  String nodeId,
                                  String nodeType,
                                  String nodeName,
                                  String status,
                                  String startedAt,
                                  String endedAt,
                                  Long elapsedMs,
                                  Map<String, Object> input,
                                  Map<String, Object> output,
                                  Object rawOutput,
                                  Map<String, Object> publishedVariables,
                                  Map<String, Object> statePatch,
                                  String eventType,
                                  Object uiRequest,
                                  Object artifact,
                                  String route,
                                  String condition,
                                  String nextNodeId,
                                  String errorCode,
                                  String errorMessage) {
    }

    public record DebugMessage(String role,
                               String content,
                               String nodeId,
                               String createdAt) {
    }

    private record GraphSpecResolution(boolean success,
                                       String graphSpecJson,
                                       GraphSpec graph,
                                       String code,
                                       String message) {
        private static GraphSpecResolution failure(String code, String message) {
            return new GraphSpecResolution(false, null, null, code, message);
        }
    }
}
