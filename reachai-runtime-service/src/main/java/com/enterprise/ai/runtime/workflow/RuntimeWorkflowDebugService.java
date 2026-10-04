package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionEventSink;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.enterprise.ai.runtime.execution.WorkflowExecutionStatus;
import com.enterprise.ai.runtime.execution.policy.RuntimeEvalExecutionContext;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.trace.WorkflowTraceSanitizer;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.runops.RuntimeRunSnapshots;
import com.enterprise.ai.runtime.trace.RuntimeTraceEvidenceWriter;
import com.enterprise.ai.runtime.trace.RuntimeTraceEvidenceWriter.ChildSpan;
import com.enterprise.ai.runtime.trace.RuntimeTraceRootService;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanTerminationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
    private final RuntimeTraceEvidenceWriter evidence;
    private final ObjectMapper objectMapper;
    private final RuntimeWorkflowDocumentCanonicalizer documentCanonicalizer;
    private final RuntimeTraceRootService rootSpans;
    private final RuntimeTraceSpanTerminationService spanTermination;

    public DebugRunResult debugRun(DebugRunRequest request) {
        return debugRun(request, RuntimeGraphSpecExecutionEventSink.NOOP, RuntimeGraphSpecExecutionCancellation.none());
    }

    public DebugRunResult debugRun(DebugRunRequest request,
                                   RuntimeGraphSpecExecutionEventSink eventSink,
                                   RuntimeGraphSpecExecutionCancellation cancellation) {
        DebugRunRequest actual = request == null ? DebugRunRequest.empty() : request;
        DebugRunReference run = DebugRunReference.fresh();
        try {
            return executeSnapshot(captureDefinition(actual),
                    new DebugInput(actual.message(), actual.inputParams(), actual.debugOptions()), run,
                    text(debugOption(actual.debugOptions(), "entryNodeId")), false, eventSink, cancellation);
        } catch (MissingDebugWorkflow missing) {
            return toDebugRunResult(run.runId(), run.traceId(), actual.message(), Map.of(), null,
                    failure("WORKFLOW_NOT_FOUND", missing.getMessage(), null, null), System.currentTimeMillis());
        }
    }

    /** Resolve saved ownership once; the caller's candidate graph remains an editable debug input. */
    public DebugDefinition captureDefinition(DebugRunRequest request) {
        DebugRunRequest actual = request == null ? DebugRunRequest.empty() : request;
        RuntimeWorkflowDefinitionEntity saved = StringUtils.hasText(actual.workflowId())
                ? workflowDefinitionService.findById(actual.workflowId()).orElseThrow(
                        () -> new MissingDebugWorkflow(actual.workflowId())) : null;
        String graph = firstText(actual.graphSpecJson(), saved == null ? null : saved.getGraphSpecJson());
        if (StringUtils.hasText(graph)) {
            GraphSpecResolution parsed = parseGraphSpec(graph);
            if (parsed.success()) graph = parsed.graphSpecJson();
        }
        return new DebugDefinition(
                saved == null ? null : saved.getId(),
                saved == null ? actual.workflowKeySlug() : saved.getKeySlug(),
                saved == null ? actual.workflowName() : saved.getName(),
                saved == null ? actual.workflowKind() : saved.getWorkflowKind(),
                saved == null ? null : saved.getProjectId(),
                saved == null ? actual.projectCode() : saved.getProjectCode(),
                WorkflowSemanticValues.normalizeExecutionEngine(firstText(
                        saved == null ? actual.executionEngine() : saved.getExecutionEngine(),
                        WorkflowSemanticValues.ENGINE_GRAPH_SPEC)),
                firstText(actual.modelInstanceId(), saved == null ? null : saved.getDefaultModelInstanceId()),
                graph, firstText(actual.canvasJson(), saved == null ? null : saved.getCanvasJson()));
    }

    /** Session creation starts at the graph entry; request options cannot turn it into a continuation. */
    public DebugRunResult startSessionDebug(DebugDefinition definition, DebugInput input, DebugRunReference run,
                                            RuntimeGraphSpecExecutionEventSink eventSink,
                                            RuntimeGraphSpecExecutionCancellation cancellation) {
        return executeSnapshot(definition, input, run, null, false, eventSink, cancellation);
    }

    /** Only the signed internal trial service may provide this server-built identity and audit. */
    public DebugRunResult runReadOnlyTrial(DebugDefinition definition, Map<String, Object> params,
                                           WorkflowExecutionIdentity identity, TrialAudit audit) {
        if (definition == null || audit == null || identity == null
                || identity.source() != WorkflowExecutionIdentity.Source.STUDIO_PROJECT_TEST
                || !identity.authorizeProjectCredential(definition.projectId(), definition.projectCode())) {
            throw new IllegalArgumentException("HTTP_API_TRIAL_IDENTITY_INVALID");
        }
        return executeSnapshot(definition, new DebugInput(null, Map.of("params", params), Map.of()),
                DebugRunReference.fresh(), null, false, RuntimeGraphSpecExecutionEventSink.NOOP,
                RuntimeGraphSpecExecutionCancellation.none(), identity, audit);
    }

    /** The session owner supplies the persisted definition, run and checkpoint after claiming its waiting row. */
    public DebugRunResult resumeSessionDebug(DebugDefinition definition, DebugInput input, DebugContinuation continuation,
                                             RuntimeGraphSpecExecutionEventSink eventSink,
                                             RuntimeGraphSpecExecutionCancellation cancellation) {
        return executeSnapshot(definition, input, continuation.run(), continuation.entryNodeId(), true, eventSink, cancellation);
    }

    private DebugRunResult executeSnapshot(DebugDefinition definition, DebugInput actual, DebugRunReference run,
                                           String entryNodeId, boolean resume,
                                           RuntimeGraphSpecExecutionEventSink eventSink,
                                           RuntimeGraphSpecExecutionCancellation cancellation) {
        return executeSnapshot(definition, actual, run, entryNodeId, resume, eventSink, cancellation,
                WorkflowExecutionIdentity.untrustedDebug(), null);
    }

    private DebugRunResult executeSnapshot(DebugDefinition definition, DebugInput actual, DebugRunReference run,
                                           String entryNodeId, boolean resume,
                                           RuntimeGraphSpecExecutionEventSink eventSink,
                                           RuntimeGraphSpecExecutionCancellation cancellation,
                                           WorkflowExecutionIdentity identity, TrialAudit trial) {
        RuntimeGraphSpecExecutionEventSink sink = eventSink == null
                ? RuntimeGraphSpecExecutionEventSink.NOOP : eventSink;
        RuntimeGraphSpecExecutionCancellation cancel = cancellation == null
                ? RuntimeGraphSpecExecutionCancellation.none() : cancellation;
        String runId = run.runId();
        String traceId = run.traceId();
        boolean evalMode = Boolean.TRUE.equals(debugOption(actual.debugOptions(), "evalMode"))
                || Boolean.TRUE.equals(debugOption(actual.debugOptions(), "sandboxSideEffects"));
        RuntimeEvalExecutionContext evaluation = evalMode
                ? RuntimeEvalExecutionContext.readOnly("workflow-debug:" + runId, "workflow", null)
                : RuntimeEvalExecutionContext.none();
        long started = System.currentTimeMillis();
        Map<String, Object> context = inputContext(actual.message(), definition.modelInstanceId(), actual.inputParams());
        GraphSpecResolution resolved = resolveGraphSpec(definition.graphSpecJson());
        WorkflowTraceHandle trace = resume
                ? continueOrBeginWorkflowTrace(traceId, definition, context)
                : trial == null ? beginWorkflowTrace(traceId, definition, context)
                : beginReadOnlyTrialTrace(traceId, definition, context, identity, trial);

        if (!resolved.success()) {
            RuntimeGraphSpecExecutionResult failure = failure(resolved.code(), resolved.message(), null, null);
            finishWorkflowTrace(trace, failure);
            return toDebugRunResult(runId, traceId, actual.message(), Map.of(), null, failure, started);
        }

        RuntimeGraphSpecExecutionResult execution;
        try {
            execution = StringUtils.hasText(entryNodeId)
                ? graphSpecExecutor.executeFromNode(
                        resolved.graphSpecJson(), context, entryNodeId, sink, cancel, evaluation)
                : graphSpecExecutor.execute(
                        resolved.graphSpecJson(), context, sink, cancel, identity, evaluation);
        } catch (RuntimeException unexpected) {
            if (trial == null) throw unexpected;
            execution = failure("HTTP_API_TRIAL_RESULT_UNCONFIRMED",
                    "试运行结果未确认，请先核对 Run/Trace，勿直接重试", null, null);
        }
        finishWorkflowTrace(trace, execution);
        return toDebugRunResult(runId, traceId, actual.message(), context, resolved.graph(), execution, started);
    }

    public NodeDebugResult debugNode(NodeDebugRequest request) {
        NodeDebugRequest actual = request == null ? NodeDebugRequest.empty() : request;
        long started = System.currentTimeMillis();
        String traceId = "studio-debug-node-" + UUID.randomUUID();
        DebugDefinition definition;
        try {
            definition = captureDefinition(new DebugRunRequest(actual.workflowId(), actual.workflowKeySlug(),
                    actual.workflowName(), actual.workflowKind(), actual.projectCode(), actual.executionEngine(),
                    actual.modelInstanceId(), actual.graphSpecJson(), actual.canvasJson(), actual.message(),
                    actual.state(), Map.of()));
        } catch (MissingDebugWorkflow missing) {
            return new NodeDebugResult(actual.nodeId(), null, false, elapsed(started), Map.of(), Map.of(),
                    null, null, "WORKFLOW_NOT_FOUND", missing.getMessage(), traceId);
        }
        Map<String, Object> context = stateContext(actual.message(), definition.modelInstanceId(), actual.state());
        WorkflowTraceHandle trace = beginWorkflowTrace(traceId, definition, context);
        if (!StringUtils.hasText(actual.nodeId())) {
            finishWorkflowTrace(trace, failure("WORKFLOW_DEBUG_NODE_REQUIRED", "debug nodeId is required", null, null));
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

        GraphSpecResolution resolved = resolveGraphSpec(definition.graphSpecJson());
        if (!resolved.success()) {
            finishWorkflowTrace(trace, failure(resolved.code(), resolved.message(), actual.nodeId(), null));
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
        finishWorkflowTrace(trace, execution);
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
                                            String message,
                                            Map<String, Object> inputContext,
                                            GraphSpec graph,
                                            RuntimeGraphSpecExecutionResult execution,
                                            long started) {
        String status = status(execution);
        Map<String, Object> snapshotBase = execution.resumeCheckpoint() != null && !execution.resumeCheckpoint().isEmpty()
                ? execution.resumeCheckpoint()
                : inputContext;
        Map<String, Object> stateSnapshot = outputState(snapshotBase, execution.answer(), execution.code(),
                execution.success() ? null : execution.answer());
        List<DebugMessage> messages = new ArrayList<>();
        String userMessage = firstText(message, text(inputContext.get("message")), text(inputContext.get("input")));
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
                stateSnapshot,
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
            boolean waitingStep = execution.isWaitingUser()
                    && nodeId != null
                    && nodeId.equals(execution.nodeId());
            boolean failedStep = !execution.success() && !execution.isWaitingUser()
                    && nodeId != null && nodeId.equals(execution.nodeId());
            Map<String, Object> output = new LinkedHashMap<>();
            if (nodeId != null && nodeId.equals(execution.nodeId()) && StringUtils.hasText(execution.answer())) {
                output.put("answer", execution.answer());
            }
            if (waitingStep && execution.uiRequest() != null) {
                output.put("uiRequest", execution.uiRequest());
            }
            if (waitingStep && execution.interactionId() != null) {
                output.put("interactionId", execution.interactionId());
            }
            String stepStatus = waitingStep ? "WAITING" : (failedStep ? "ERROR" : "SUCCESS");
            steps.add(new DebugStepResult(
                    i,
                    nodeId,
                    firstText(normalizeNodeType(node), nodeId != null && nodeId.equals(execution.nodeId())
                            ? execution.nodeType()
                            : null),
                    node == null ? null : node.getName(),
                    stepStatus,
                    null,
                    null,
                    0L,
                    Map.of(),
                    output,
                    output.isEmpty() ? null : output,
                    Map.of(),
                    Map.of(),
                    "execute-node",
                    waitingStep ? execution.uiRequest() : null,
                    null,
                    text(rawStep.get("route")),
                    null,
                    null,
                    failedStep ? execution.code() : null,
                    failedStep ? execution.answer() : null));
        }
        return steps;
    }

    private GraphSpecResolution resolveGraphSpec(String graphSpecJson) {
        if (StringUtils.hasText(graphSpecJson)) {
            return parseGraphSpec(graphSpecJson);
        }
        return GraphSpecResolution.failure("WORKFLOW_DEBUG_GRAPH_REQUIRED",
                "debug request requires graphSpecJson or a workflow with GraphSpec");
    }

    private GraphSpecResolution parseGraphSpec(String graphSpecJson) {
        try {
            String canonicalJson = documentCanonicalizer.canonicalizeGraphSpecJson(graphSpecJson);
            GraphSpec graph = objectMapper.readValue(canonicalJson, GraphSpec.class);
            return new GraphSpecResolution(true, canonicalJson, graph, null, null);
        } catch (Exception ex) {
            return GraphSpecResolution.failure("RUNTIME_WORKFLOW_GRAPH_INVALID",
                    "Workflow GraphSpec JSON is invalid: " + ex.getMessage());
        }
    }

    private RuntimeGraphSpecExecutionResult failure(String code, String message, String nodeId, String nodeType) {
        return new RuntimeGraphSpecExecutionResult(false, code, message, nodeId, nodeType, List.of(), Map.of());
    }

    private WorkflowTraceHandle continueOrBeginWorkflowTrace(String traceId,
                                                             DebugDefinition definition,
                                                             Map<String, Object> context) {
        var existingRoot = rootSpans.resumeWorkflow(traceId);
        if (existingRoot != null) {
            return new WorkflowTraceHandle(existingRoot, definition, null);
        }
        return beginWorkflowTrace(traceId, definition, context);
    }

    private WorkflowTraceHandle beginWorkflowTrace(String traceId,
                                                    DebugDefinition definition,
                                                    Map<String, Object> input) {
        String rootSpanId = compactId(16);
        LocalDateTime now = LocalDateTime.now();
        var root = rootSpans.startBestEffort(RuntimeTraceRootService.Start.builder()
                .traceId(traceId).spanId(rootSpanId).spanType("WORKFLOW")
                .runtimeType(definition.executionEngine())
                .agentId(definition.workflowId()).agentName(definition.workflowName()).nodeId(definition.workflowId())
                .projectCode(definition.projectCode()).appId(firstText(text(input.get("appId")), definition.projectCode()))
                .input(input).metadataJson(json(Map.of(
                "sourceType", "WORKFLOW_STUDIO",
                "workflowId", definition.workflowId() == null ? "" : definition.workflowId(),
                "workflowKeySlug", definition.workflowKeySlug() == null ? "" : definition.workflowKeySlug(),
                "workflowName", definition.workflowName() == null ? "" : definition.workflowName())))
                .startedAt(now).build());
        runLifecycleService.beginWorkflow(traceId, rootSpanId, "WORKFLOW_STUDIO", new RuntimeRunSnapshots.Workflow(
                definition.workflowId(), definition.workflowKeySlug(), definition.workflowName(), definition.projectId(),
                definition.projectCode(), definition.executionEngine(), definition.graphSpecJson()), input);
        return new WorkflowTraceHandle(root, definition, null);
    }

    private WorkflowTraceHandle beginReadOnlyTrialTrace(String traceId, DebugDefinition definition,
                                                       Map<String, Object> input,
                                                       WorkflowExecutionIdentity identity, TrialAudit trial) {
        String rootSpanId = compactId(16);
        Map<String, Object> auditMetadata = trial.metadata();
        var root = rootSpans.startBestEffort(RuntimeTraceRootService.Start.builder()
                .traceId(traceId).spanId(rootSpanId).spanType("WORKFLOW")
                .runtimeType(definition.executionEngine())
                .agentId(definition.workflowId()).agentName(definition.workflowName()).nodeId(definition.workflowId())
                .projectCode(definition.projectCode()).appId(definition.projectCode())
                .input(Map.of("inputKeys", trial.inputKeys()))
                .metadataJson(json(auditMetadata))
                .startedAt(LocalDateTime.now()).build());
        runLifecycleService.beginWorkflowTrial(traceId, rootSpanId,
                new RuntimeRunSnapshots.Workflow(definition.workflowId(), definition.workflowKeySlug(),
                        definition.workflowName(), definition.projectId(), definition.projectCode(),
                        definition.executionEngine(), definition.graphSpecJson()),
                Map.of("inputKeys", trial.inputKeys()), identity, auditMetadata);
        return new WorkflowTraceHandle(root, definition, trial);
    }

    @SuppressWarnings("unchecked")
    private void finishWorkflowTrace(WorkflowTraceHandle trace,
                                     RuntimeGraphSpecExecutionResult execution) {
        DebugDefinition definition = trace.definition();
        String workflowId = definition.workflowId();
        String workflowKeySlug = definition.workflowKeySlug();
        String workflowName = definition.workflowName();
        LocalDateTime ended = LocalDateTime.now();
        String status = traceSpanStatus(execution);
        String safeAnswer = trace.trial() == null ? execution.answer()
                : execution.success() ? "Read-only saved draft trial completed" : execution.code();
        if (!rootSpans.finishBestEffort(trace.root(),
                new RuntimeTraceRootService.Completion(status, execution.code(), safeAnswer, ended, null))) return;
        List<Map<String, Object>> nodeTraces = canonicalNodeTraces(execution);
        for (int index = 0; index < nodeTraces.size(); index++) {
            Map<String, Object> nodeTrace = nodeTraces.get(index);
            String nodeId = firstText(text(nodeTrace.get("nodeId")), text(nodeTrace.get("detail")));
            ChildSpan.ChildSpanBuilder child = ChildSpan.builder();
            child.traceId(trace.root().traceId());
            child.spanId(compactId(16));
            child.parentSpanId(trace.root().spanId());
            child.spanType("WORKFLOW_NODE");
            child.runtimeType("LANGGRAPH4J");
            child.agentId(workflowId);
            child.agentName(workflowName);
            var scope = trace.root().scope();
            child.projectCode(scope.projectCode());
            child.tenantId(scope.tenantId());
            child.appId(scope.appId());
            child.nodeId(nodeId);
            child.toolName(text(nodeTrace.get("qualifiedName")));
            String nodeStatus = firstText(text(nodeTrace.get("status")), "SUCCESS").toUpperCase();
            boolean nodeWaiting = "WAITING_USER".equals(nodeStatus) || "WAITING".equals(nodeStatus);
            boolean failed = "FAILED".equals(nodeStatus) || "ERROR".equals(nodeStatus)
                    || "TIMEOUT".equals(nodeStatus);
            child.status(nodeWaiting ? "WAITING_USER" : (failed ? nodeStatus : nodeStatus));
            Map<String, Object> childMeta = new LinkedHashMap<>(
                    WorkflowTraceSanitizer.sanitizeDebugStepMetadata(nodeTrace));
            childMeta.put("workflowKeySlug", workflowKeySlug == null ? "" : workflowKeySlug);
            if (nodeWaiting && execution.interactionId() != null) {
                childMeta.put("interactionId", execution.interactionId());
            }
            child.metadataJson(json(childMeta));
            child.errorCode(failed
                    ? firstText(text(nodeTrace.get("failureCode")), execution.code())
                    : null);
            child.latencyMs(nonNegativeInt(nodeTrace.get("latencyMs")));
            LocalDateTime nodeStarted = epochMillis(nodeTrace.get("startedAt"), ended);
            LocalDateTime nodeEnded = epochMillis(nodeTrace.get("endedAt"), ended);
            child.startedAt(nodeStarted);
            child.endedAt(nodeWaiting ? null : nodeEnded);
            child.createdAt(nodeStarted);
            try {
                if (!nodeWaiting && StringUtils.hasText(nodeId)) {
                    spanTermination.finishWaitingWorkflowNode(trace.root().traceId(), trace.root().spanId(), nodeId,
                            nodeStatus, failed ? firstText(text(nodeTrace.get("failureCode")), execution.code()) : null,
                            nodeEnded);
                }
                evidence.appendChild(child.build());
            } catch (Exception ignored) { }
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceType", trace.trial() == null ? "WORKFLOW_STUDIO" : "STUDIO_READ_ONLY_TRIAL");
        metadata.put("workflowId", workflowId);
        metadata.put("workflowKeySlug", workflowKeySlug);
        metadata.put("workflowName", workflowName);
        metadata.put("nodeCount", nodeTraces.size());
        if (trace.trial() != null) metadata.putAll(trace.trial().metadata());
        if (execution.interactionId() != null) {
            metadata.put("interactionId", execution.interactionId());
        }
        runLifecycleService.finishWorkflow(trace.root().traceId(), execution.success(), execution.code(),
                safeAnswer, nodeTraces.size(), metadata);
    }

    private String traceSpanStatus(RuntimeGraphSpecExecutionResult execution) {
        if (execution.success()) return "SUCCESS";
        if (execution.isWaitingUser()
                || "RUNTIME_GRAPH_INTERACTION_WAITING".equalsIgnoreCase(execution.code())) {
            return "WAITING_USER";
        }
        if ("RUNTIME_GRAPH_CANCELLED".equalsIgnoreCase(execution.code())) return "CANCELLED";
        if (execution.code() != null && execution.code().toUpperCase().contains("TIMEOUT")) return "TIMEOUT";
        return "FAILED";
    }

    private Map<String, Object> nodeTrace(Map<String, Object> step, String nodeId) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("nodeId", nodeId);
        trace.put("nodeType", step.get("nodeType"));
        trace.put("status", step.get("status"));
        trace.put("failureCode", step.get("failureCode"));
        trace.put("traceSummary", step.get("traceSummary"));
        return trace;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> canonicalNodeTraces(RuntimeGraphSpecExecutionResult execution) {
        if (execution != null && execution.metadata() != null) {
            Object projected = execution.metadata().get("workflowNodeTraces");
            if (projected instanceof List<?> list && !list.isEmpty()) {
                List<Map<String, Object>> traces = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        traces.add(new LinkedHashMap<>((Map<String, Object>) map));
                    }
                }
                if (!traces.isEmpty()) {
                    return List.copyOf(traces);
                }
            }
        }
        List<Map<String, Object>> steps = execution == null || execution.steps() == null
                ? List.of() : execution.steps();
        List<Map<String, Object>> legacy = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            Map<String, Object> safeStep = step == null ? Map.of() : step;
            String nodeId = firstText(text(safeStep.get("nodeId")), text(safeStep.get("detail")));
            legacy.add(nodeTrace(safeStep, nodeId));
        }
        return List.copyOf(legacy);
    }

    private LocalDateTime epochMillis(Object raw, LocalDateTime fallback) {
        if (raw instanceof Number number && number.longValue() > 0L) {
            return Instant.ofEpochMilli(number.longValue())
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime();
        }
        return fallback;
    }

    private Integer nonNegativeInt(Object raw) {
        if (!(raw instanceof Number number)) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, number.longValue()));
    }

    private String compactId(int length) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, length);
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception ex) { return "{}"; }
    }

    private record WorkflowTraceHandle(RuntimeTraceRootService.Handle root, DebugDefinition definition,
                                       TrialAudit trial) {
    }

    public record TrialAudit(String actorId, String revision, long apiId, String qualifiedName,
                             String environment, String acceptedContractHash, String sourceSetRevision,
                             String graphSha256, List<String> inputKeys, String assetType, String methodName,
                             String currentContractHash, String sourceContractHash) {
        public TrialAudit(String actorId, String revision, long apiId, String qualifiedName,
                          String environment, String acceptedContractHash, String sourceSetRevision,
                          String graphSha256, List<String> inputKeys) {
            this(actorId, revision, apiId, qualifiedName, environment, acceptedContractHash, sourceSetRevision,
                    graphSha256, inputKeys, "HTTP_API", null, null, null);
        }
        Map<String, Object> metadata() {
            if ("BUSINESS_METHOD".equals(assetType)) {
                return Map.ofEntries(Map.entry("sourceType", "STUDIO_READ_ONLY_TRIAL"),
                        Map.entry("assetType", assetType), Map.entry("platformActorId", actorId),
                        Map.entry("identityMode", "STUDIO_PROJECT_TEST"), Map.entry("draftRevision", revision),
                        Map.entry("methodName", methodName), Map.entry("methodQualifiedName", qualifiedName),
                        Map.entry("currentContractHash", currentContractHash), Map.entry("acceptedContractHash", acceptedContractHash),
                        Map.entry("sourceContractHash", sourceContractHash), Map.entry("graphSha256", graphSha256),
                        Map.entry("inputKeys", inputKeys));
            }
            return Map.of("sourceType", "STUDIO_READ_ONLY_TRIAL", "platformActorId", actorId,
                    "draftRevision", revision, "apiId", apiId, "apiQualifiedName", qualifiedName,
                    "environment", environment, "acceptedContractHash", acceptedContractHash,
                    "sourceSetRevision", sourceSetRevision, "graphSha256", graphSha256, "inputKeys", inputKeys);
        }
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
            return WorkflowExecutionStatus.FAILED.name();
        }
        return execution.executionStatus().name();
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

    /** Frozen debug target and candidate; metadata is not an execution identity or an ACL grant. */
    public record DebugDefinition(String workflowId, String workflowKeySlug, String workflowName,
                                  String workflowKind, Long projectId, String projectCode, String executionEngine,
                                  String modelInstanceId, String graphSpecJson, String canvasJson) {
    }

    public record DebugInput(String message, Map<String, Object> inputParams, Map<String, Object> debugOptions) {
    }

    /** Internal run facts; public DebugRunRequest never deserializes this contract. */
    public record DebugRunReference(String runId, String traceId) {
        public DebugRunReference {
            if (!StringUtils.hasText(runId) || !StringUtils.hasText(traceId)) {
                throw new IllegalArgumentException("debug runId and traceId are required");
            }
        }

        private static DebugRunReference fresh() {
            String id = "studio-debug-run-" + UUID.randomUUID();
            return new DebugRunReference(id, id);
        }
    }

    public record DebugContinuation(DebugRunReference run, String entryNodeId) {
        public DebugContinuation {
            java.util.Objects.requireNonNull(run, "run");
            if (!StringUtils.hasText(entryNodeId)) {
                throw new IllegalArgumentException("debug continuation nodeId is required");
            }
        }
    }

    private static final class MissingDebugWorkflow extends IllegalArgumentException {
        private MissingDebugWorkflow(String workflowId) {
            super("workflow not found: " + workflowId);
        }
    }

    public record DebugRunRequest(String workflowId,
                                  String workflowKeySlug,
                                  String workflowName,
                                  String workflowKind,
                                  String projectCode,
                                  String executionEngine,
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
                                   String workflowKind,
                                   String projectCode,
                                   String executionEngine,
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
                                 Map<String, Object> stateSnapshot,
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
