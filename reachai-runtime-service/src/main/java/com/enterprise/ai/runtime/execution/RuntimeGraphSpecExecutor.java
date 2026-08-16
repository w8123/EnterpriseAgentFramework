package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.common.response.BusinessResponseEnvelope;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient.PageBridgeExecutionRequest;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient.PageBridgeExecutionResponse;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient.KnowledgeHit;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalData;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalRequest;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalResult;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatData;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest.ChatMessage;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import com.enterprise.ai.runtime.execution.context.WorkflowOutputAliasWriter;
import com.enterprise.ai.runtime.execution.context.WorkflowVariableNamespaces;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpClient;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpClient.HttpExecutionRequest;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpClient.HttpExecutionResult;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionCodes;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionNodeHandler;
import com.enterprise.ai.runtime.memory.RuntimeBusinessMemoryHydrationService;
import com.enterprise.ai.runtime.memory.RuntimeBusinessMemoryHydrationService.HydrationBatch;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowInputContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Workflow GraphSpec 线性执行器。
 * <p>
 * 取消语义为<strong>节点边界协作式取消</strong>：
 * <ul>
 *   <li>节点前后检查 cancellation；取消后停止后续节点，返回 {@code RUNTIME_GRAPH_CANCELLED}</li>
 *   <li>LLM / TOOL / CAPABILITY / PAGE_ACTION / 模型分类与参数抽取在同步 Feign 调用前后检查取消；
 *       调用返回后若已取消，丢弃成功结果且不发 public delta</li>
 *   <li>OpenFeign 同步 HTTP（{@code modelServiceClient.chat}、{@code capabilityClient.executeTool}、
 *       {@code controlClient.executePageBridge}）在现有技术栈下<strong>无法硬中断</strong>进行中的 socket；
 *       8s heartbeat SLA 只描述「发现连接断开并设置取消信号」的时限，不承诺节点内 HTTP 在 8s 内停止</li>
 *   <li>已发生的外部副作用不回滚；取消不得伪装为 TIMEOUT</li>
 * </ul>
 */
@Service
public class RuntimeGraphSpecExecutor {

    private static final Pattern TEMPLATE_TOKEN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");
    private static final int MAX_LINEAR_STEPS = 64;
    /** Human confirmation is intentionally independent from the host action execution budget. */
    private static final int DEFAULT_PAGE_BRIDGE_CONFIRMATION_TIMEOUT_MS = 90_000;
    private static final int DEFAULT_PAGE_BRIDGE_EXECUTION_TIMEOUT_MS = 30_000;
    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final long MAX_RETRY_BACKOFF_MS = 10_000L;
    private static final Set<String> EXECUTABLE_NODE_TYPES = Set.of(
            "USER_INPUT",
            "INTENT_CLASSIFIER",
            "IF_ELSE",
            "ANSWER",
            "LLM",
            "TOOL",
            "CAPABILITY",
            "PAGE_ACTION",
            "INTERACTION",
            "PARAMETER_EXTRACT",
            "VARIABLE_ASSIGN",
            "TEMPLATE",
            "VARIABLE_AGGREGATOR",
            "KNOWLEDGE_RETRIEVAL",
            "HTTP_REQUEST",
            "LOOP");

    private final ObjectMapper objectMapper;
    private final RuntimeModelServiceClient modelServiceClient;
    private final RuntimeCapabilityCatalogClient capabilityClient;
    private final RuntimeControlCatalogClient controlClient;
    private final RuntimeKnowledgeRetrievalClient knowledgeClient;
    private final WorkflowHttpClient httpClient;
    private final RuntimeBusinessMemoryHydrationService businessMemoryHydrationService;

    public RuntimeGraphSpecExecutor(ObjectMapper objectMapper,
                                    RuntimeModelServiceClient modelServiceClient,
                                    RuntimeCapabilityCatalogClient capabilityClient,
                                    RuntimeControlCatalogClient controlClient) {
        this(objectMapper, modelServiceClient, capabilityClient, controlClient, null, null, null);
    }

    public RuntimeGraphSpecExecutor(ObjectMapper objectMapper,
                                    RuntimeModelServiceClient modelServiceClient,
                                    RuntimeCapabilityCatalogClient capabilityClient,
                                    RuntimeControlCatalogClient controlClient,
                                    @Autowired(required = false) RuntimeKnowledgeRetrievalClient knowledgeClient,
                                    @Autowired(required = false) WorkflowHttpClient httpClient) {
        this(objectMapper, modelServiceClient, capabilityClient, controlClient,
                knowledgeClient, httpClient, null);
    }

    @Autowired
    public RuntimeGraphSpecExecutor(ObjectMapper objectMapper,
                                    RuntimeModelServiceClient modelServiceClient,
                                    RuntimeCapabilityCatalogClient capabilityClient,
                                    RuntimeControlCatalogClient controlClient,
                                    @Autowired(required = false) RuntimeKnowledgeRetrievalClient knowledgeClient,
                                    @Autowired(required = false) WorkflowHttpClient httpClient,
                                    @Autowired(required = false)
                                    RuntimeBusinessMemoryHydrationService businessMemoryHydrationService) {
        this.objectMapper = objectMapper;
        this.modelServiceClient = modelServiceClient;
        this.capabilityClient = capabilityClient;
        this.controlClient = controlClient;
        this.knowledgeClient = knowledgeClient;
        this.httpClient = httpClient;
        this.businessMemoryHydrationService = businessMemoryHydrationService;
    }

    /**
     * Immutable set of node types that currently have a real Runtime handler.
     * Product openness (Studio / publish / AI authoring) is decided by the node capability registry.
     */
    public static Set<String> handledNodeTypes() {
        return EXECUTABLE_NODE_TYPES;
    }

    public static final String TRUSTED_IDENTITY_CONTEXT_KEY = "__workflowExecutionIdentity";

    public RuntimeGraphSpecExecutionResult execute(String graphSpecJson, Map<String, Object> request) {
        return execute(graphSpecJson, request, null,
                RuntimeGraphSpecExecutionEventSink.NOOP, RuntimeGraphSpecExecutionCancellation.none(),
                WorkflowExecutionIdentity.untrustedDebug());
    }

    public RuntimeGraphSpecExecutionResult execute(String graphSpecJson,
                                                   Map<String, Object> request,
                                                   WorkflowExecutionIdentity identity) {
        return execute(graphSpecJson, request, null,
                RuntimeGraphSpecExecutionEventSink.NOOP, RuntimeGraphSpecExecutionCancellation.none(),
                identity);
    }

    public RuntimeGraphSpecExecutionResult execute(String graphSpecJson,
                                                   Map<String, Object> request,
                                                   RuntimeGraphSpecExecutionEventSink sink,
                                                   RuntimeGraphSpecExecutionCancellation cancellation) {
        return execute(graphSpecJson, request, null, sink, cancellation,
                WorkflowExecutionIdentity.untrustedDebug());
    }

    public RuntimeGraphSpecExecutionResult execute(String graphSpecJson,
                                                   Map<String, Object> request,
                                                   RuntimeGraphSpecExecutionEventSink sink,
                                                   RuntimeGraphSpecExecutionCancellation cancellation,
                                                   WorkflowExecutionIdentity identity) {
        return execute(graphSpecJson, request, null, sink, cancellation, identity);
    }

    public RuntimeGraphSpecExecutionResult executeFromNode(String graphSpecJson,
                                                           Map<String, Object> request,
                                                           String entryNodeId) {
        return execute(graphSpecJson, request, entryNodeId,
                RuntimeGraphSpecExecutionEventSink.NOOP, RuntimeGraphSpecExecutionCancellation.none(),
                WorkflowExecutionIdentity.untrustedDebug());
    }

    public RuntimeGraphSpecExecutionResult executeFromNode(String graphSpecJson,
                                                           Map<String, Object> request,
                                                           String entryNodeId,
                                                           RuntimeGraphSpecExecutionEventSink sink,
                                                           RuntimeGraphSpecExecutionCancellation cancellation) {
        return execute(graphSpecJson, request, entryNodeId, sink, cancellation,
                WorkflowExecutionIdentity.untrustedDebug());
    }

    public RuntimeGraphSpecExecutionResult executeFromNode(String graphSpecJson,
                                                           Map<String, Object> request,
                                                           String entryNodeId,
                                                           RuntimeGraphSpecExecutionEventSink sink,
                                                           RuntimeGraphSpecExecutionCancellation cancellation,
                                                           WorkflowExecutionIdentity identity) {
        return execute(graphSpecJson, request, entryNodeId, sink, cancellation, identity);
    }

    private RuntimeGraphSpecExecutionResult execute(String graphSpecJson,
                                                    Map<String, Object> request,
                                                    String entryOverride,
                                                    RuntimeGraphSpecExecutionEventSink sink,
                                                    RuntimeGraphSpecExecutionCancellation cancellation,
                                                    WorkflowExecutionIdentity identity) {
        RuntimeGraphSpecExecutionEventSink eventSink = sink == null
                ? RuntimeGraphSpecExecutionEventSink.NOOP : sink;
        RuntimeGraphSpecExecutionCancellation cancel = cancellation == null
                ? RuntimeGraphSpecExecutionCancellation.none() : cancellation;
        GraphSpec graph;
        try {
            graph = objectMapper.readValue(graphSpecJson, GraphSpec.class);
        } catch (Exception ex) {
            return failure("RUNTIME_WORKFLOW_GRAPH_INVALID", "Workflow GraphSpec JSON is invalid: " + ex.getMessage(),
                    null, null);
        }
        if (graph == null || graph.getNodes() == null || graph.getNodes().isEmpty()) {
            return failure("RUNTIME_GRAPH_NODE_EMPTY", "GraphSpec requires at least one node", null, null);
        }
        if (!Integer.valueOf(2).equals(graph.getSchemaVersion())) {
            return failure("RUNTIME_GRAPH_SCHEMA_VERSION_INVALID", "GraphSpec schemaVersion must be 2", null, null);
        }
        for (GraphSpec.Node node : graph.getNodes()) {
            AgentGraphNodeType type = node == null ? null : AgentGraphNodeType.find(node.getType()).orElse(null);
            if (type == null || !type.type().equals(node.getType())) {
                return failure("RUNTIME_GRAPH_NODE_TYPE_INVALID",
                        "GraphSpec node.type must use a canonical value: " + (node == null ? null : node.getType()),
                        node == null ? null : node.getId(), node == null ? null : node.getType());
            }
        }
        String declaredEntry = text(graph.getEntryNodeId());
        if (!StringUtils.hasText(declaredEntry)) {
            return failure("RUNTIME_GRAPH_ENTRY_MISSING", "GraphSpec entry is required", null, null);
        }

        Map<String, GraphSpec.Node> nodesById = new LinkedHashMap<>();
        for (GraphSpec.Node node : graph.getNodes()) {
            if (node != null && StringUtils.hasText(node.getId())) {
                nodesById.put(node.getId().trim(), node);
            }
        }
        if (!nodesById.containsKey(declaredEntry)) {
            return failure("RUNTIME_GRAPH_ENTRY_INVALID",
                    "GraphSpec entry node does not exist: " + declaredEntry, declaredEntry, null);
        }
        Set<String> exitNodeIds = new LinkedHashSet<>();
        for (String exitNodeId : graph.getExitNodeIds()) {
            String exit = text(exitNodeId);
            if (!StringUtils.hasText(exit) || !nodesById.containsKey(exit)) {
                return failure("RUNTIME_GRAPH_EXIT_INVALID",
                        "GraphSpec exit node does not exist: " + exit, exit, null);
            }
            exitNodeIds.add(exit);
        }
        if (exitNodeIds.isEmpty()) {
            return failure("RUNTIME_GRAPH_EXIT_MISSING",
                    "GraphSpec requires at least one exitNodeId", null, null);
        }
        for (GraphSpec.Edge edge : graph.getEdges() == null ? List.<GraphSpec.Edge>of() : graph.getEdges()) {
            if (edge == null
                    || !nodesById.containsKey(text(edge.getFrom()))
                    || !nodesById.containsKey(text(edge.getTo()))) {
                return failure("RUNTIME_GRAPH_EDGE_INVALID",
                        "GraphSpec edges must connect real nodes; use entryNodeId/exitNodeIds for boundaries",
                        null, null);
            }
        }

        String entry = firstText(text(entryOverride), declaredEntry);
        if (!nodesById.containsKey(entry)) {
            return failure("RUNTIME_GRAPH_ENTRY_INVALID", "GraphSpec entry node does not exist: " + entry, entry, null);
        }

        Map<String, Object> context = initialContext(request == null ? Map.of() : request);
        // Trusted identity is never taken from business maps / model args.
        context.remove(TRUSTED_IDENTITY_CONTEXT_KEY);
        context.put(TRUSTED_IDENTITY_CONTEXT_KEY,
                identity == null ? WorkflowExecutionIdentity.untrustedDebug() : identity);
        List<Map<String, Object>> steps = new ArrayList<>();
        List<Map<String, Object>> nodeTraces = new ArrayList<>();
        String currentNodeId = entry;
        RuntimeGraphSpecExecutionResult lastResult = null;
        for (int index = 0; index < MAX_LINEAR_STEPS && StringUtils.hasText(currentNodeId); index++) {
            if (cancel.isCancelled()) {
                eventSink.onExecutionCancelled(Map.of("currentNodeId", currentNodeId));
                return withLiveContext(withSteps(failure("RUNTIME_GRAPH_CANCELLED", "Workflow execution cancelled",
                        currentNodeId, null), steps, nodeTraces), context);
            }
            GraphSpec.Node node = nodesById.get(currentNodeId);
            if (node == null) {
                return withLiveContext(withSteps(failure("RUNTIME_GRAPH_NEXT_NODE_INVALID",
                        "GraphSpec next node does not exist: " + currentNodeId,
                        currentNodeId,
                        null), steps, nodeTraces), context);
            }
            String nodeType = node.getType();
            String nodeName = firstText(node.getName(), node.getId());
            long nodeStartedAt = System.currentTimeMillis();
            eventSink.onNodeStarted(node.getId(), nodeType, nodeName, safeNodePayload(node, nodeType, null));
            RuntimeGraphSpecExecutionResult nodeResult = executeNodeWithPolicies(
                    node, context, graph, eventSink, cancel, nodesById);
            long nodeEndedAt = System.currentTimeMillis();
            long elapsedMs = Math.max(0L, nodeEndedAt - nodeStartedAt);
            steps.addAll(nodeResult.steps());
            nodeTraces.add(buildInternalNodeTrace(node, nodeType, nodeResult, nodeStartedAt, nodeEndedAt, elapsedMs));
            // Cooperative cancellation：停止后续节点与可取消资源；不回滚当前节点已发生的外部副作用。
            // 节点内同步调用返回后也会把取消映射为 RUNTIME_GRAPH_CANCELLED，禁止落入 onNodeFailed。
            if (cancel.isCancelled() || "RUNTIME_GRAPH_CANCELLED".equals(nodeResult.code())) {
                eventSink.onExecutionCancelled(Map.of(
                        "currentNodeId", node.getId(),
                        "code", "RUNTIME_GRAPH_CANCELLED"));
                return withLiveContext(withSteps(failure("RUNTIME_GRAPH_CANCELLED", "Workflow execution cancelled",
                        node.getId(), nodeType), steps, nodeTraces), context);
            }
            if (!nodeResult.success()) {
                if (nodeResult.isWaitingUser()
                        || WorkflowInteractionCodes.WAITING.equals(nodeResult.code())) {
                    Map<String, Object> waitingPayload = safeNodePayload(node, nodeType, elapsedMs);
                    waitingPayload.put("status", "WAITING");
                    if (nodeResult.interactionId() != null) {
                        waitingPayload.put("interactionId", nodeResult.interactionId());
                    }
                    if (nodeResult.uiRequest() != null) {
                        waitingPayload.put("uiRequest", nodeResult.uiRequest());
                    }
                    if (nodeResult.metadata() != null) {
                        Object validationErrors = nodeResult.metadata().get("validationErrors");
                        if (validationErrors != null) {
                            waitingPayload.put("validationErrors", validationErrors);
                        }
                        putAttemptObservability(waitingPayload, nodeResult);
                    }
                    eventSink.onNodeWaiting(node.getId(), nodeType, nodeName, waitingPayload);
                } else {
                    Map<String, Object> failedPayload = safeNodePayload(node, nodeType, elapsedMs);
                    failedPayload.put("status", "FAILED");
                    failedPayload.put("code", nodeResult.code());
                    failedPayload.put("summary", safeSummary(nodeResult.answer()));
                    putAttemptObservability(failedPayload, nodeResult);
                    eventSink.onNodeFailed(node.getId(), nodeType, nodeName, failedPayload);
                }
                // Persist the live executor context (TOOL/PARAMETER_EXTRACT outputs, vars, pending ids).
                return withLiveContext(withSteps(nodeResult, steps, nodeTraces), context);
            }
            Map<String, Object> completedPayload = safeNodePayload(node, nodeType, elapsedMs);
            completedPayload.put("status", isBusinessTerminalResult(nodeResult)
                    ? "BUSINESS_TERMINAL"
                    : "SUCCESS");
            completedPayload.put("summary", safeSummary(nodeResult.answer()));
            putAttemptObservability(completedPayload, nodeResult);
            if (nodeResult.uiRequest() != null) {
                completedPayload.put("uiRequest", nodeResult.uiRequest());
            }
            String forcedNext = forcedNextNodeId(nodeResult);
            String nextPreview = null;
            NextNodeResolution next = null;
            if (StringUtils.hasText(forcedNext)) {
                next = NextNodeResolution.node(forcedNext);
                nextPreview = forcedNext;
            } else if (!"ANSWER".equals(nodeResult.nodeType())) {
                next = resolveNextNode(graph, node.getId(), nodeResult);
                // INTERACTION confirm/reject/cancel 不得回落到 always 边执行受保护下游
                if ("INTERACTION".equals(nodeResult.nodeType())
                        && isStrictInteractionRoute(resultRoute(nodeResult))
                        && !hasExactRouteEdge(graph, node.getId(), resultRoute(nodeResult))) {
                    next = NextNodeResolution.unmatched();
                }
                nextPreview = next.nodeId();
            }
            if (StringUtils.hasText(nextPreview)) {
                completedPayload.put("nextNodeId", nextPreview);
            }
            eventSink.onNodeCompleted(node.getId(), nodeType, nodeName, completedPayload);
            lastResult = withSteps(nodeResult, steps, nodeTraces);
            rememberNodeOutput(context, node, nodeResult);
            if (nodeResult.uiRequest() != null
                    && nodeResult.metadata() != null
                    && Boolean.TRUE.equals(nodeResult.metadata().get("displayOnly"))) {
                context.put("__lastDisplayUiRequest", nodeResult.uiRequest());
            }
            if ("ANSWER".equals(nodeResult.nodeType()) && !StringUtils.hasText(forcedNext)) {
                return withLiveContext(withDisplayUiRequest(lastResult, context), context);
            }
            if (!StringUtils.hasText(forcedNext) && exitNodeIds.contains(node.getId())) {
                return withLiveContext(withDisplayUiRequest(lastResult, context), context);
            }
            String route = resultRoute(nodeResult);
            if (!StringUtils.hasText(forcedNext) && StringUtils.hasText(route) && next != null && !next.matched()) {
                if ("INTERACTION".equals(nodeResult.nodeType())
                        && ("reject".equalsIgnoreCase(route) || "cancel".equalsIgnoreCase(route))) {
                    // 拒绝/取消且无显式 route 边：安全停止，不继续下游
                    return withLiveContext(lastResult, context);
                }
                return withLiveContext(withSteps(failure(
                        "RUNTIME_GRAPH_ROUTE_UNRESOLVED",
                        nodeResult.nodeType() + " route has no matching outgoing edge: " + route,
                        node.getId(),
                        nodeResult.nodeType()), steps, nodeTraces), context);
            }
            currentNodeId = next == null ? null : next.nodeId();
        }
        if (StringUtils.hasText(currentNodeId)) {
            return withLiveContext(withSteps(failure("RUNTIME_GRAPH_STEP_LIMIT_EXCEEDED",
                    "Runtime GraphSpec linear execution exceeded step limit: " + MAX_LINEAR_STEPS,
                    currentNodeId,
                    null), steps, nodeTraces), context);
        }
        return lastResult == null
                ? withLiveContext(withSteps(failure("RUNTIME_GRAPH_ENTRY_MISSING", "GraphSpec entry is required", null, null), steps, nodeTraces), context)
                : withLiveContext(withDisplayUiRequest(lastResult, context), context);
    }

    private RuntimeGraphSpecExecutionResult withDisplayUiRequest(RuntimeGraphSpecExecutionResult result,
                                                                 Map<String, Object> context) {
        if (result == null || context == null || context.get("__lastDisplayUiRequest") == null) {
            return result;
        }
        if (result.uiRequest() != null) {
            return result;
        }
        Map<String, Object> metadata = result.metadata() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(result.metadata());
        metadata.putIfAbsent("uiRequest", context.get("__lastDisplayUiRequest"));
        metadata.putIfAbsent("displayOnly", true);
        return new RuntimeGraphSpecExecutionResult(
                result.success(),
                result.code(),
                result.answer(),
                result.nodeId(),
                result.nodeType(),
                result.steps(),
                metadata,
                result.resumeCheckpoint());
    }

    /**
     * Attach the live executor context as an internal resume snapshot.
     * Must not be copied into public API / SSE / uiRequest payloads by callers.
     */
    private RuntimeGraphSpecExecutionResult withLiveContext(RuntimeGraphSpecExecutionResult result,
                                                            Map<String, Object> context) {
        if (result == null) {
            return null;
        }
        return result.withResumeCheckpoint(context == null ? Map.of() : context);
    }

    private Map<String, Object> safeNodePayload(GraphSpec.Node node, String nodeType, Long elapsedMs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeId", node.getId());
        payload.put("nodeType", nodeType);
        payload.put("nodeName", firstText(node.getName(), node.getId()));
        if (elapsedMs != null) {
            payload.put("elapsedMs", elapsedMs);
        }
        return payload;
    }

    private String safeSummary(String answer) {
        if (!StringUtils.hasText(answer)) {
            return "";
        }
        String trimmed = answer.trim();
        return trimmed.length() <= 240 ? trimmed : trimmed.substring(0, 240);
    }

    private RuntimeGraphSpecExecutionResult executeNodeWithPolicies(GraphSpec.Node node,
                                                                    Map<String, Object> context,
                                                                    GraphSpec graph,
                                                                    RuntimeGraphSpecExecutionEventSink eventSink,
                                                                    RuntimeGraphSpecExecutionCancellation cancel,
                                                                    Map<String, GraphSpec.Node> nodesById) {
        String nodeType = node.getType();
        GraphSpec.RetryPolicy retry = node.getRetry();
        boolean retryEnabled = retry != null && Boolean.TRUE.equals(retry.getEnabled());
        int maxAttempts = 1;
        if (retryEnabled) {
            int configured = retry.getMaxAttempts() == null ? 1 : retry.getMaxAttempts();
            maxAttempts = Math.max(1, Math.min(MAX_RETRY_ATTEMPTS, configured));
        }
        long backoffMs = retry == null || retry.getBackoffMs() == null
                ? 0L
                : Math.max(0L, Math.min(MAX_RETRY_BACKOFF_MS, retry.getBackoffMs()));
        RuntimeGraphSpecExecutionResult last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (cancel.isCancelled()) {
                return cancelled(node.getId(), nodeType);
            }
            if (attempt > 1) {
                if (!allowsRetry(node, nodeType, last)) {
                    break;
                }
                if (backoffMs > 0) {
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return cancelled(node.getId(), nodeType);
                    }
                }
                if (cancel.isCancelled()) {
                    return cancelled(node.getId(), nodeType);
                }
            }
            last = executeNode(node, context, graph, eventSink, cancel);
            last = withAttemptMetadata(last, attempt, maxAttempts, attempt > 1 ? "retry" : "initial");
            if (last.success()) {
                last = hydrateBusinessMemory(last, context);
            }
            if (last.success()
                    || last.isWaitingUser()
                    || WorkflowInteractionCodes.WAITING.equals(last.code())
                    || "RUNTIME_GRAPH_CANCELLED".equals(last.code())) {
                return last;
            }
            if (attempt < maxAttempts && !allowsRetry(node, nodeType, last)) {
                break;
            }
        }
        return applyErrorPolicy(node, nodeType, last, nodesById, context);
    }

    private RuntimeGraphSpecExecutionResult hydrateBusinessMemory(
            RuntimeGraphSpecExecutionResult result,
            Map<String, Object> context) {
        if (businessMemoryHydrationService == null || result == null || !result.success()) {
            return result;
        }
        Object structured = result.metadata() == null
                ? null : result.metadata().get("structuredOutput");
        if (structured == null && StringUtils.hasText(result.answer())) {
            String answer = result.answer().trim();
            if (answer.startsWith("{") || answer.startsWith("[")) {
                try {
                    structured = objectMapper.readValue(answer, Object.class);
                } catch (Exception ignored) {
                    return result;
                }
            }
        }
        if (structured == null) return result;
        HydrationBatch batch = businessMemoryHydrationService.hydrate(
                structured, resolveTrustedIdentity(context), context);
        if (!batch.detected()) return result;

        Map<String, Object> metadata = result.metadata() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(result.metadata());
        Object safeOutput = batch.output();
        String safeAnswer;
        try {
            safeAnswer = objectMapper.writeValueAsString(safeOutput);
        } catch (Exception serializationFailure) {
            safeOutput = Map.of(
                    "authoritative", false,
                    "hydrationRequired", true,
                    "hydrationStatus", "SAFE_SERIALIZATION_FAILED");
            try {
                safeAnswer = objectMapper.writeValueAsString(safeOutput);
            } catch (Exception impossible) {
                safeAnswer = "{\"authoritative\":false,\"hydrationRequired\":true,"
                        + "\"hydrationStatus\":\"SAFE_SERIALIZATION_FAILED\"}";
            }
        }
        metadata.put("structuredOutput", safeOutput);
        metadata.put("businessMemoryHydration", Map.of(
                "referenceCount", batch.referenceCount(),
                "resolvedCount", batch.resolvedCount(),
                "blockedCount", batch.blockedCount(),
                "versionChangedCount", batch.versionChangedCount()));
        return new RuntimeGraphSpecExecutionResult(
                result.success(), result.code(), safeAnswer, result.nodeId(), result.nodeType(),
                result.steps(), metadata, result.resumeCheckpoint());
    }

    private boolean allowsRetry(GraphSpec.Node node, String nodeType) {
        return allowsRetry(node, nodeType, null);
    }

    private boolean allowsRetry(GraphSpec.Node node, String nodeType, RuntimeGraphSpecExecutionResult failure) {
        GraphSpec.RetryPolicy retry = node.getRetry();
        if (retry == null || !Boolean.TRUE.equals(retry.getEnabled())) {
            return false;
        }
        if (failure != null) {
            if (failure.success()
                    || failure.isWaitingUser()
                    || WorkflowInteractionCodes.WAITING.equals(failure.code())
                    || "RUNTIME_GRAPH_CANCELLED".equals(failure.code())) {
                return false;
            }
            if (!isRetryableFailure(nodeType, failure)) {
                return false;
            }
        }
        Optional<AgentGraphNodeType> type = AgentGraphNodeType.find(nodeType);
        if (type.isEmpty() || !type.get().retryable()) {
            return false;
        }
        if ("HTTP_REQUEST".equals(nodeType)) {
            Map<String, Object> config = httpConfig(node);
            String method = firstText(text(config.get("method")), "GET");
            boolean allowNonIdempotent = Boolean.TRUE.equals(config.get("retryAllowNonIdempotent"));
            if (httpClient != null && !httpClient.isIdempotentMethod(method) && !allowNonIdempotent) {
                return false;
            }
        }
        return true;
    }

    private boolean isRetryableFailure(String nodeType, RuntimeGraphSpecExecutionResult failure) {
        if (failure == null || !StringUtils.hasText(failure.code())) {
            return false;
        }
        String code = failure.code();
        if (code.startsWith("RUNTIME_HTTP_EGRESS_")
                || "RUNTIME_HTTP_CREDENTIAL_DENIED".equals(code)
                || "RUNTIME_HTTP_CREDENTIAL_INVALID".equals(code)
                || "RUNTIME_HTTP_CREDENTIAL_TYPE_UNSUPPORTED".equals(code)
                || "RUNTIME_HTTP_URL_REQUIRED".equals(code)
                || "RUNTIME_HTTP_URL_INVALID".equals(code)
                || "RUNTIME_HTTP_METHOD_UNSUPPORTED".equals(code)
                || "RUNTIME_HTTP_REDIRECT_CREDENTIAL_DENIED".equals(code)
                || "RUNTIME_HTTP_REDIRECT_DOWNGRADE_DENIED".equals(code)
                || "RUNTIME_GRAPH_NODE_UNSUPPORTED".equals(code)
                || code.contains("CONFIG")
                || code.contains("VALIDATION")) {
            return false;
        }
        // Explicit non-retryable configuration / identity / dependency failures.
        if (code.contains("REQUIRED")
                || code.contains("DENIED")
                || code.contains("UNSUPPORTED")
                || code.contains("UNAVAILABLE")
                || code.contains("INVALID")
                || code.contains("PROTECTED")
                || "RUNTIME_KNOWLEDGE_USER_IDENTITY_REQUIRED".equals(code)
                || "RUNTIME_MODEL_REQUIRED".equals(code)
                || "RUNTIME_TOOL_REF_REQUIRED".equals(code)
                || code.startsWith("RUNTIME_KNOWLEDGE_") && code.endsWith("_REQUIRED")
                || code.endsWith("_CLIENT_UNAVAILABLE")
                || code.contains("PERMISSION")
                || code.contains("ACL")) {
            return false;
        }
        Object retryableFlag = failure.metadata() == null ? null : failure.metadata().get("retryableFailure");
        if (Boolean.TRUE.equals(retryableFlag)) {
            return true;
        }
        if ("HTTP_REQUEST".equals(nodeType)) {
            if (code.startsWith("RUNTIME_HTTP_STATUS_")) {
                try {
                    int status = Integer.parseInt(code.substring("RUNTIME_HTTP_STATUS_".length()));
                    return httpClient != null && httpClient.isRetryableStatus(status);
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
            return "RUNTIME_HTTP_TIMEOUT".equals(code) || "RUNTIME_HTTP_CONNECT_FAILED".equals(code);
        }
        // Default fail-closed: only explicitly marked transient failures are retryable.
        return false;
    }

    private RuntimeGraphSpecExecutionResult applyErrorPolicy(GraphSpec.Node node,
                                                             String nodeType,
                                                             RuntimeGraphSpecExecutionResult failed,
                                                             Map<String, GraphSpec.Node> nodesById,
                                                             Map<String, Object> context) {
        GraphSpec.ErrorPolicy policy = node.getErrorPolicy();
        String strategy = policy == null
                ? "TERMINATE"
                : firstText(text(policy.getStrategy()), "TERMINATE").toUpperCase(Locale.ROOT);
        RuntimeGraphSpecExecutionResult base = failed == null
                ? failure("RUNTIME_GRAPH_NODE_FAILED", "Node execution failed", node.getId(), nodeType)
                : failed;
        if ("CONTINUE".equals(strategy)) {
            Object defaultOutput = policy == null ? null : policy.getDefaultOutput();
            String answer = defaultOutput == null ? "" : String.valueOf(defaultOutput);
            Map<String, Object> metadata = new LinkedHashMap<>(base.metadata() == null ? Map.of() : base.metadata());
            metadata.put("errorPolicyDecision", "CONTINUE");
            metadata.put("structuredOutput", defaultOutput == null ? Map.of() : defaultOutput);
            metadata.put("recoveredFromCode", base.code());
            return new RuntimeGraphSpecExecutionResult(
                    true,
                    "RUNTIME_GRAPH_ERROR_CONTINUED",
                    answer,
                    node.getId(),
                    nodeType,
                    base.steps(),
                    metadata,
                    base.resumeCheckpoint());
        }
        if ("FALLBACK".equals(strategy)) {
            String fallbackNodeId = policy == null ? null : text(policy.getFallbackNodeId());
            if (!StringUtils.hasText(fallbackNodeId) || !nodesById.containsKey(fallbackNodeId)) {
                return withPolicyDecision(base, "FALLBACK_INVALID",
                        "RUNTIME_GRAPH_FALLBACK_INVALID",
                        "ErrorPolicy FALLBACK target is missing or invalid");
            }
            if (fallbackNodeId.equals(node.getId())) {
                return withPolicyDecision(base, "FALLBACK_SELF",
                        "RUNTIME_GRAPH_FALLBACK_SELF",
                        "ErrorPolicy FALLBACK must not point to the same node");
            }
            @SuppressWarnings("unchecked")
            List<String> trail = context.get("__fallbackTrail") instanceof List<?> list
                    ? new ArrayList<>((List<String>) list)
                    : new ArrayList<>();
            String hop = node.getId() + "->" + fallbackNodeId;
            if (trail.contains(hop) || trail.size() >= 8) {
                return withPolicyDecision(base, "FALLBACK_CYCLE",
                        "RUNTIME_GRAPH_FALLBACK_CYCLE",
                        "ErrorPolicy FALLBACK cycle detected");
            }
            trail.add(hop);
            context.put("__fallbackTrail", trail);
            Map<String, Object> metadata = new LinkedHashMap<>(base.metadata() == null ? Map.of() : base.metadata());
            metadata.put("errorPolicyDecision", "FALLBACK");
            metadata.put("forceNextNodeId", fallbackNodeId);
            metadata.put("fallbackNodeId", fallbackNodeId);
            metadata.put("recoveredFromCode", base.code());
            metadata.put("structuredOutput", policy.getDefaultOutput() == null
                    ? Map.of("fallback", true, "fromNodeId", node.getId())
                    : policy.getDefaultOutput());
            String answer = policy.getDefaultOutput() == null
                    ? firstText(base.answer(), "fallback:" + fallbackNodeId)
                    : String.valueOf(policy.getDefaultOutput());
            return new RuntimeGraphSpecExecutionResult(
                    true,
                    "RUNTIME_GRAPH_ERROR_FALLBACK",
                    answer,
                    node.getId(),
                    nodeType,
                    base.steps(),
                    metadata,
                    base.resumeCheckpoint());
        }
        return withPolicyDecision(base, "TERMINATE", base.code(), base.answer());
    }

    private RuntimeGraphSpecExecutionResult withPolicyDecision(RuntimeGraphSpecExecutionResult base,
                                                               String decision,
                                                               String code,
                                                               String answer) {
        Map<String, Object> metadata = new LinkedHashMap<>(base.metadata() == null ? Map.of() : base.metadata());
        metadata.put("errorPolicyDecision", decision);
        return new RuntimeGraphSpecExecutionResult(
                base.success(),
                code,
                answer,
                base.nodeId(),
                base.nodeType(),
                base.steps(),
                metadata,
                base.resumeCheckpoint());
    }

    private RuntimeGraphSpecExecutionResult withAttemptMetadata(RuntimeGraphSpecExecutionResult result,
                                                                int attempt,
                                                                int maxAttempts,
                                                                String reason) {
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata() == null ? Map.of() : result.metadata());
        metadata.put("attempt", attempt);
        metadata.put("attemptCount", attempt);
        metadata.put("maxAttempts", maxAttempts);
        metadata.put("attemptReason", reason);
        return result.withMetadata(metadata);
    }

    private void putAttemptObservability(Map<String, Object> payload, RuntimeGraphSpecExecutionResult result) {
        if (result == null || result.metadata() == null || payload == null) {
            return;
        }
        putIfPresent(payload, "attempt", result.metadata().get("attempt"));
        putIfPresent(payload, "attemptCount", result.metadata().get("attemptCount"));
        putIfPresent(payload, "maxAttempts", result.metadata().get("maxAttempts"));
        putIfPresent(payload, "errorPolicyDecision", result.metadata().get("errorPolicyDecision"));
        putIfPresent(payload, "traceSummary", result.metadata().get("traceSummary"));
        putIfPresent(payload, "outcomeClass", result.metadata().get("outcomeClass"));
        putIfPresent(payload, "businessOutcome", result.metadata().get("businessOutcome"));
    }

    private String forcedNextNodeId(RuntimeGraphSpecExecutionResult result) {
        if (result == null || result.metadata() == null) {
            return null;
        }
        return text(result.metadata().get("forceNextNodeId"));
    }

    private RuntimeGraphSpecExecutionResult executeNode(GraphSpec.Node node,
                                                        Map<String, Object> context,
                                                        GraphSpec graph,
                                                        RuntimeGraphSpecExecutionEventSink eventSink,
                                                        RuntimeGraphSpecExecutionCancellation cancel) {
        String nodeType = AgentGraphNodeType.normalize(node.getType());
        if (cancel.isCancelled()) {
            return cancelled(node.getId(), nodeType);
        }
        return switch (nodeType) {
            case "USER_INPUT" -> executeUserInput(node, context);
            case "INTENT_CLASSIFIER" -> executeIntentClassifier(node, context, cancel);
            case "IF_ELSE" -> executeCondition(node, context);
            case "PARAMETER_EXTRACT" -> executeParameterExtract(node, context, cancel);
            case "ANSWER" -> executeAnswer(node, context);
            case "LLM" -> executeLlm(node, context, graph, eventSink, cancel);
            case "TOOL", "CAPABILITY" -> executeTool(node, nodeType, context, cancel);
            case "PAGE_ACTION" -> executePageAction(node, context, cancel);
            case "INTERACTION" -> executeInteraction(node, context);
            case "VARIABLE_ASSIGN" -> executeVariableAssign(node, context);
            case "TEMPLATE" -> executeTemplate(node, context);
            case "VARIABLE_AGGREGATOR" -> executeVariableAggregator(node, context);
            case "KNOWLEDGE_RETRIEVAL" -> executeKnowledgeRetrieval(node, context, cancel);
            case "HTTP_REQUEST" -> executeHttpRequest(node, context, cancel);
            case "LOOP" -> executeLoop(node, context, graph, eventSink, cancel);
            default -> failure("RUNTIME_GRAPH_NODE_UNSUPPORTED",
                    "Runtime GraphSpec node type is not executable yet: " + nodeType,
                    node.getId(),
                    nodeType);
        };
    }

    public static final int LOOP_DEFAULT_MAX_ITERATIONS = 100;
    public static final int LOOP_HARD_MAX_ITERATIONS = 1000;

    private RuntimeGraphSpecExecutionResult executeLoop(GraphSpec.Node node,
                                                        Map<String, Object> context,
                                                        GraphSpec graph,
                                                        RuntimeGraphSpecExecutionEventSink eventSink,
                                                        RuntimeGraphSpecExecutionCancellation cancel) {
        Map<String, GraphSpec.Node> nodesById = new LinkedHashMap<>();
        if (graph != null && graph.getNodes() != null) {
            for (GraphSpec.Node graphNode : graph.getNodes()) {
                if (graphNode != null && StringUtils.hasText(graphNode.getId())) {
                    nodesById.put(graphNode.getId().trim(), graphNode);
                }
            }
        }
        Map<String, Object> config = loopConfigOf(node);
        String mode = firstText(text(config.get("mode")), "FOREACH");
        if (!"FOREACH".equalsIgnoreCase(mode)) {
            return failure("RUNTIME_GRAPH_LOOP_MODE_UNSUPPORTED",
                    "LOOP v1 only supports FOREACH mode",
                    node.getId(),
                    "LOOP");
        }
        String collectionExpr = firstText(text(config.get("collection")), text(config.get("itemExpression")));
        String itemAlias = firstText(text(config.get("itemAlias")), "item");
        String indexAlias = firstText(text(config.get("indexAlias")), "index");
        String outputAlias = firstText(text(config.get("outputAlias")), text(config.get("loopKey")), "loop_results");
        String bodyOutputExpr = firstText(text(config.get("bodyOutput")), "lastOutput");
        String bodyEntry = text(config.get("bodyEntry"));
        String bodyExit = firstText(text(config.get("bodyExit")), bodyEntry);
        Integer maxIterations = parseLoopMaxIterations(config.get("maxIterations"));
        if (maxIterations == null) {
            return failure("RUNTIME_GRAPH_LOOP_MAX_ITERATIONS_INVALID",
                    "LOOP maxIterations must be between 1 and " + LOOP_HARD_MAX_ITERATIONS
                            + " (missing defaults to " + LOOP_DEFAULT_MAX_ITERATIONS + ")",
                    node.getId(),
                    "LOOP");
        }
        Set<String> bodyNodeIds = loopBodyNodeIds(config, bodyEntry, bodyExit);
        if (!StringUtils.hasText(collectionExpr)) {
            return failure("RUNTIME_GRAPH_LOOP_COLLECTION_REQUIRED",
                    "LOOP requires collection expression",
                    node.getId(),
                    "LOOP");
        }
        if (!WorkflowVariableNamespaces.isValidAlias(itemAlias)
                || WorkflowVariableNamespaces.isReservedAlias(itemAlias)
                || !WorkflowVariableNamespaces.isValidAlias(indexAlias)
                || WorkflowVariableNamespaces.isReservedAlias(indexAlias)
                || !WorkflowVariableNamespaces.isValidAlias(outputAlias)
                || WorkflowVariableNamespaces.isReservedAlias(outputAlias)) {
            return failure("RUNTIME_GRAPH_LOOP_ALIAS_INVALID",
                    "LOOP itemAlias/indexAlias/outputAlias must be valid non-reserved aliases",
                    node.getId(),
                    "LOOP");
        }
        if (!StringUtils.hasText(bodyEntry) || !nodesById.containsKey(bodyEntry)
                || !StringUtils.hasText(bodyExit) || !nodesById.containsKey(bodyExit)
                || bodyNodeIds.isEmpty()
                || !bodyNodeIds.contains(bodyEntry)
                || !bodyNodeIds.contains(bodyExit)) {
            return failure("RUNTIME_GRAPH_LOOP_BODY_INVALID",
                    "LOOP requires bodyEntry/bodyExit inside bodyNodeIds",
                    node.getId(),
                    "LOOP");
        }
        for (String bodyId : bodyNodeIds) {
            GraphSpec.Node bodyNode = nodesById.get(bodyId);
            if (bodyNode == null) {
                return failure("RUNTIME_GRAPH_LOOP_BODY_INVALID",
                        "LOOP body node missing: " + bodyId,
                        node.getId(),
                        "LOOP");
            }
            String bodyType = AgentGraphNodeType.normalize(bodyNode.getType());
            if ("LOOP".equals(bodyType)) {
                return failure("RUNTIME_GRAPH_LOOP_NESTED",
                        "LOOP nesting is not supported in v1",
                        node.getId(),
                        "LOOP");
            }
            if ("INTERACTION".equals(bodyType) || "HUMAN_APPROVAL".equals(bodyType)) {
                return failure("RUNTIME_GRAPH_LOOP_BODY_FORBIDDEN",
                        "LOOP body cannot include " + bodyType,
                        node.getId(),
                        "LOOP");
            }
        }
        Object collectionValue = resolveContextValue(collectionExpr, context);
        if (collectionValue == null) {
            return failure("RUNTIME_GRAPH_LOOP_COLLECTION_NULL",
                    "LOOP collection resolved to null",
                    node.getId(),
                    "LOOP");
        }
        List<?> items;
        if (collectionValue instanceof List<?> list) {
            items = list;
        } else if (collectionValue.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(collectionValue);
            List<Object> converted = new ArrayList<>(len);
            for (int i = 0; i < len; i++) {
                converted.add(java.lang.reflect.Array.get(collectionValue, i));
            }
            items = converted;
        } else {
            return failure("RUNTIME_GRAPH_LOOP_COLLECTION_TYPE",
                    "LOOP collection must resolve to an array/list",
                    node.getId(),
                    "LOOP");
        }
        if (items.size() > maxIterations) {
            return failure("RUNTIME_GRAPH_LOOP_MAX_ITERATIONS",
                    "LOOP collection size " + items.size() + " exceeds maxIterations " + maxIterations,
                    node.getId(),
                    "LOOP");
        }
        List<Object> collected = new ArrayList<>();
        List<Map<String, Object>> iterationSummaries = new ArrayList<>();
        List<Map<String, Object>> bodySteps = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (cancel.isCancelled()) {
                return cancelled(node.getId(), "LOOP");
            }
            Map<String, Object> iterContext = copyExecutionContext(context);
            WorkflowOutputAliasWriter.writeAlias(iterContext, itemAlias, items.get(i));
            WorkflowOutputAliasWriter.writeAlias(iterContext, indexAlias, i);
            RuntimeGraphSpecExecutionResult bodyResult = executeLoopBody(
                    graph, nodesById, bodyNodeIds, bodyEntry, bodyExit, iterContext, eventSink, cancel);
            bodySteps.addAll(bodyResult.steps());
            Map<String, Object> iterSummary = new LinkedHashMap<>();
            iterSummary.put("index", i);
            iterSummary.put("status", bodyResult.success() ? "SUCCESS" : bodyResult.code());
            iterationSummaries.add(iterSummary);
            if (cancel.isCancelled() || "RUNTIME_GRAPH_CANCELLED".equals(bodyResult.code())) {
                return cancelled(node.getId(), "LOOP");
            }
            if (!bodyResult.success()) {
                Map<String, Object> metadata = nodeMetadata(node, "LOOP");
                metadata.put("traceSummary", loopTraceSummary(items.size(), maxIterations, i, iterationSummaries));
                metadata.put("failureIterationIndex", i);
                return new RuntimeGraphSpecExecutionResult(
                        false,
                        firstText(bodyResult.code(), "RUNTIME_GRAPH_LOOP_BODY_FAILED"),
                        firstText(bodyResult.answer(), "LOOP body failed at iteration " + i),
                        node.getId(),
                        "LOOP",
                        bodySteps,
                        metadata);
            }
            Object piece = resolveContextValue(bodyOutputExpr, iterContext);
            if (piece == null && bodyResult.metadata() != null) {
                piece = bodyResult.metadata().get("structuredOutput");
            }
            if (piece == null) {
                piece = bodyResult.answer();
            }
            collected.add(piece);
        }
        WorkflowOutputAliasWriter.writeAlias(context, outputAlias, collected);
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("results", collected);
        structured.put("collectionSize", items.size());
        structured.put("completedIterations", collected.size());
        Map<String, Object> metadata = nodeMetadata(node, "LOOP");
        metadata.put("structuredOutput", collected);
        metadata.put("traceSummary", loopTraceSummary(items.size(), maxIterations, collected.size(), iterationSummaries));
        return new RuntimeGraphSpecExecutionResult(
                true,
                "RUNTIME_GRAPH_EXECUTED",
                String.valueOf(collected.size()),
                node.getId(),
                "LOOP",
                bodySteps.isEmpty() ? List.of(step("execute-node", node.getId())) : bodySteps,
                metadata);
    }

    private RuntimeGraphSpecExecutionResult executeLoopBody(GraphSpec graph,
                                                            Map<String, GraphSpec.Node> nodesById,
                                                            Set<String> bodyNodeIds,
                                                            String bodyEntry,
                                                            String bodyExit,
                                                            Map<String, Object> context,
                                                            RuntimeGraphSpecExecutionEventSink eventSink,
                                                            RuntimeGraphSpecExecutionCancellation cancel) {
        List<Map<String, Object>> steps = new ArrayList<>();
        String current = bodyEntry;
        RuntimeGraphSpecExecutionResult last = null;
        for (int guard = 0; guard < MAX_LINEAR_STEPS && StringUtils.hasText(current); guard++) {
            if (cancel.isCancelled()) {
                return cancelled(current, "LOOP");
            }
            if (!bodyNodeIds.contains(current)) {
                return failure("RUNTIME_GRAPH_LOOP_BODY_ESCAPE",
                        "LOOP body attempted to leave bodyNodeIds at " + current,
                        current,
                        null);
            }
            GraphSpec.Node bodyNode = nodesById.get(current);
            if (bodyNode == null) {
                return failure("RUNTIME_GRAPH_NEXT_NODE_INVALID",
                        "LOOP body node missing: " + current, current, null);
            }
            String nodeType = AgentGraphNodeType.normalize(bodyNode.getType());
            RuntimeGraphSpecExecutionResult nodeResult = executeNodeWithPolicies(
                    bodyNode, context, graph, eventSink, cancel, nodesById);
            steps.addAll(nodeResult.steps());
            rememberNodeOutput(context, bodyNode, nodeResult);
            if (cancel.isCancelled() || "RUNTIME_GRAPH_CANCELLED".equals(nodeResult.code())) {
                return withSteps(cancelled(bodyNode.getId(), nodeType), steps, List.of());
            }
            if (!nodeResult.success()) {
                return withSteps(nodeResult, steps, List.of());
            }
            last = nodeResult;
            if (bodyExit.equals(bodyNode.getId())) {
                return withSteps(nodeResult, steps, List.of());
            }
            NextNodeResolution next = resolveNextNode(graph, bodyNode.getId(), nodeResult);
            if (next == null || !StringUtils.hasText(next.nodeId())) {
                return withSteps(failure("RUNTIME_GRAPH_LOOP_BODY_EXIT",
                        "LOOP body ended before bodyExit: " + bodyExit,
                        bodyNode.getId(),
                        nodeType), steps, List.of());
            }
            if (!bodyNodeIds.contains(next.nodeId())) {
                return withSteps(failure("RUNTIME_GRAPH_LOOP_BODY_ESCAPE",
                        "LOOP body edge escapes bodyNodeIds: " + next.nodeId(),
                        bodyNode.getId(),
                        nodeType), steps, List.of());
            }
            current = next.nodeId();
        }
        return last == null
                ? failure("RUNTIME_GRAPH_LOOP_BODY_INVALID", "LOOP body did not execute", bodyEntry, null)
                : withSteps(last, steps, List.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> copyExecutionContext(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source == null) {
            return copy;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> nested = new LinkedHashMap<>();
                map.forEach((k, v) -> nested.put(String.valueOf(k), v));
                copy.put(entry.getKey(), nested);
            } else if (value instanceof List<?> list) {
                copy.put(entry.getKey(), new ArrayList<>(list));
            } else {
                copy.put(entry.getKey(), value);
            }
        }
        return copy;
    }

    private Map<String, Object> loopConfigOf(GraphSpec.Node node) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> nested = mapValue(config.get("loopConfig"));
        if (nested == null || nested.isEmpty()) {
            return config;
        }
        Map<String, Object> merged = new LinkedHashMap<>(nested);
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            if (!"loopConfig".equals(entry.getKey())) {
                merged.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        return merged;
    }

    private static Set<String> loopBodyNodeIds(Map<String, Object> config, String bodyEntry, String bodyExit) {
        Set<String> ids = new LinkedHashSet<>();
        Object raw = config.get("bodyNodeIds");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && StringUtils.hasText(String.valueOf(item))) {
                    ids.add(String.valueOf(item).trim());
                }
            }
        }
        if (StringUtils.hasText(bodyEntry)) {
            ids.add(bodyEntry.trim());
        }
        if (StringUtils.hasText(bodyExit)) {
            ids.add(bodyExit.trim());
        }
        return ids;
    }

    /**
     * Missing / blank → default 100. Present but non-numeric, &lt;1 or &gt;hard max → null (fail-closed).
     */
    public static Integer parseLoopMaxIterations(Object raw) {
        if (raw == null) {
            return LOOP_DEFAULT_MAX_ITERATIONS;
        }
        if (raw instanceof String text && !StringUtils.hasText(text)) {
            return LOOP_DEFAULT_MAX_ITERATIONS;
        }
        int value;
        if (raw instanceof Number number) {
            if (raw instanceof Double || raw instanceof Float) {
                double d = number.doubleValue();
                if (!Double.isFinite(d) || Math.floor(d) != d) {
                    return null;
                }
            }
            value = number.intValue();
        } else {
            try {
                value = Integer.parseInt(String.valueOf(raw).trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        if (value < 1 || value > LOOP_HARD_MAX_ITERATIONS) {
            return null;
        }
        return value;
    }

    private static Map<String, Object> loopTraceSummary(int collectionSize,
                                                        int maxIterations,
                                                        int completedOrFailedIndex,
                                                        List<Map<String, Object>> iterations) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("collectionSize", collectionSize);
        summary.put("configuredMaxIterations", maxIterations);
        summary.put("completedIterations", Math.min(completedOrFailedIndex, collectionSize));
        summary.put("iterationCount", iterations == null ? 0 : iterations.size());
        // Never include item values — only index/status.
        summary.put("iterations", iterations == null ? List.of() : iterations);
        return summary;
    }

    private RuntimeGraphSpecExecutionResult cancelled(String nodeId, String nodeType) {
        return failure("RUNTIME_GRAPH_CANCELLED", "Workflow execution cancelled", nodeId, nodeType);
    }

    private RuntimeGraphSpecExecutionResult executeVariableAssign(GraphSpec.Node node, Map<String, Object> context) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> assignments = mapValue(config.get("assignments"));
        if (assignments == null || assignments.isEmpty()) {
            return failure("RUNTIME_GRAPH_ASSIGNMENTS_REQUIRED",
                    "VARIABLE_ASSIGN requires non-empty assignments",
                    node.getId(),
                    "VARIABLE_ASSIGN");
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        Map<String, String> normalizedTargets = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, Object> entry : assignments.entrySet()) {
                String normalized = WorkflowVariableNamespaces.normalizeBusinessWriteTarget(entry.getKey());
                Object value = renderInputValue(entry.getValue(), context);
                resolved.put(normalized, value);
                normalizedTargets.put(entry.getKey(), normalized);
            }
            for (Map.Entry<String, Object> entry : resolved.entrySet()) {
                WorkflowOutputAliasWriter.writeBusinessPath(context, entry.getKey(), entry.getValue());
            }
        } catch (IllegalArgumentException ex) {
            return failure("RUNTIME_GRAPH_ASSIGNMENT_INVALID",
                    "VARIABLE_ASSIGN failed for node " + node.getId() + ": " + ex.getMessage(),
                    node.getId(),
                    "VARIABLE_ASSIGN");
        }
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("assignments", resolved);
        structured.put("targets", normalizedTargets);
        Map<String, Object> metadata = nodeMetadata(node, "VARIABLE_ASSIGN");
        metadata.put("structuredOutput", structured);
        metadata.put("traceSummary", Map.of("assignmentCount", resolved.size()));
        String answer;
        try {
            answer = objectMapper.writeValueAsString(structured);
        } catch (Exception ex) {
            answer = String.valueOf(structured);
        }
        return new RuntimeGraphSpecExecutionResult(
                true,
                "RUNTIME_GRAPH_EXECUTED",
                answer,
                node.getId(),
                "VARIABLE_ASSIGN",
                List.of(step("execute-node", node.getId())),
                metadata);
    }

    private RuntimeGraphSpecExecutionResult executeTemplate(GraphSpec.Node node, Map<String, Object> context) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        String template = firstText(text(config.get("template")), text(config.get("content")));
        if (!StringUtils.hasText(template)) {
            return failure("RUNTIME_GRAPH_TEMPLATE_REQUIRED",
                    "TEMPLATE requires template",
                    node.getId(),
                    "TEMPLATE");
        }
        // Missing variables render as empty string — same contract as ANSWER/LLM templates.
        String rendered = firstText(renderTemplate(template, context), "");
        Map<String, Object> metadata = nodeMetadata(node, "TEMPLATE");
        metadata.put("structuredOutput", rendered);
        metadata.put("traceSummary", Map.of("length", rendered.length()));
        return new RuntimeGraphSpecExecutionResult(
                true,
                "RUNTIME_GRAPH_EXECUTED",
                rendered,
                node.getId(),
                "TEMPLATE",
                List.of(step("execute-node", node.getId())),
                metadata);
    }

    private RuntimeGraphSpecExecutionResult executeVariableAggregator(GraphSpec.Node node,
                                                                      Map<String, Object> context) {
        Map<String, Object> config = aggregateConfig(node);
        String mode = firstText(text(config.get("aggregateMode")), text(config.get("mode")), "object")
                .toLowerCase(Locale.ROOT);
        Object rawItems = config.get("items");
        if (!(rawItems instanceof List<?> items) || items.isEmpty()) {
            return failure("RUNTIME_GRAPH_AGGREGATE_ITEMS_REQUIRED",
                    "VARIABLE_AGGREGATOR requires items",
                    node.getId(),
                    "VARIABLE_AGGREGATOR");
        }
        List<Map<String, Object>> normalizedItems = new ArrayList<>();
        for (Object raw : items) {
            Map<String, Object> item = mapValue(raw);
            if (item == null) {
                continue;
            }
            String name = firstText(text(item.get("name")), "value");
            String source = firstText(text(item.get("source")), "lastOutput");
            Object value = renderInputValue(source, context);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("source", source);
            row.put("value", value);
            normalizedItems.add(row);
        }
        Object structured;
        String answer;
        switch (mode) {
            case "array" -> {
                List<Object> values = normalizedItems.stream().map(item -> item.get("value")).toList();
                structured = values;
                try {
                    answer = objectMapper.writeValueAsString(values);
                } catch (Exception ex) {
                    answer = String.valueOf(values);
                }
            }
            case "text" -> {
                String template = firstText(text(config.get("template")), "");
                if (!StringUtils.hasText(template)) {
                    StringBuilder builder = new StringBuilder();
                    for (Map<String, Object> item : normalizedItems) {
                        if (!builder.isEmpty()) {
                            builder.append('\n');
                        }
                        builder.append(item.get("value") == null ? "" : item.get("value"));
                    }
                    answer = builder.toString();
                } else {
                    Map<String, Object> local = new LinkedHashMap<>(context);
                    for (Map<String, Object> item : normalizedItems) {
                        local.put(String.valueOf(item.get("name")), item.get("value"));
                    }
                    answer = firstText(renderTemplate(template, local), "");
                }
                structured = answer;
            }
            default -> {
                Map<String, Object> object = new LinkedHashMap<>();
                for (Map<String, Object> item : normalizedItems) {
                    object.put(String.valueOf(item.get("name")), item.get("value"));
                }
                structured = object;
                try {
                    answer = objectMapper.writeValueAsString(object);
                } catch (Exception ex) {
                    answer = String.valueOf(object);
                }
            }
        }
        Map<String, Object> metadata = nodeMetadata(node, "VARIABLE_AGGREGATOR");
        metadata.put("structuredOutput", structured);
        metadata.put("aggregateMode", mode);
        metadata.put("traceSummary", Map.of("mode", mode, "itemCount", normalizedItems.size()));
        return new RuntimeGraphSpecExecutionResult(
                true,
                "RUNTIME_GRAPH_EXECUTED",
                answer,
                node.getId(),
                "VARIABLE_AGGREGATOR",
                List.of(step("execute-node", node.getId())),
                metadata);
    }

    private Map<String, Object> aggregateConfig(GraphSpec.Node node) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> nested = mapValue(config.get("aggregateConfig"));
        if (nested == null || nested.isEmpty()) {
            return config;
        }
        Map<String, Object> merged = new LinkedHashMap<>(nested);
        config.forEach((key, value) -> {
            if (!"aggregateConfig".equals(key)) {
                merged.put(key, value);
            }
        });
        return merged;
    }

    private RuntimeGraphSpecExecutionResult executeKnowledgeRetrieval(GraphSpec.Node node,
                                                                      Map<String, Object> context,
                                                                      RuntimeGraphSpecExecutionCancellation cancel) {
        if (knowledgeClient == null) {
            return failure("RUNTIME_KNOWLEDGE_CLIENT_UNAVAILABLE",
                    "Knowledge retrieval client is unavailable",
                    node.getId(),
                    "KNOWLEDGE_RETRIEVAL");
        }
        Map<String, Object> config = knowledgeConfig(node);
        List<String> codes = stringList(config.get("knowledgeBaseCodes"));
        if (codes.isEmpty()) {
            return failure("RUNTIME_KNOWLEDGE_BASE_REQUIRED",
                    "KNOWLEDGE_RETRIEVAL requires knowledgeBaseCodes",
                    node.getId(),
                    "KNOWLEDGE_RETRIEVAL");
        }
        String queryExpression = firstText(text(config.get("query")), "input");
        String query = classifierInput(queryExpression, context);
        if (!StringUtils.hasText(query)) {
            return failure("RUNTIME_KNOWLEDGE_QUERY_REQUIRED",
                    "KNOWLEDGE_RETRIEVAL query resolved to empty",
                    node.getId(),
                    "KNOWLEDGE_RETRIEVAL");
        }
        if (cancel.isCancelled()) {
            return cancelled(node.getId(), "KNOWLEDGE_RETRIEVAL");
        }
        int topK = Math.max(1, Math.min(20, intValue(config.get("topK"), 5)));
        Float threshold = config.get("similarityThreshold") == null
                ? null
                : (float) doubleValue(config.get("similarityThreshold"), 0.5D);
        WorkflowExecutionIdentity identity = resolveTrustedIdentity(context);
        if (!identity.canResolveUserAcl()) {
            return failure("RUNTIME_KNOWLEDGE_USER_IDENTITY_REQUIRED",
                    "KNOWLEDGE_RETRIEVAL requires a trusted user identity for ACL",
                    node.getId(),
                    "KNOWLEDGE_RETRIEVAL");
        }
        try {
            KnowledgeRetrievalResult result = knowledgeClient.retrieve(KnowledgeRetrievalRequest.builder()
                    .query(query)
                    .knowledgeBaseCodes(codes)
                    .userId(identity.userId())
                    .topK(topK)
                    .similarityThreshold(threshold)
                    .searchMode(text(config.get("searchMode")))
                    .rerankEnabled(config.get("rerankEnabled") instanceof Boolean bool ? bool : null)
                    .build());
            if (cancel.isCancelled()) {
                return cancelled(node.getId(), "KNOWLEDGE_RETRIEVAL");
            }
            if (result == null || (result.getCode() != 0 && result.getCode() != 200)) {
                return failure("RUNTIME_KNOWLEDGE_FAILED",
                        result == null ? "Knowledge service returned empty response"
                                : firstText(result.getMessage(), "Knowledge retrieval failed"),
                        node.getId(),
                        "KNOWLEDGE_RETRIEVAL");
            }
            KnowledgeRetrievalData data = result.getData();
            List<KnowledgeHit> hits = data == null || data.getHits() == null ? List.of() : data.getHits();
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("query", query);
            structured.put("hits", hits);
            structured.put("hitCount", hits.size());
            Map<String, Object> metadata = nodeMetadata(node, "KNOWLEDGE_RETRIEVAL");
            metadata.put("structuredOutput", structured);
            Map<String, Object> knowledgeTrace = new LinkedHashMap<>();
            knowledgeTrace.put("queryLength", query.length());
            knowledgeTrace.put("hitCount", hits.size());
            knowledgeTrace.put("topK", topK);
            knowledgeTrace.put("searchMode", firstText(text(config.get("searchMode")), "hybrid"));
            knowledgeTrace.put("rerankApplied", Boolean.TRUE.equals(config.get("rerankEnabled")));
            metadata.put("traceSummary", knowledgeTrace);
            String answer;
            try {
                answer = objectMapper.writeValueAsString(structured);
            } catch (Exception ex) {
                answer = "hitCount=" + hits.size();
            }
            return new RuntimeGraphSpecExecutionResult(
                    true,
                    "RUNTIME_GRAPH_EXECUTED",
                    answer,
                    node.getId(),
                    "KNOWLEDGE_RETRIEVAL",
                    List.of(step("execute-node", node.getId())),
                    metadata);
        } catch (Exception ex) {
            if (cancel.isCancelled()) {
                return cancelled(node.getId(), "KNOWLEDGE_RETRIEVAL");
            }
            return failure("RUNTIME_KNOWLEDGE_FAILED",
                    "KNOWLEDGE_RETRIEVAL failed: " + ex.getMessage(),
                    node.getId(),
                    "KNOWLEDGE_RETRIEVAL");
        }
    }

    private Map<String, Object> knowledgeConfig(GraphSpec.Node node) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> nested = mapValue(config.get("knowledgeConfig"));
        if (nested == null || nested.isEmpty()) {
            return config;
        }
        Map<String, Object> merged = new LinkedHashMap<>(nested);
        config.forEach((key, value) -> {
            if (!"knowledgeConfig".equals(key)) {
                merged.put(key, value);
            }
        });
        return merged;
    }

    private RuntimeGraphSpecExecutionResult executeHttpRequest(GraphSpec.Node node,
                                                               Map<String, Object> context,
                                                               RuntimeGraphSpecExecutionCancellation cancel) {
        if (httpClient == null) {
            return failure("RUNTIME_HTTP_CLIENT_UNAVAILABLE",
                    "HTTP client is unavailable",
                    node.getId(),
                    "HTTP_REQUEST");
        }
        Map<String, Object> config = httpConfig(node);
        String method = firstText(text(config.get("method")), "GET");
        String urlTemplate = text(config.get("url"));
        if (!StringUtils.hasText(urlTemplate)) {
            return failure("RUNTIME_HTTP_URL_REQUIRED",
                    "HTTP_REQUEST requires url",
                    node.getId(),
                    "HTTP_REQUEST");
        }
        String url = firstText(renderTemplate(urlTemplate, context), urlTemplate);
        Map<String, String> queryParams = renderStringMap(mapValue(config.get("queryParams")), context);
        Map<String, String> headers = renderStringMap(mapValue(config.get("headers")), context);
        String bodyType = firstText(text(config.get("bodyType")), "none");
        String body = text(config.get("body"));
        if (StringUtils.hasText(body) && body.contains("{{")) {
            body = renderTemplate(body, context);
        }
        if (cancel.isCancelled()) {
            return cancelled(node.getId(), "HTTP_REQUEST");
        }
        WorkflowExecutionIdentity identity = resolveTrustedIdentity(context);
        HttpExecutionResult result = httpClient.execute(new HttpExecutionRequest(
                method,
                url,
                queryParams,
                headers,
                bodyType,
                body,
                intValue(config.get("timeoutMs"), WorkflowHttpClient.DEFAULT_TIMEOUT_MS),
                text(config.get("credentialRef")),
                identity));
        if (cancel.isCancelled()) {
            return cancelled(node.getId(), "HTTP_REQUEST");
        }
        Map<String, Object> metadata = nodeMetadata(node, "HTTP_REQUEST");
        metadata.put("traceSummary", result.traceSummary());
        metadata.put("retryableFailure", result.retryableFailure());
        if (!result.success()) {
            metadata.put("structuredOutput", result.structuredOutput());
            return new RuntimeGraphSpecExecutionResult(
                    false,
                    result.code(),
                    result.body(),
                    node.getId(),
                    "HTTP_REQUEST",
                    List.of(step("execute-node", node.getId())),
                    metadata);
        }
        Map<String, Object> structured = result.structuredOutput();
        metadata.put("structuredOutput", structured);
        Optional<BusinessResponseEnvelope.Failure> businessFailure =
                BusinessResponseEnvelope.failure(result.parsedBody());
        if (businessFailure.isPresent()) {
            BusinessResponseEnvelope.Failure failure = businessFailure.get();
            metadata.put("retryableFailure", false);
            if (failure.businessCode() != null) {
                metadata.put("businessCode", failure.businessCode());
            }
            return new RuntimeGraphSpecExecutionResult(
                    false,
                    "RUNTIME_HTTP_BUSINESS_RESPONSE_FAILED",
                    failure.message(),
                    node.getId(),
                    "HTTP_REQUEST",
                    List.of(step("execute-node", node.getId())),
                    metadata);
        }
        String answer;
        try {
            answer = objectMapper.writeValueAsString(structured);
        } catch (Exception ex) {
            answer = "statusCode=" + result.statusCode();
        }
        return new RuntimeGraphSpecExecutionResult(
                true,
                "RUNTIME_GRAPH_EXECUTED",
                answer,
                node.getId(),
                "HTTP_REQUEST",
                List.of(step("execute-node", node.getId())),
                metadata);
    }

    private Map<String, Object> httpConfig(GraphSpec.Node node) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> nested = mapValue(config.get("httpConfig"));
        if (nested == null || nested.isEmpty()) {
            return config;
        }
        Map<String, Object> merged = new LinkedHashMap<>(nested);
        config.forEach((key, value) -> {
            if (!"httpConfig".equals(key)) {
                merged.put(key, value);
            }
        });
        return merged;
    }

    private Map<String, String> renderStringMap(Map<String, Object> source, Map<String, Object> context) {
        Map<String, String> rendered = new LinkedHashMap<>();
        if (source == null) {
            return rendered;
        }
        source.forEach((key, value) -> {
            if (!StringUtils.hasText(key)) {
                return;
            }
            Object resolved = renderInputValue(value, context);
            rendered.put(key, resolved == null ? "" : String.valueOf(resolved));
        });
        return rendered;
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> items) {
            return items.stream().map(this::text).filter(StringUtils::hasText).toList();
        }
        if (value instanceof String raw && StringUtils.hasText(raw)) {
            return List.of(raw.trim());
        }
        return List.of();
    }

    private RuntimeGraphSpecExecutionResult executeUserInput(GraphSpec.Node node, Map<String, Object> context) {
        String input = userInputText(context);
        writeUserInputParams(node, context, input);
        Map<String, Object> metadata = nodeMetadata(node, "USER_INPUT");
        return new RuntimeGraphSpecExecutionResult(
                true,
                "RUNTIME_GRAPH_EXECUTED",
                input,
                node.getId(),
                "USER_INPUT",
                List.of(step("execute-node", node.getId())),
                metadata);
    }

    /**
     * {@code params} is a reserved Runtime namespace. USER_INPUT is the single
     * designated writer, so this deliberately bypasses the normal business
     * outputAlias writer while keeping the same flattened lookup behaviour.
     */
    private void writeUserInputParams(GraphSpec.Node node,
                                      Map<String, Object> context,
                                      String rawInput) {
        List<RuntimeWorkflowInputContract.InputField> fields = RuntimeWorkflowInputContract.inputFields(node);
        if (fields.isEmpty()) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> existing = mapValue(context.get(WorkflowVariableNamespaces.PARAMS_ROOT));
        if (existing != null) {
            params.putAll(existing);
        }
        for (RuntimeWorkflowInputContract.InputField field : fields) {
            if (!StringUtils.hasText(field.name())) {
                continue;
            }
            Object value = resolveUserInputFieldValue(field, context, rawInput);
            if (value == null && field.defaultValue() != null) {
                value = field.defaultValue();
            }
            params.put(field.name(), value == null ? "" : value);
        }
        context.put(WorkflowVariableNamespaces.PARAMS_ROOT, params);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            context.put(WorkflowVariableNamespaces.PARAMS_ROOT + "." + entry.getKey(), entry.getValue());
        }
    }

    private Object resolveUserInputFieldValue(RuntimeWorkflowInputContract.InputField field,
                                              Map<String, Object> context,
                                              String rawInput) {
        String source = field.source() == null ? "" : field.source().trim();
        if (!StringUtils.hasText(source)
                || "input".equals(source)
                || "input.message".equals(source)
                || "message".equals(source)
                || "userInput".equals(source)
                || "query".equals(source)) {
            return rawInput;
        }
        Object value = resolveContextValue(source, context);
        if (value != null) {
            return value;
        }
        // A scalar input cannot expose named fields. For the canonical entry
        // field, treat input.question as the natural-language message.
        if (("input." + field.name()).equals(source)) {
            return rawInput;
        }
        return null;
    }

    private RuntimeGraphSpecExecutionResult executeIntentClassifier(GraphSpec.Node node,
                                                                     Map<String, Object> context,
                                                                     RuntimeGraphSpecExecutionCancellation cancel) {
        Map<String, Object> config = classifierConfig(node);
        String strategy = normalizeClassifierStrategy(text(config.get("strategy")));
        List<ClassifierClass> classes = classifierClasses(config.get("classes"));
        if (classes.isEmpty()) {
            return failure("RUNTIME_GRAPH_CLASSIFIER_CLASSES_REQUIRED",
                    "INTENT_CLASSIFIER requires at least one class",
                    node.getId(),
                    "INTENT_CLASSIFIER");
        }

        String inputExpression = firstText(text(config.get("inputExpression")), "input");
        String input = classifierInput(inputExpression, context);
        String defaultRoute = firstText(text(config.get("defaultRoute")), "else");
        ClassifierDecision decision = null;
        if (!"LLM".equals(strategy)) {
            decision = keywordDecision(input, classes);
        }
        if (decision == null && !"KEYWORD".equals(strategy)) {
            String modelInstanceId = resolveModelInstanceId(config, context);
            if (!StringUtils.hasText(modelInstanceId)) {
                return failure("RUNTIME_GRAPH_MODEL_REQUIRED",
                        "INTENT_CLASSIFIER " + strategy + " strategy requires modelInstanceId on node config or request",
                        node.getId(),
                        "INTENT_CLASSIFIER");
            }
            if (cancel.isCancelled()) {
                return cancelled(node.getId(), "INTENT_CLASSIFIER");
            }
            try {
                // Feign sync chat：节点边界协作式取消，无法硬中断进行中的 HTTP
                decision = modelDecision(node, config, context, input, classes, defaultRoute, modelInstanceId);
                if (cancel.isCancelled()) {
                    return cancelled(node.getId(), "INTENT_CLASSIFIER");
                }
            } catch (Exception ex) {
                if (cancel.isCancelled()) {
                    return cancelled(node.getId(), "INTENT_CLASSIFIER");
                }
                return failure("RUNTIME_GRAPH_CLASSIFIER_FAILED",
                        "INTENT_CLASSIFIER model execution failed: " + ex.getMessage(),
                        node.getId(),
                        "INTENT_CLASSIFIER");
            }
        }
        if (decision == null) {
            decision = new ClassifierDecision(defaultRoute, 0D, "default", null);
        }

        context.put("route", decision.route());
        context.put("lastRoute", decision.route());
        Map<String, Object> metadata = nodeMetadata(node, "INTENT_CLASSIFIER");
        metadata.put("route", decision.route());
        metadata.put("lastRoute", decision.route());
        metadata.put("strategy", strategy);
        metadata.put("matchedBy", decision.matchedBy());
        metadata.put("confidence", decision.confidence());
        metadata.put("inputExpression", inputExpression);
        if (StringUtils.hasText(decision.modelOutput())) {
            metadata.put("modelOutput", decision.modelOutput());
        }
        Map<String, Object> classifierStep = step("execute-node", node.getId());
        classifierStep.put("route", decision.route());
        classifierStep.put("strategy", strategy);
        return new RuntimeGraphSpecExecutionResult(
                true,
                "RUNTIME_GRAPH_EXECUTED",
                decision.route(),
                node.getId(),
                "INTENT_CLASSIFIER",
                List.of(classifierStep),
                metadata);
    }

    private RuntimeGraphSpecExecutionResult executeCondition(GraphSpec.Node node,
                                                              Map<String, Object> context) {
        Map<String, Object> config = conditionConfig(node);
        Object rawGroups = firstPresent(config.get("conditionGroups"), config.get("groups"));
        String route = null;
        if (rawGroups instanceof List<?> groups) {
            for (Object rawGroup : groups) {
                if (!(rawGroup instanceof Map<?, ?> group)) {
                    continue;
                }
                String groupId = text(group.get("id"));
                if (!StringUtils.hasText(groupId)) {
                    continue;
                }
                String logic = firstText(text(group.get("logic")), "AND");
                Object rawConditions = group.get("conditions");
                if (!(rawConditions instanceof List<?> conditions) || conditions.isEmpty()) {
                    continue;
                }
                boolean matched = "OR".equalsIgnoreCase(logic)
                        ? conditions.stream().anyMatch(condition -> evaluateCondition(condition, context))
                        : conditions.stream().allMatch(condition -> evaluateCondition(condition, context));
                if (matched) {
                    route = groupId;
                    break;
                }
            }
        }
        route = firstText(route, text(config.get("defaultRoute")), "else");
        context.put("route", route);
        context.put("lastRoute", route);
        Map<String, Object> metadata = nodeMetadata(node, "IF_ELSE");
        metadata.put("route", route);
        metadata.put("lastRoute", route);
        Map<String, Object> conditionStep = step("execute-node", node.getId());
        conditionStep.put("route", route);
        return new RuntimeGraphSpecExecutionResult(
                true,
                "RUNTIME_GRAPH_EXECUTED",
                route,
                node.getId(),
                "IF_ELSE",
                List.of(conditionStep),
                metadata);
    }

    private RuntimeGraphSpecExecutionResult executeParameterExtract(GraphSpec.Node node,
                                                                     Map<String, Object> context,
                                                                     RuntimeGraphSpecExecutionCancellation cancel) {
        Map<String, Object> config = parameterConfig(node);
        String mode = "LLM".equalsIgnoreCase(firstText(
                text(config.get("extractMode")),
                text(config.get("mode")),
                "expression")) ? "LLM" : "EXPRESSION";
        List<Map<String, Object>> fields = parameterFields(config.get("fields"));
        if (fields.isEmpty()) {
            return failure("RUNTIME_GRAPH_PARAMETER_FIELDS_REQUIRED",
                    "PARAMETER_EXTRACT requires at least one target field",
                    node.getId(),
                    "PARAMETER_EXTRACT");
        }

        Map<String, Object> extracted;
        String modelOutput = null;
        if ("LLM".equals(mode)) {
            String modelInstanceId = resolveModelInstanceId(config, context);
            if (!StringUtils.hasText(modelInstanceId)) {
                return failure("RUNTIME_GRAPH_MODEL_REQUIRED",
                        "PARAMETER_EXTRACT LLM mode requires modelInstanceId on node config or Workflow/runtime context",
                        node.getId(),
                        "PARAMETER_EXTRACT");
            }
            if (cancel.isCancelled()) {
                return cancelled(node.getId(), "PARAMETER_EXTRACT");
            }
            try {
                String inputExpression = firstText(text(config.get("inputExpression")), "input");
                String input = classifierInput(inputExpression, context);
                String fieldCatalog = objectMapper.writeValueAsString(fields);
                String systemPrompt = firstText(
                        text(config.get("systemPrompt")),
                        "Extract parameters from the user input according to this field catalog: " + fieldCatalog
                                + ". Return one JSON object only. Do not invent values that are absent.");
                String userPrompt = firstText(
                        renderTemplate(text(config.get("userPrompt")), context),
                        input);
                // Feign sync chat：节点边界协作式取消，无法硬中断进行中的 HTTP
                ModelChatResult result = modelServiceClient.chat(ModelChatRequest.builder()
                        .modelInstanceId(modelInstanceId)
                        .messages(List.of(
                                ChatMessage.builder().role("system").content(systemPrompt).build(),
                                ChatMessage.builder().role("user").content(userPrompt).build()))
                        .options(mapValue(firstPresent(config.get("modelParams"), config.get("options"))))
                        .build());
                if (cancel.isCancelled()) {
                    return cancelled(node.getId(), "PARAMETER_EXTRACT");
                }
                ModelChatData data = result == null ? null : result.getData();
                modelOutput = data == null ? null : text(data.getContent());
                if (!StringUtils.hasText(modelOutput)) {
                    throw new IllegalStateException("model service returned empty parameter content for node " + node.getId());
                }
                extracted = normalizeExtractedFields(parseJsonObject(modelOutput), fields, context, false);
            } catch (IllegalArgumentException ex) {
                if (cancel.isCancelled()) {
                    return cancelled(node.getId(), "PARAMETER_EXTRACT");
                }
                return failure("RUNTIME_GRAPH_PARAMETER_REQUIRED",
                        ex.getMessage(), node.getId(), "PARAMETER_EXTRACT");
            } catch (Exception ex) {
                if (cancel.isCancelled()) {
                    return cancelled(node.getId(), "PARAMETER_EXTRACT");
                }
                return failure("RUNTIME_GRAPH_PARAMETER_EXTRACT_FAILED",
                        "PARAMETER_EXTRACT model execution failed: " + ex.getMessage(),
                        node.getId(),
                        "PARAMETER_EXTRACT");
            }
        } else {
            try {
                extracted = normalizeExtractedFields(Map.of(), fields, context, true);
            } catch (IllegalArgumentException ex) {
                return failure("RUNTIME_GRAPH_PARAMETER_REQUIRED",
                        ex.getMessage(), node.getId(), "PARAMETER_EXTRACT");
            }
        }

        // Publish extracted fields into live context so later ANSWER/INTERACTION templates
        // can read {{ field }} even after lastOutput is overwritten by a subsequent node.
        extracted.forEach(context::put);
        String answer;
        try {
            answer = objectMapper.writeValueAsString(extracted);
        } catch (Exception ex) {
            return failure("RUNTIME_GRAPH_PARAMETER_EXTRACT_FAILED",
                    "PARAMETER_EXTRACT output serialization failed: " + ex.getMessage(),
                    node.getId(),
                    "PARAMETER_EXTRACT");
        }
        Map<String, Object> metadata = nodeMetadata(node, "PARAMETER_EXTRACT");
        metadata.put("mode", mode);
        metadata.put("structuredOutput", extracted);
        if (StringUtils.hasText(modelOutput)) {
            metadata.put("modelOutput", modelOutput);
        }
        return new RuntimeGraphSpecExecutionResult(
                true,
                "RUNTIME_GRAPH_EXECUTED",
                answer,
                node.getId(),
                "PARAMETER_EXTRACT",
                List.of(step("execute-node", node.getId())),
                metadata);
    }

    private Map<String, Object> parameterConfig(GraphSpec.Node node) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> nested = mapValue(config.get("parameterConfig"));
        if (nested == null || nested.isEmpty()) {
            return config;
        }
        Map<String, Object> merged = new LinkedHashMap<>(nested);
        config.forEach((key, value) -> {
            if (!"parameterConfig".equals(key)) {
                merged.put(key, value);
            }
        });
        return merged;
    }

    private List<Map<String, Object>> parameterFields(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        List<Map<String, Object>> fields = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> raw) || !StringUtils.hasText(text(raw.get("name")))) {
                continue;
            }
            Map<String, Object> field = new LinkedHashMap<>();
            raw.forEach((key, fieldValue) -> field.put(String.valueOf(key), fieldValue));
            fields.add(field);
        }
        return fields;
    }

    private Map<String, Object> normalizeExtractedFields(Map<String, Object> modelValues,
                                                          List<Map<String, Object>> fields,
                                                          Map<String, Object> context,
                                                          boolean expressionMode) {
        Map<String, Object> extracted = new LinkedHashMap<>();
        for (Map<String, Object> field : fields) {
            String name = text(field.get("name"));
            Object value;
            if (expressionMode) {
                String source = firstText(text(field.get("source")), name);
                value = resolveContextValue(source, context);
            } else {
                value = modelValues.get(name);
            }
            if (value == null && field.containsKey("defaultValue")) {
                value = field.get("defaultValue");
            }
            value = convertParameterValue(value, text(field.get("type")));
            boolean required = Boolean.TRUE.equals(field.get("required"))
                    || "true".equalsIgnoreCase(text(field.get("required")));
            if (required && isEmptyValue(value)) {
                throw new IllegalArgumentException("PARAMETER_EXTRACT required field is missing: " + name);
            }
            if (value != null) {
                extracted.put(name, value);
            }
        }
        return extracted;
    }

    private Object convertParameterValue(Object value, String type) {
        if (value == null || !StringUtils.hasText(type)) return value;
        try {
            return switch (type.toLowerCase(Locale.ROOT)) {
                case "string" -> String.valueOf(value);
                case "integer" -> value instanceof Number number
                        ? number.intValue()
                        : Integer.parseInt(String.valueOf(value).trim());
                case "number" -> value instanceof Number number
                        ? number.doubleValue()
                        : Double.parseDouble(String.valueOf(value).trim());
                case "boolean" -> value instanceof Boolean bool
                        ? bool
                        : Boolean.parseBoolean(String.valueOf(value).trim());
                default -> value;
            };
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("PARAMETER_EXTRACT field type conversion failed for "
                    + type + ": " + value);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(String content) {
        String candidate = content == null ? "" : content.trim();
        int objectStart = candidate.indexOf('{');
        int objectEnd = candidate.lastIndexOf('}');
        if (objectStart < 0 || objectEnd <= objectStart) {
            throw new IllegalArgumentException("model output is not a JSON object");
        }
        try {
            return objectMapper.readValue(candidate.substring(objectStart, objectEnd + 1), Map.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("model output JSON is invalid: " + ex.getMessage());
        }
    }

    private Map<String, Object> conditionConfig(GraphSpec.Node node) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> nested = mapValue(config.get("conditionConfig"));
        if (nested == null || nested.isEmpty()) {
            return config;
        }
        Map<String, Object> merged = new LinkedHashMap<>(nested);
        config.forEach((key, value) -> {
            if (!"conditionConfig".equals(key)) {
                merged.put(key, value);
            }
        });
        return merged;
    }

    private boolean evaluateCondition(Object value, Map<String, Object> context) {
        if (!(value instanceof Map<?, ?> condition)) {
            return false;
        }
        Object left = resolveConditionOperand(condition.get("left"), context, true);
        Object right = resolveConditionOperand(condition.get("right"), context, false);
        String operator = firstText(text(condition.get("operator")), "equals").toLowerCase(Locale.ROOT);
        return switch (operator) {
            case "exists", "not_empty" -> !isEmptyValue(left);
            case "empty" -> isEmptyValue(left);
            case "equals", "eq" -> valuesEqual(left, right);
            case "not_equals", "neq" -> !valuesEqual(left, right);
            case "contains" -> containsValue(left, right);
            case "not_contains" -> !containsValue(left, right);
            case "gt" -> compareValues(left, right) > 0;
            case "gte" -> compareValues(left, right) >= 0;
            case "lt" -> compareValues(left, right) < 0;
            case "lte" -> compareValues(left, right) <= 0;
            default -> false;
        };
    }

    private Object resolveConditionOperand(Object value,
                                           Map<String, Object> context,
                                           boolean expressionByDefault) {
        if (!(value instanceof String raw)) {
            return value;
        }
        String candidate = raw.trim();
        if (candidate.contains("{{")) {
            return renderTemplate(candidate, context);
        }
        boolean looksLikeExpression = expressionByDefault
                || candidate.startsWith("$.")
                || candidate.startsWith("params.")
                || candidate.startsWith("nodeOutput.")
                || candidate.startsWith("state.")
                || context.containsKey(candidate);
        if (!looksLikeExpression) {
            return raw;
        }
        Object resolved = resolveContextValue(candidate, context);
        return resolved == null && !expressionByDefault ? raw : resolved;
    }

    private boolean isEmptyValue(Object value) {
        if (value == null) return true;
        if (value instanceof CharSequence text) return !StringUtils.hasText(text);
        if (value instanceof Collection<?> collection) return collection.isEmpty();
        if (value instanceof Map<?, ?> map) return map.isEmpty();
        if (value.getClass().isArray()) return java.lang.reflect.Array.getLength(value) == 0;
        return false;
    }

    private boolean valuesEqual(Object left, Object right) {
        if (left == null || right == null) return left == right;
        Double leftNumber = numberValue(left);
        Double rightNumber = numberValue(right);
        if (leftNumber != null && rightNumber != null) {
            return Double.compare(leftNumber, rightNumber) == 0;
        }
        return String.valueOf(left).equals(String.valueOf(right));
    }

    private boolean containsValue(Object left, Object right) {
        if (left == null || right == null) return false;
        if (left instanceof Collection<?> collection) {
            return collection.stream().anyMatch(item -> valuesEqual(item, right));
        }
        if (left instanceof Map<?, ?> map) {
            return map.containsKey(right) || map.containsValue(right);
        }
        return String.valueOf(left).contains(String.valueOf(right));
    }

    private int compareValues(Object left, Object right) {
        Double leftNumber = numberValue(left);
        Double rightNumber = numberValue(right);
        if (leftNumber != null && rightNumber != null) {
            return Double.compare(leftNumber, rightNumber);
        }
        if (left == null || right == null) {
            return left == right ? 0 : left == null ? -1 : 1;
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private Double numberValue(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value != null) {
            try { return Double.parseDouble(String.valueOf(value).trim()); }
            catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private Map<String, Object> classifierConfig(GraphSpec.Node node) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> nested = mapValue(config.get("classifierConfig"));
        if (nested == null || nested.isEmpty()) {
            return config;
        }
        Map<String, Object> merged = new LinkedHashMap<>(nested);
        config.forEach((key, value) -> {
            if (!"classifierConfig".equals(key)) {
                merged.put(key, value);
            }
        });
        return merged;
    }

    private String classifierInput(String expression, Map<String, Object> context) {
        if (!StringUtils.hasText(expression)) {
            return userInputText(context);
        }
        if (expression.contains("{{")) {
            return firstText(renderTemplate(expression, context), "");
        }
        if ("input".equals(expression.trim()) || "userInput".equals(expression.trim())
                || "query".equals(expression.trim())) {
            return userInputText(context);
        }
        Object value = resolveContextValue(expression, context);
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * The page-embed transport may attach an empty structured {@code input} object while
     * carrying the actual natural-language request in {@code message}.  Preserve structured
     * input for explicit paths such as {@code input.orderNo}, but do not feed the placeholder
     * object ({@code {}}) to USER_INPUT, classifiers, or parameter extractors that ask for the
     * generic {@code input} text.
     */
    private String userInputText(Map<String, Object> context) {
        String userInput = context == null ? null : text(context.get("userInput"));
        if (StringUtils.hasText(userInput)) {
            return userInput;
        }
        Object rawInput = context == null ? null : context.get("input");
        // A scalar input is an explicit Workflow value and must win over the
        // transport message. Structured input remains addressable through
        // input.<field>; its generic text falls back to the user message.
        if (!(rawInput instanceof Map<?, ?>) && !(rawInput instanceof Collection<?>)) {
            String input = text(rawInput);
            if (StringUtils.hasText(input)) {
                return input;
            }
        }
        String message = context == null ? null : text(context.get("message"));
        if (StringUtils.hasText(message)) {
            return message;
        }
        if (isEmptyStructuredInput(rawInput)) {
            return "";
        }
        return firstText(text(rawInput), "");
    }

    private boolean isEmptyStructuredInput(Object value) {
        return (value instanceof Map<?, ?> map && map.isEmpty())
                || (value instanceof Collection<?> collection && collection.isEmpty());
    }

    private Object resolveContextValue(String expression, Map<String, Object> context) {
        String path = expression == null ? "" : expression.trim();
        if (path.startsWith("$.")) {
            path = path.substring(2);
        }
        Object value = context.get(path);
        if (value == null && ("query".equals(path) || "userInput".equals(path))) {
            value = userInputText(context);
        }
        // Compatibility: bare business alias may resolve through var.<alias> without dual-write.
        if (value == null && StringUtils.hasText(path) && !path.contains(".")
                && !WorkflowVariableNamespaces.isReservedRoot(path)) {
            value = context.get(WorkflowVariableNamespaces.VAR_ROOT + "." + path);
            if (value == null && context.get(WorkflowVariableNamespaces.VAR_ROOT) instanceof Map<?, ?> vars) {
                value = vars.get(path);
            }
        }
        if (value == null && path.contains(".")) {
            Object current = context;
            for (String part : path.split("\\.")) {
                if (!(current instanceof Map<?, ?> map)) {
                    current = null;
                    break;
                }
                current = map.get(part);
            }
            value = current;
        }
        return value;
    }

    private ClassifierDecision keywordDecision(String input, List<ClassifierClass> classes) {
        String normalizedInput = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        ClassifierDecision best = null;
        int bestScore = -1;
        boolean ambiguous = false;
        for (ClassifierClass candidate : classes) {
            for (String keyword : candidate.keywords()) {
                String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
                if (!StringUtils.hasText(normalizedKeyword) || !normalizedInput.contains(normalizedKeyword)) {
                    continue;
                }
                int score = normalizedKeyword.length();
                if (score > bestScore) {
                    bestScore = score;
                    best = new ClassifierDecision(candidate.id(), 1D, "keyword:" + keyword, null);
                    ambiguous = false;
                } else if (score == bestScore && best != null && !candidate.id().equals(best.route())) {
                    ambiguous = true;
                }
            }
        }
        // A HYBRID classifier must not silently prefer the first catalog entry when equally
        // specific keywords point at different intents. Returning null lets HYBRID ask the
        // configured model and makes a KEYWORD-only classifier use its explicit default route.
        return ambiguous ? null : best;
    }

    private ClassifierDecision modelDecision(GraphSpec.Node node,
                                               Map<String, Object> config,
                                               Map<String, Object> context,
                                               String input,
                                               List<ClassifierClass> classes,
                                               String defaultRoute,
                                               String modelInstanceId) throws Exception {
        List<Map<String, Object>> catalog = classes.stream()
                .map(item -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("id", item.id());
                    value.put("label", item.label());
                    value.put("description", item.description());
                    value.put("keywords", item.keywords());
                    return value;
                })
                .toList();
        String systemPrompt = "Classify the user input into exactly one route from this JSON catalog: "
                + objectMapper.writeValueAsString(catalog)
                + ". Return JSON only: {\"route\":\"<class id>\",\"confidence\":0.0}.";
        Map<String, Object> promptContext = new LinkedHashMap<>(context);
        promptContext.put("classifierInput", input);
        String userPrompt = firstText(
                renderTemplate(text(config.get("llmPrompt")), promptContext),
                "Input: " + input);
        ModelChatRequest modelRequest = ModelChatRequest.builder()
                .modelInstanceId(modelInstanceId)
                .messages(List.of(
                        ChatMessage.builder().role("system").content(systemPrompt).build(),
                        ChatMessage.builder().role("user").content(userPrompt).build()))
                .options(mapValue(firstPresent(config.get("modelParams"), config.get("options"))))
                .build();
        ModelChatResult result = modelServiceClient.chat(modelRequest);
        ModelChatData data = result == null ? null : result.getData();
        String output = data == null ? null : text(data.getContent());
        if (!StringUtils.hasText(output)) {
            throw new IllegalStateException("model service returned empty classifier content for node " + node.getId());
        }

        ModelClassifierOutput parsed = parseModelClassifierOutput(output);
        Set<String> allowedRoutes = new LinkedHashSet<>();
        classes.forEach(item -> allowedRoutes.add(item.id()));
        double threshold = Math.max(0D, Math.min(1D, doubleValue(config.get("confidenceThreshold"), 0.7D)));
        String proposedRoute = parsed.route();
        if (StringUtils.hasText(proposedRoute) && proposedRoute.startsWith("route:")) {
            proposedRoute = proposedRoute.substring("route:".length()).trim();
        }
        boolean accepted = StringUtils.hasText(proposedRoute)
                && allowedRoutes.contains(proposedRoute)
                && parsed.confidence() >= threshold;
        return new ClassifierDecision(
                accepted ? proposedRoute : defaultRoute,
                parsed.confidence(),
                accepted ? "llm" : "llm-default",
                output);
    }

    @SuppressWarnings("unchecked")
    private ModelClassifierOutput parseModelClassifierOutput(String output) {
        String candidate = output.trim();
        int objectStart = candidate.indexOf('{');
        int objectEnd = candidate.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(
                        candidate.substring(objectStart, objectEnd + 1), Map.class);
                String route = firstText(
                        text(parsed.get("route")),
                        text(parsed.get("classId")),
                        text(parsed.get("id")),
                        text(parsed.get("intent")));
                return new ModelClassifierOutput(route, doubleValue(parsed.get("confidence"), 1D));
            } catch (Exception ignored) {
                // Fall through to the plain route form for tolerant model compatibility.
            }
        }
        String plainRoute = candidate.replace("`", "").replace("\"", "").trim();
        return new ModelClassifierOutput(plainRoute, 1D);
    }

    private List<ClassifierClass> classifierClasses(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        List<ClassifierClass> classes = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            String id = text(raw.get("id"));
            if (!StringUtils.hasText(id)) {
                continue;
            }
            classes.add(new ClassifierClass(
                    id,
                    firstText(text(raw.get("label")), id),
                    firstText(text(raw.get("description")), ""),
                    classifierKeywords(raw.get("keywords"))));
        }
        return classes;
    }

    private List<String> classifierKeywords(Object value) {
        if (value instanceof List<?> items) {
            return items.stream().map(this::text).filter(StringUtils::hasText).toList();
        }
        if (value instanceof String raw) {
            return Pattern.compile("[,，]").splitAsStream(raw)
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .toList();
        }
        return List.of();
    }

    private String normalizeClassifierStrategy(String value) {
        String strategy = firstText(value, "KEYWORD").toUpperCase(Locale.ROOT);
        return "LLM".equals(strategy) || "HYBRID".equals(strategy) ? strategy : "KEYWORD";
    }

    private RuntimeGraphSpecExecutionResult executeAnswer(GraphSpec.Node node, Map<String, Object> context) {
        String answer = renderAnswer(node, context);
        Map<String, Object> metadata = nodeMetadata(node, "ANSWER");
        return new RuntimeGraphSpecExecutionResult(
                true,
                "RUNTIME_GRAPH_EXECUTED",
                answer,
                node.getId(),
                "ANSWER",
                List.of(step("execute-node", node.getId())),
                metadata);
    }

    private String renderAnswer(GraphSpec.Node node, Map<String, Object> request) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        String template = firstText(
                text(config.get("template")),
                text(config.get("answer")),
                text(config.get("content")),
                text(config.get("message")));
        if (!StringUtils.hasText(template)) {
            return firstText(text(request.get("message")), text(request.get("input")), "GraphSpec ANSWER node completed");
        }
        Matcher matcher = TEMPLATE_TOKEN.matcher(template);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            String replacement = resolveToken(matcher.group(1), request);
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement == null ? "" : replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private String resolveToken(String token, Map<String, Object> request) {
        String resolved = switch (token) {
            case "input", "userInput", "query" -> userInputText(request);
            case "message" -> firstText(text(request.get("message")), userInputText(request));
            case "lastOutput", "previousOutput" ->
                    firstText(text(request.get(token)), userInputText(request));
            default -> text(resolveContextValue(token, request));
        };
        return resolved == null ? "" : resolved;
    }

    private RuntimeGraphSpecExecutionResult executeLlm(GraphSpec.Node node,
                                                       Map<String, Object> request,
                                                       GraphSpec graph,
                                                       RuntimeGraphSpecExecutionEventSink eventSink,
                                                       RuntimeGraphSpecExecutionCancellation cancel) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        String modelInstanceId = resolveModelInstanceId(config, request);
        if (!StringUtils.hasText(modelInstanceId)) {
            return failure("RUNTIME_GRAPH_MODEL_REQUIRED",
                    "LLM node requires modelInstanceId on node config or request",
                    node.getId(),
                    "LLM");
        }
        if (cancel.isCancelled()) {
            return cancelled(node.getId(), "LLM");
        }
        ModelChatRequest modelRequest = ModelChatRequest.builder()
                .modelInstanceId(modelInstanceId)
                .messages(buildLlmMessages(config, request))
                .options(mapValue(firstPresent(config.get("modelParams"), config.get("options"))))
                .build();
        boolean publicUserOutput = isSafePublicUserOutputLlm(node, graph, config);
        try {
            // Feign sync chat：节点边界协作式取消，无法硬中断进行中的 HTTP
            ModelChatResult result = modelServiceClient.chat(modelRequest);
            if (cancel.isCancelled()) {
                // 丢弃成功结果；禁止 public delta / 成功 Memory 路径
                return cancelled(node.getId(), "LLM");
            }
            ModelChatData data = result == null ? null : result.getData();
            String answer = data == null ? null : text(data.getContent());
            if (!StringUtils.hasText(answer)) {
                return failure("RUNTIME_GRAPH_LLM_EMPTY",
                        "Model service returned empty content for LLM node: " + node.getId(),
                        node.getId(),
                        "LLM");
            }
            // 安全最终输出：整段答案一次性作为 node.delta / 公共增量（LLM 节点当前走 sync chat，无 Token 流）
            if (publicUserOutput && eventSink != null) {
                Map<String, Object> deltaPayload = safeNodePayload(node, "LLM", null);
                deltaPayload.put("publicUserOutput", true);
                eventSink.onNodeDelta(node.getId(), "LLM", answer, deltaPayload);
            }
            Map<String, Object> metadata = modelMetadata(node, data);
            metadata.put("publicUserOutput", publicUserOutput);
            return new RuntimeGraphSpecExecutionResult(
                    true,
                    "RUNTIME_GRAPH_EXECUTED",
                    answer,
                    node.getId(),
                    "LLM",
                    List.of(step("execute-node", node.getId())),
                    metadata);
        } catch (Exception ex) {
            if (cancel.isCancelled()) {
                return cancelled(node.getId(), "LLM");
            }
            return failure("RUNTIME_GRAPH_LLM_FAILED",
                    "LLM node execution failed: " + ex.getMessage(),
                    node.getId(),
                    "LLM");
        }
    }

    /**
     * 仅当 LLM 节点可被确定性判定为用户最终输出时，才允许公开文本增量。
     * 不凭节点名称猜测；内部分类/参数抽取等不得公开。
     */
    private boolean isSafePublicUserOutputLlm(GraphSpec.Node node, GraphSpec graph, Map<String, Object> config) {
        if (config != null && Boolean.TRUE.equals(config.get("publicUserOutput"))) {
            return true;
        }
        if (graph == null || node == null || !StringUtils.hasText(node.getId())) {
            return false;
        }
        List<GraphSpec.Edge> outgoing = graph.getEdges() == null ? List.of() : graph.getEdges().stream()
                .filter(edge -> edge != null && node.getId().equals(text(edge.getFrom())))
                .toList();
        if (outgoing.isEmpty()) {
            // 终止 LLM 节点
            return true;
        }
        if (outgoing.size() != 1) {
            return false;
        }
        GraphSpec.Edge edge = outgoing.get(0);
        String targetId = text(edge.getTo());
        GraphSpec.Node next = graph.getNodes() == null ? null : graph.getNodes().stream()
                .filter(candidate -> candidate != null && targetId != null
                        && targetId.equals(candidate.getId()))
                .findFirst()
                .orElse(null);
        if (next == null || !"ANSWER".equals(AgentGraphNodeType.normalize(next.getType()))) {
            return false;
        }
        return isPassthroughAnswer(next);
    }

    private boolean isPassthroughAnswer(GraphSpec.Node answerNode) {
        Map<String, Object> config = answerNode.getConfig() == null ? Map.of() : answerNode.getConfig();
        String template = firstText(
                text(config.get("template")),
                text(config.get("answer")),
                text(config.get("content")),
                text(config.get("message")));
        if (!StringUtils.hasText(template)) {
            return true;
        }
        String normalized = template.trim();
        return "{{lastOutput}}".equals(normalized)
                || "{{previousOutput}}".equals(normalized)
                || "{{last_output}}".equals(normalized)
                || "{{previous_output}}".equals(normalized);
    }

    private RuntimeGraphSpecExecutionResult executeInteraction(GraphSpec.Node node, Map<String, Object> context) {
        return WorkflowInteractionNodeHandler.execute(node, context);
    }

    private RuntimeGraphSpecExecutionResult executeTool(GraphSpec.Node node,
                                                        String nodeType,
                                                        Map<String, Object> context,
                                                        RuntimeGraphSpecExecutionCancellation cancel) {
        String qualifiedName = resolveQualifiedName(node);
        if (!StringUtils.hasText(qualifiedName)) {
            return failure("RUNTIME_GRAPH_TOOL_REF_REQUIRED",
                    nodeType + " node requires ref.qualifiedName, config.qualifiedName, or config.ref",
                    node.getId(),
                    nodeType);
        }
        if (cancel.isCancelled()) {
            return cancelled(node.getId(), nodeType);
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("input", buildToolInput(node, context));
        request.put("context", toolExecutionContext(node, nodeType, context));
        request.put(RuntimeCapabilityCatalogClient.TRUSTED_IDENTITY_ATTRIBUTE,
                resolveTrustedIdentity(context));
        try {
            // Feign sync executeTool：节点边界协作式取消，无法硬中断进行中的 HTTP
            Map<String, Object> result = capabilityClient.executeTool(qualifiedName, request);
            if (cancel.isCancelled()) {
                return cancelled(node.getId(), nodeType);
            }
            Object businessOutput = firstPresent(result == null ? null : result.get("data"),
                    result == null ? null : result.get("result"));
            businessOutput = firstPresent(businessOutput, result == null ? null : result.get("body"));
            Object output = firstPresent(businessOutput, result);
            Map<String, Object> metadata = nodeMetadata(node, nodeType);
            metadata.put("qualifiedName", qualifiedName);
            if (output != null) {
                metadata.put("structuredOutput", output);
            }
            Optional<BusinessResponseEnvelope.Failure> payloadFailure =
                    BusinessResponseEnvelope.failure(businessOutput);
            boolean upstreamBusinessFailure = result != null
                    && "CAPABILITY_BUSINESS_RESPONSE_FAILED".equals(text(result.get("code")));
            if (payloadFailure.isPresent() || upstreamBusinessFailure) {
                BusinessResponseEnvelope.Failure failure = payloadFailure.orElseGet(() ->
                        new BusinessResponseEnvelope.Failure(
                                text(result.get("businessCode")),
                                firstText(text(result.get("message")), BusinessResponseEnvelope.FAILURE_MESSAGE)));
                metadata.put("retryableFailure", false);
                if (failure.businessCode() != null) {
                    metadata.put("businessCode", failure.businessCode());
                }
                return new RuntimeGraphSpecExecutionResult(
                        false,
                        "RUNTIME_GRAPH_TOOL_BUSINESS_RESPONSE_FAILED",
                        failure.message(),
                        node.getId(),
                        nodeType,
                        List.of(step("execute-node", node.getId())),
                        metadata);
            }
            if (result != null && Boolean.FALSE.equals(result.get("success"))) {
                metadata.put("retryableFailure", false);
                return new RuntimeGraphSpecExecutionResult(
                        false,
                        firstText(text(result.get("code")), "RUNTIME_GRAPH_TOOL_FAILED"),
                        firstText(text(result.get("message")), nodeType + " node execution failed"),
                        node.getId(),
                        nodeType,
                        List.of(step("execute-node", node.getId())),
                        metadata);
            }
            String answer = output == null ? "" : String.valueOf(output);
            return new RuntimeGraphSpecExecutionResult(
                    true,
                    "RUNTIME_GRAPH_EXECUTED",
                    answer,
                    node.getId(),
                    nodeType,
                    List.of(step("execute-node", node.getId())),
                    metadata);
        } catch (Exception ex) {
            if (cancel.isCancelled()) {
                return cancelled(node.getId(), nodeType);
            }
            return failure("RUNTIME_GRAPH_TOOL_FAILED",
                    nodeType + " node execution failed: " + ex.getMessage(),
                    node.getId(),
                    nodeType);
        }
    }

    private Map<String, Object> toolExecutionContext(GraphSpec.Node node,
                                                     String nodeType,
                                                     Map<String, Object> context) {
        Map<String, Object> executionContext = new LinkedHashMap<>();
        executionContext.put("nodeId", node.getId());
        executionContext.put("nodeType", nodeType);
        Map<String, Object> metadata = mapValue(context.get("metadata"));
        copyContextValue(executionContext, "tenantId", context, metadata);
        copyContextValue(executionContext, "externalUserId", context, metadata);
        copyContextValue(executionContext, "globalUserId", context, metadata);
        copyContextValue(executionContext, "userName", context, metadata);
        copyContextValue(executionContext, "deptId", context, metadata);
        copyContextValue(executionContext, "deptName", context, metadata);
        copyContextValue(executionContext, "roles", context, metadata);
        copyContextValue(executionContext, "attributes", context, metadata);
        copyContextValue(executionContext, "agentId", context, metadata);
        copyContextValue(executionContext, "sessionId", context, metadata);
        copyContextValue(executionContext, "supervisorTraceId", context, metadata);
        copyContextValue(executionContext, "pageInstanceId", context, metadata);
        copyContextValue(executionContext, "origin", context, metadata);
        copyContextValue(executionContext, "route", context, metadata);
        if (!executionContext.containsKey("externalUserId") && context.get("userId") != null) {
            executionContext.put("externalUserId", context.get("userId"));
        }
        return executionContext;
    }

    private void copyContextValue(Map<String, Object> target,
                                  String key,
                                  Map<String, Object> context,
                                  Map<String, Object> metadata) {
        Object value = context.get(key);
        if (value == null && metadata != null) {
            value = metadata.get(key);
        }
        if (value != null) {
            target.put(key, value);
        }
    }

    private RuntimeGraphSpecExecutionResult executePageAction(GraphSpec.Node node,
                                                               Map<String, Object> context,
                                                               RuntimeGraphSpecExecutionCancellation cancel) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        String sessionId = text(context.get("sessionId"));
        String projectCode = firstText(text(config.get("projectCode")), text(context.get("projectCode")));
        String agentId = text(context.get("agentId"));
        String targetPageKey = text(config.get("pageKey"));
        String actionKey = text(config.get("actionKey"));
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(projectCode)
                || !StringUtils.hasText(agentId) || !StringUtils.hasText(targetPageKey)
                || !StringUtils.hasText(actionKey)) {
            return failure("RUNTIME_PAGE_ACTION_CONTEXT_REQUIRED",
                    "PAGE_ACTION requires sessionId, projectCode, agentId, pageKey and actionKey",
                    node.getId(), "PAGE_ACTION");
        }
        if (cancel.isCancelled()) {
            return cancelled(node.getId(), "PAGE_ACTION");
        }
        Map<String, Object> args = buildToolInput(node, context);
        int executionTimeoutMs = intValue(
                context.get("pageBridgeTimeoutMs"), DEFAULT_PAGE_BRIDGE_EXECUTION_TIMEOUT_MS);
        try {
            // Feign sync Page Bridge：节点边界协作式取消，无法硬中断进行中的 HTTP
            PageBridgeExecutionResponse response = controlClient.executePageBridge(new PageBridgeExecutionRequest(
                    sessionId,
                    projectCode,
                    agentId,
                    text(context.get("pageKey")),
                    targetPageKey,
                    firstText(text(config.get("route")), text(config.get("routePattern"))),
                    actionKey,
                    args,
                    Boolean.TRUE.equals(config.get("confirm")) || Boolean.TRUE.equals(config.get("confirmRequired")),
                    DEFAULT_PAGE_BRIDGE_CONFIRMATION_TIMEOUT_MS,
                    executionTimeoutMs));
            if (cancel.isCancelled()) {
                return cancelled(node.getId(), "PAGE_ACTION");
            }
            Map<String, Object> metadata = nodeMetadata(node, "PAGE_ACTION");
            metadata.put("pageKey", targetPageKey);
            metadata.put("actionKey", actionKey);
            if (response != null) {
                if (response.code() != null) {
                    metadata.put("pageBridgeCode", response.code());
                }
                if (response.status() != null) {
                    metadata.put("pageBridgeStatus", response.status());
                }
                if (response.phases() != null) {
                    metadata.put("pageBridgePhases", response.phases());
                }
                if (response.data() != null) {
                    metadata.put("pageBridgeData", response.data());
                }
                Map<String, Object> pageActionSummary = pageActionResultSummary(actionKey, response);
                metadata.put("pageActionResultSummary", pageActionSummary);
                // Node trace output must stay useful without replaying business rows or raw bridge data.
                metadata.put("traceSummary", pageActionSummary);
                if (response.data() != null) {
                    metadata.put("structuredOutput", response.data());
                }
                if (isBusinessTerminalPageAction(response)) {
                    metadata.put("outcomeClass", "BUSINESS_TERMINAL");
                    metadata.put("businessOutcome", response.status());
                }
            }
            if (response == null || !response.success()) {
                return new RuntimeGraphSpecExecutionResult(false,
                        response == null ? "RUNTIME_PAGE_ACTION_EMPTY" : response.code(),
                        response == null ? "Page Bridge returned no response" : response.status(),
                        node.getId(), "PAGE_ACTION", List.of(step("execute-page-action", node.getId())), metadata);
            }
            if (isBusinessTerminalPageAction(response)) {
                return new RuntimeGraphSpecExecutionResult(
                        true,
                        "RUNTIME_PAGE_ACTION_BUSINESS_TERMINAL",
                        pageActionBusinessMessage(response),
                        node.getId(),
                        "PAGE_ACTION",
                        List.of(step("execute-page-action", node.getId())),
                        metadata);
            }
            return new RuntimeGraphSpecExecutionResult(true, "RUNTIME_PAGE_ACTION_EXECUTED",
                    response.data() == null ? response.status() : String.valueOf(response.data()),
                    node.getId(), "PAGE_ACTION", List.of(step("execute-page-action", node.getId())), metadata);
        } catch (Exception ex) {
            if (cancel.isCancelled()) {
                return cancelled(node.getId(), "PAGE_ACTION");
            }
            return failure("RUNTIME_PAGE_ACTION_FAILED", "PAGE_ACTION failed: " + ex.getMessage(),
                    node.getId(), "PAGE_ACTION");
        }
    }

    /**
     * Normalize page-action outcomes for supervisor final-answer grounding and trace display.
     * This deliberately keeps only action state, a declared aggregate count and an optional short
     * business message. Raw rows remain in the normal Workflow context for downstream nodes, but
     * are never copied into the cross-layer result summary.
     */
    private Map<String, Object> pageActionResultSummary(String actionKey,
                                                        PageBridgeExecutionResponse response) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("actionKey", actionKey);
        summary.put("success", response.success());
        String status = firstText(response.status(), response.success() ? "SUCCESS" : "FAILED");
        summary.put("status", status);
        if (isBusinessTerminalPageAction(response)) {
            summary.put("outcomeClass", "BUSINESS_TERMINAL");
            summary.put("businessOutcome", status);
        }

        Long total = null;
        String message = null;
        for (Map<String, Object> payload : pageActionPayloadCandidates(mapValue(response.data()))) {
            if (!StringUtils.hasText(message)) {
                message = safePageActionMessage(payload.get("message"));
            }
            if (total == null) {
                total = pageActionCount(payload);
            }
        }
        if (StringUtils.hasText(message)) {
            summary.put("message", message);
        } else if (isBusinessTerminalPageAction(response)) {
            summary.put("message", pageActionBusinessMessage(response));
        }
        if (total != null) {
            summary.put("total", total);
            summary.put("empty", total == 0L);
        }
        if (pageActionPayloadCandidates(mapValue(response.data())).stream()
                .anyMatch(payload -> Boolean.TRUE.equals(payload.get("userConfirmed")))) {
            summary.put("userConfirmed", true);
        }
        return Map.copyOf(summary);
    }

    private boolean isBusinessTerminalPageAction(
            PageBridgeExecutionResponse response) {
        if (response == null || !response.success()) {
            return false;
        }
        if ("PAGE_BRIDGE_BUSINESS_TERMINAL".equalsIgnoreCase(response.code())) {
            return true;
        }
        String status = text(response.status());
        return "NO_DATA".equalsIgnoreCase(status)
                || "PRECONDITION_FAILED".equalsIgnoreCase(status)
                || "USER_CANCELLED".equalsIgnoreCase(status)
                || "CANCELLED".equalsIgnoreCase(status);
    }

    private String pageActionBusinessMessage(
            PageBridgeExecutionResponse response) {
        for (Map<String, Object> payload : pageActionPayloadCandidates(
                mapValue(response == null ? null : response.data()))) {
            String message = safePageActionMessage(payload.get("message"));
            if (StringUtils.hasText(message)) {
                return message;
            }
        }
        String status = response == null ? null : text(response.status());
        if ("NO_DATA".equalsIgnoreCase(status)) {
            return "未查询到符合条件的数据。";
        }
        if ("PRECONDITION_FAILED".equalsIgnoreCase(status)) {
            return "当前业务状态不满足执行该页面操作的条件。";
        }
        if ("USER_CANCELLED".equalsIgnoreCase(status)
                || "CANCELLED".equalsIgnoreCase(status)) {
            return "已取消本次页面操作。";
        }
        return "页面操作已结束，未产生可继续执行的业务结果。";
    }

    private List<Map<String, Object>> pageActionPayloadCandidates(Map<String, Object> root) {
        if (root == null || root.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> candidates = new ArrayList<>();
        candidates.add(root);
        // Page Bridge returns handler data directly, while common SDK handlers return
        // {status, message, data}. Support both without an unbounded recursive walk.
        for (int index = 0; index < candidates.size() && index < 4; index++) {
            Map<String, Object> candidate = candidates.get(index);
            for (String nestedKey : List.of("data", "result", "payload")) {
                Map<String, Object> nested = mapValue(candidate.get(nestedKey));
                if (nested != null && !candidates.contains(nested)) {
                    candidates.add(nested);
                }
            }
        }
        return candidates;
    }

    private Long pageActionCount(Map<String, Object> payload) {
        for (String countKey : List.of("total", "totalCount", "rowCount", "count")) {
            Long count = nonNegativeIntegralCount(payload.get(countKey));
            if (count != null) {
                return count;
            }
        }
        for (String collectionKey : List.of("records", "rows", "items", "list", "courses")) {
            Object value = payload.get(collectionKey);
            if (value instanceof Collection<?> collection) {
                return (long) collection.size();
            }
        }
        return null;
    }

    private Long nonNegativeIntegralCount(Object value) {
        if (value instanceof Number number) {
            double decimal = number.doubleValue();
            long integral = number.longValue();
            return Double.isFinite(decimal) && decimal == integral && integral >= 0 ? integral : null;
        }
        if (value instanceof CharSequence sequence) {
            String text = sequence.toString().trim();
            if (text.matches("\\d+")) {
                try {
                    return Long.parseLong(text);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private String safePageActionMessage(Object value) {
        if (!(value instanceof CharSequence sequence)) {
            return null;
        }
        String message = sequence.toString().trim();
        if (!StringUtils.hasText(message)) {
            return null;
        }
        return message.length() <= 240 ? message : message.substring(0, 240);
    }

    private Map<String, Object> buildToolInput(GraphSpec.Node node, Map<String, Object> context) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> mapping = mapValue(config.get("inputMapping"));
        if (mapping == null || mapping.isEmpty()) {
            mapping = mapValue(config.get("args"));
        }
        if (mapping == null || mapping.isEmpty()) {
            // PAGE_ACTION intentionally supports a zero-argument contract. Studio
            // serializes that as args={}, which must not be replaced by an
            // unrelated previous-node output.
            if (config.containsKey("args")) {
                return Map.of();
            }
            String input = firstText(text(context.get("lastOutput")), text(context.get("input")));
            return Map.of("input", input == null ? "" : input);
        }
        Map<String, Object> input = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : mapping.entrySet()) {
            input.put(entry.getKey(), renderInputValue(entry.getValue(), context));
        }
        return input;
    }

    private Object renderInputValue(Object value, Map<String, Object> context) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> rendered = new LinkedHashMap<>();
            rawMap.forEach((key, item) -> rendered.put(String.valueOf(key), renderInputValue(item, context)));
            return rendered;
        }
        if (value instanceof List<?> rawList) {
            return rawList.stream().map(item -> renderInputValue(item, context)).toList();
        }
        if (value instanceof String template) {
            if (!template.contains("{{") && isContextExpression(template, context)) {
                return resolveContextValue(template, context);
            }
            return renderTemplate(template, context);
        }
        return value;
    }

    private boolean isContextExpression(String value, Map<String, Object> context) {
        if (!StringUtils.hasText(value)) return false;
        String candidate = value.trim();
        if (context.containsKey(candidate)) return true;
        if (candidate.startsWith("$.")) candidate = candidate.substring(2);
        int separator = candidate.indexOf('.');
        String root = separator < 0 ? candidate : candidate.substring(0, separator);
        boolean bareBusinessVariable = separator < 0
                && context.get(WorkflowVariableNamespaces.VAR_ROOT) instanceof Map<?, ?> variables
                && variables.containsKey(candidate);
        return context.get(root) instanceof Map<?, ?>
                || candidate.startsWith("params.")
                || candidate.startsWith("nodeOutput.")
                || candidate.startsWith("var.")
                || candidate.startsWith("sys.")
                || "input".equals(candidate)
                || "message".equals(candidate)
                || "lastOutput".equals(candidate)
                || "previousOutput".equals(candidate)
                || (StringUtils.hasText(root)
                && !WorkflowVariableNamespaces.isReservedRoot(root)
                && (context.containsKey(WorkflowVariableNamespaces.VAR_ROOT + "." + candidate)
                || bareBusinessVariable));
    }

    private String resolveQualifiedName(GraphSpec.Node node) {
        if (node.getRef() != null) {
            String qualifiedName = firstText(
                    text(node.getRef().getQualifiedName()),
                    text(node.getRef().getName()));
            if (StringUtils.hasText(qualifiedName)) {
                return qualifiedName;
            }
        }
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> nested = mapValue(firstPresent(config.get("toolConfig"), config.get("capabilityConfig")));
        return firstText(
                text(config.get("qualifiedName")),
                configuredReference(config.get("ref")),
                text(config.get("toolName")),
                nested == null ? null : text(nested.get("qualifiedName")),
                nested == null ? null : configuredReference(nested.get("ref")),
                nested == null ? null : text(nested.get("toolName")));
    }

    private String configuredReference(Object rawReference) {
        Map<String, Object> reference = mapValue(rawReference);
        if (reference != null) {
            return firstText(
                    text(reference.get("qualifiedName")),
                    text(reference.get("name")),
                    reference.get("ref") instanceof Map<?, ?>
                            ? configuredReference(reference.get("ref"))
                            : text(reference.get("ref")),
                    text(reference.get("toolName")));
        }
        return text(rawReference);
    }

    private List<ChatMessage> buildLlmMessages(Map<String, Object> config, Map<String, Object> request) {
        List<ChatMessage> messages = new ArrayList<>();
        String systemPrompt = renderTemplate(text(config.get("systemPrompt")), request);
        if (StringUtils.hasText(systemPrompt)) {
            messages.add(ChatMessage.builder().role("system").content(systemPrompt).build());
        }

        Object configuredMessages = config.get("messages");
        if (configuredMessages instanceof List<?> items && !items.isEmpty()) {
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> message)) {
                    continue;
                }
                Object enabled = message.get("enabled");
                if (Boolean.FALSE.equals(enabled)) {
                    continue;
                }
                String role = firstText(text(message.get("role")), "user");
                String content = renderTemplate(text(message.get("content")), request);
                if (StringUtils.hasText(content)) {
                    messages.add(ChatMessage.builder().role(role).content(content).build());
                }
            }
        }

        if (messages.stream().noneMatch(message -> "user".equalsIgnoreCase(message.getRole()))) {
            String userPrompt = firstText(
                    renderTemplate(text(config.get("userPrompt")), request),
                    renderTemplate(text(config.get("prompt")), request),
                    userInputText(request));
            if (StringUtils.hasText(userPrompt)) {
                messages.add(ChatMessage.builder().role("user").content(userPrompt).build());
            }
        }
        return messages;
    }

    private String renderTemplate(String template, Map<String, Object> request) {
        if (!StringUtils.hasText(template)) {
            return null;
        }
        Matcher matcher = TEMPLATE_TOKEN.matcher(template);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            String replacement = resolveToken(matcher.group(1), request);
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement == null ? "" : replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private Map<String, Object> modelMetadata(GraphSpec.Node node, ModelChatData data) {
        Map<String, Object> metadata = nodeMetadata(node, "LLM");
        putIfPresent(metadata, "model", data.getModel());
        putIfPresent(metadata, "provider", data.getProvider());
        putIfPresent(metadata, "usage", data.getUsage());
        // 禁止把 reasoning 正文写入 metadata / Trace 公共字段
        if (data.getReasoningContent() != null && !data.getReasoningContent().isEmpty()) {
            metadata.put("reasoningLength", data.getReasoningContent().length());
        }
        putIfPresent(metadata, "finishReason", data.getFinishReason());
        return metadata;
    }

    private Map<String, Object> nodeMetadata(GraphSpec.Node node, String nodeType) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("nodeId", node.getId());
        metadata.put("nodeType", nodeType);
        return metadata;
    }

    private Map<String, Object> initialContext(Map<String, Object> request) {
        Map<String, Object> context = new LinkedHashMap<>(request);
        String input = userInputText(request);
        context.putIfAbsent("input", input);
        context.putIfAbsent("userInput", input);
        context.putIfAbsent("message", input);
        context.putIfAbsent("lastOutput", input);
        context.putIfAbsent("previousOutput", input);
        context.putIfAbsent(WorkflowVariableNamespaces.VAR_ROOT, new LinkedHashMap<String, Object>());
        if (!(context.get(WorkflowVariableNamespaces.SYS_ROOT) instanceof Map<?, ?>)) {
            Map<String, Object> sys = new LinkedHashMap<>();
            putIfPresent(sys, "userId", firstPresent(request.get("userId"), request.get("externalUserId")));
            putIfPresent(sys, "tenantId", request.get("tenantId"));
            putIfPresent(sys, "roles", request.get("roles"));
            putIfPresent(sys, "projectCode", request.get("projectCode"));
            putIfPresent(sys, "projectId", request.get("projectId"));
            putIfPresent(sys, "sessionId", request.get("sessionId"));
            context.put(WorkflowVariableNamespaces.SYS_ROOT, sys);
        }
        return context;
    }

    private void rememberNodeOutput(Map<String, Object> context,
                                    GraphSpec.Node node,
                                    RuntimeGraphSpecExecutionResult result) {
        String answer = result.answer();
        String nodeType = AgentGraphNodeType.normalize(node.getType());
        Object output = structuredOutput(result, answer);
        rememberOutputPath(context, "nodeOutput." + node.getId(), output);
        Map<String, Object> existingNodeOutputs = mapValue(context.get("nodeOutput"));
        Map<String, Object> nodeOutputs = existingNodeOutputs == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(existingNodeOutputs);
        nodeOutputs.put(node.getId(), output);
        context.put("nodeOutput", nodeOutputs);
        if ("PAGE_ACTION".equals(nodeType)) {
            rememberPageActionResult(context, result);
        }
        String outputAlias = nodeOutputAlias(node);
        if (StringUtils.hasText(outputAlias)
                && WorkflowVariableNamespaces.isValidAlias(outputAlias)
                && !WorkflowVariableNamespaces.isReservedAlias(outputAlias)) {
            WorkflowOutputAliasWriter.writeAlias(context, outputAlias, output);
        }
        if ("INTENT_CLASSIFIER".equals(nodeType) || "IF_ELSE".equals(nodeType)) {
            return;
        }
        Object previousOutput = context.get("lastOutput");
        rememberOutputPath(context, "previousOutput", previousOutput);
        rememberOutputPath(context, "lastOutput", output);
    }

    private void rememberPageActionResult(Map<String, Object> context,
                                          RuntimeGraphSpecExecutionResult result) {
        if (result.metadata() == null) {
            return;
        }
        Map<String, Object> summary = mapValue(result.metadata().get("pageActionResultSummary"));
        if (summary == null || summary.isEmpty()) {
            return;
        }
        List<Map<String, Object>> history = new ArrayList<>();
        Object existing = context.get("pageActionResults");
        if (existing instanceof List<?> items) {
            for (Object item : items) {
                Map<String, Object> previous = mapValue(item);
                if (previous != null && !previous.isEmpty()) {
                    history.add(Map.copyOf(previous));
                }
            }
        }
        history.add(Map.copyOf(summary));
        context.put("pageActionResults", List.copyOf(history));
    }

    private String nodeOutputAlias(GraphSpec.Node node) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        return firstText(
                text(config.get("outputAlias")),
                text(mapValue(config.get("userInputConfig")) == null
                        ? null : mapValue(config.get("userInputConfig")).get("outputAlias")),
                text(mapValue(config.get("interactionConfig")) == null
                        ? null : mapValue(config.get("interactionConfig")).get("outputAlias")),
                text(mapValue(config.get("pageActionConfig")) == null
                        ? null : mapValue(config.get("pageActionConfig")).get("outputAlias")));
    }

    private Object structuredOutput(RuntimeGraphSpecExecutionResult result, String answer) {
        if (result.metadata() != null && result.metadata().containsKey("structuredOutput")) {
            return result.metadata().get("structuredOutput");
        }
        return firstText(answer, "");
    }

    private void rememberOutputPath(Map<String, Object> context, String path, Object output) {
        context.keySet().removeIf(key -> key.startsWith(path + "."));
        context.put(path, output);
        flattenOutputPath(context, path, output);
    }

    private void flattenOutputPath(Map<String, Object> context, String path, Object output) {
        if (!(output instanceof Map<?, ?> map)) {
            return;
        }
        map.forEach((key, value) -> {
            String childPath = path + "." + key;
            context.put(childPath, value);
            flattenOutputPath(context, childPath, value);
        });
    }

    private String resolveModelInstanceId(Map<String, Object> config, Map<String, Object> context) {
        String nodeModelInstanceId = text(config.get("modelInstanceId"));
        if (StringUtils.hasText(nodeModelInstanceId)) {
            return nodeModelInstanceId;
        }
        if (context.containsKey("workflowDefaultModelInstanceId")) {
            return text(context.get("workflowDefaultModelInstanceId"));
        }
        return text(context.get("modelInstanceId"));
    }

    private NextNodeResolution resolveNextNode(GraphSpec graph,
                                               String nodeId,
                                               RuntimeGraphSpecExecutionResult nodeResult) {
        if (graph.getEdges() == null || graph.getEdges().isEmpty()) {
            return NextNodeResolution.unmatched();
        }
        String route = resultRoute(nodeResult);
        String target = graph.getEdges().stream()
                .filter(edge -> edge != null && nodeId.equals(text(edge.getFrom())))
                .filter(edge -> edgeMatchRank(edge, route) < Integer.MAX_VALUE)
                .sorted(Comparator
                        .comparingInt((GraphSpec.Edge edge) -> edgeMatchRank(edge, route))
                        .thenComparing(edge -> edge.getPriority() == null ? Integer.MAX_VALUE : edge.getPriority()))
                .map(GraphSpec.Edge::getTo)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
        if (!StringUtils.hasText(target)) {
            return NextNodeResolution.unmatched();
        }
        return NextNodeResolution.node(target);
    }

    private String resultRoute(RuntimeGraphSpecExecutionResult nodeResult) {
        return nodeResult == null || nodeResult.metadata() == null
                ? null
                : text(firstPresent(nodeResult.metadata().get("route"), nodeResult.metadata().get("lastRoute")));
    }

    private static boolean isStrictInteractionRoute(String route) {
        if (!StringUtils.hasText(route)) {
            return false;
        }
        String normalized = route.trim().toLowerCase(Locale.ROOT);
        return "confirm".equals(normalized)
                || "reject".equals(normalized)
                || "cancel".equals(normalized)
                || "approve".equals(normalized)
                || "deny".equals(normalized);
    }

    private boolean hasExactRouteEdge(GraphSpec graph, String nodeId, String route) {
        if (graph.getEdges() == null || !StringUtils.hasText(route)) {
            return false;
        }
        for (GraphSpec.Edge edge : graph.getEdges()) {
            if (edge == null || !nodeId.equals(text(edge.getFrom()))) {
                continue;
            }
            String condition = text(edge.getCondition());
            if (!StringUtils.hasText(condition)) {
                continue;
            }
            String expectedRoute = condition.regionMatches(true, 0, "route:", 0, "route:".length())
                    ? condition.substring("route:".length()).trim()
                    : condition;
            if (route.equalsIgnoreCase(expectedRoute)) {
                return true;
            }
        }
        return false;
    }

    private int edgeMatchRank(GraphSpec.Edge edge, String route) {
        String condition = text(edge.getCondition());
        boolean unconditional = !StringUtils.hasText(condition)
                || "always".equalsIgnoreCase(condition)
                || "success".equalsIgnoreCase(condition);
        if (StringUtils.hasText(route) && StringUtils.hasText(condition)) {
            String expectedRoute = condition.regionMatches(true, 0, "route:", 0, "route:".length())
                    ? condition.substring("route:".length()).trim()
                    : condition;
            if (route.equalsIgnoreCase(expectedRoute)) {
                return 0;
            }
            if (("else".equalsIgnoreCase(condition) || "default".equalsIgnoreCase(condition))
                    && ("else".equalsIgnoreCase(route) || "default".equalsIgnoreCase(route))) {
                return 0;
            }
        }
        return unconditional ? (StringUtils.hasText(route) ? 1 : 0) : Integer.MAX_VALUE;
    }

    private record NextNodeResolution(boolean matched, String nodeId) {
        private static NextNodeResolution unmatched() {
            return new NextNodeResolution(false, null);
        }

        private static NextNodeResolution node(String nodeId) {
            return new NextNodeResolution(true, nodeId);
        }
    }

    private RuntimeGraphSpecExecutionResult withSteps(RuntimeGraphSpecExecutionResult result,
                                                      List<Map<String, Object>> steps,
                                                      List<Map<String, Object>> nodeTraces) {
        Map<String, Object> metadata = result.metadata() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(result.metadata());
        metadata.put("workflowNodeTraces", List.copyOf(nodeTraces == null ? List.of() : nodeTraces));
        return new RuntimeGraphSpecExecutionResult(
                result.success(),
                result.code(),
                result.answer(),
                result.nodeId(),
                result.nodeType(),
                List.copyOf(steps),
                metadata,
                result.resumeCheckpoint());
    }

    private Map<String, Object> buildInternalNodeTrace(GraphSpec.Node node,
                                                       String nodeType,
                                                       RuntimeGraphSpecExecutionResult nodeResult,
                                                       long startedAtMs,
                                                       long endedAtMs,
                                                       long latencyMs) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("nodeId", node.getId());
        trace.put("nodeType", nodeType);
        String status;
        if (nodeResult.isWaitingUser() || WorkflowInteractionCodes.WAITING.equals(nodeResult.code())) {
            status = "WAITING_USER";
        } else if ("RUNTIME_GRAPH_CANCELLED".equals(nodeResult.code())) {
            status = "CANCELLED";
        } else if (isBusinessTerminalResult(nodeResult)) {
            status = "BUSINESS_TERMINAL";
        } else if (nodeResult.success()) {
            status = "SUCCESS";
        } else {
            status = "FAILED";
        }
        trace.put("status", status);
        trace.put("startedAt", startedAtMs);
        if (!"WAITING_USER".equals(status)) {
            trace.put("endedAt", endedAtMs);
        }
        trace.put("latencyMs", latencyMs);
        if (nodeResult.metadata() != null) {
            putIfPresent(trace, "attempt", nodeResult.metadata().get("attempt"));
            putIfPresent(trace, "maxAttempts", nodeResult.metadata().get("maxAttempts"));
            putIfPresent(trace, "errorPolicy", nodeResult.metadata().get("errorPolicyDecision"));
            putIfPresent(trace, "fallbackNodeId", nodeResult.metadata().get("fallbackNodeId"));
            putIfPresent(trace, "outcomeClass", nodeResult.metadata().get("outcomeClass"));
            putIfPresent(trace, "businessOutcome", nodeResult.metadata().get("businessOutcome"));
            putIfPresent(trace, "interactionType", nodeResult.metadata().get("interactionType"));
            Object summary = nodeResult.metadata().get("traceSummary");
            if (summary instanceof Map<?, ?> map) {
                trace.put("traceSummary", new LinkedHashMap<>((Map<String, Object>) map));
            } else if (summary != null) {
                trace.put("traceSummary", summary);
            }
        }
        if (!nodeResult.success()) {
            putIfPresent(trace, "failureCode", nodeResult.code());
        }
        if (nodeResult.interactionId() != null) {
            trace.put("interactionId", nodeResult.interactionId());
        }
        if (nodeResult.uiRequest() != null) {
            trace.put("uiRequest", nodeResult.uiRequest());
        }
        return trace;
    }

    private boolean isBusinessTerminalResult(
            RuntimeGraphSpecExecutionResult result) {
        return result != null
                && result.success()
                && result.metadata() != null
                && "BUSINESS_TERMINAL".equalsIgnoreCase(
                text(result.metadata().get("outcomeClass")));
    }

    private void putIfPresent(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private WorkflowExecutionIdentity resolveTrustedIdentity(Map<String, Object> context) {
        if (context == null) {
            return WorkflowExecutionIdentity.untrustedDebug();
        }
        Object raw = context.get(TRUSTED_IDENTITY_CONTEXT_KEY);
        if (raw instanceof WorkflowExecutionIdentity identity) {
            return identity;
        }
        // Runtime-owned session snapshots may rehydrate identity as a Map; trust flags are
        // recomputed from source only (never from client-supplied projectTrusted/userTrusted).
        if (raw instanceof Map<?, ?> map) {
            return WorkflowExecutionIdentity.restoreFromContextMap(map);
        }
        return WorkflowExecutionIdentity.untrustedDebug();
    }

    private Long resolveProjectId(Map<String, Object> context) {
        // Non-security convenience only (e.g. PAGE_ACTION display). Credentials/ACL must use trusted identity.
        Object direct = context.get("projectId");
        if (direct instanceof Number number) {
            return number.longValue();
        }
        Object sys = context.get("sys");
        if (sys instanceof Map<?, ?> sysMap) {
            Object nested = sysMap.get("projectId");
            if (nested instanceof Number number) {
                return number.longValue();
            }
            if (nested != null) {
                try {
                    return Long.parseLong(String.valueOf(nested).trim());
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
        }
        return null;
    }

    private String resolveProjectCode(Map<String, Object> context) {
        String direct = text(context.get("projectCode"));
        if (StringUtils.hasText(direct)) {
            return direct;
        }
        Object sys = context.get("sys");
        if (sys instanceof Map<?, ?> sysMap) {
            String nested = text(sysMap.get("projectCode"));
            if (StringUtils.hasText(nested)) {
                return nested;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private Object firstPresent(Object first, Object fallback) {
        return first != null ? first : fallback;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        if (value != null) {
            try { return Integer.parseInt(String.valueOf(value)); }
            catch (NumberFormatException ignored) { }
        }
        return fallback;
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        if (value != null) {
            try { return Double.parseDouble(String.valueOf(value)); }
            catch (NumberFormatException ignored) { }
        }
        return fallback;
    }

    private RuntimeGraphSpecExecutionResult failure(String code, String answer, String nodeId, String nodeType) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (StringUtils.hasText(nodeId)) {
            metadata.put("nodeId", nodeId);
        }
        if (StringUtils.hasText(nodeType)) {
            metadata.put("nodeType", nodeType);
        }
        return new RuntimeGraphSpecExecutionResult(false, code, answer, nodeId, nodeType, List.of(), metadata);
    }

    private Map<String, Object> step(String name, String detail) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("name", name);
        step.put("detail", detail);
        return step;
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

    private record ClassifierClass(String id,
                                   String label,
                                   String description,
                                   List<String> keywords) {
    }

    private record ClassifierDecision(String route,
                                      double confidence,
                                      String matchedBy,
                                      String modelOutput) {
    }

    private record ModelClassifierOutput(String route, double confidence) {
    }
}
