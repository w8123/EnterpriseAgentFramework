package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeWriteClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.execution.context.WorkflowOutputAliasWriter;
import com.enterprise.ai.runtime.execution.context.WorkflowVariableNamespaces;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionEventSink;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.RuntimeBusinessIndexResolutionPort;
import com.enterprise.ai.runtime.execution.RuntimeBusinessIndexResolutionPort.HydrationBatch;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpClient;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionCodes;
import com.enterprise.ai.runtime.execution.RuntimeHttpApiToolExecutionPort;
import com.enterprise.ai.runtime.execution.RuntimeSubWorkflowPort;
import com.enterprise.ai.runtime.execution.event.RuntimeExecutionEventBridgeSink;
import com.enterprise.ai.runtime.execution.policy.RuntimeEvalExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Workflow GraphSpec 线性执行器。
 * <p>
 * 取消语义为<strong>节点边界协作式取消</strong>：
 * <ul>
 *   <li>节点前后检查 cancellation；取消后停止后续节点，返回 {@code RUNTIME_GRAPH_CANCELLED}</li>
 *   <li>LLM / TOOL / PAGE_ACTION / 模型分类与参数抽取在同步 Feign 调用前后检查取消；
 *       调用返回后若已取消，丢弃成功结果且不发 public delta</li>
 *   <li>OpenFeign 同步 HTTP（{@code modelServiceClient.chat}、{@code capabilityClient.invokeTool}、
 *       {@code controlClient.executePageBridge}）在现有技术栈下<strong>无法硬中断</strong>进行中的 socket；
 *       8s heartbeat SLA 只描述「发现连接断开并设置取消信号」的时限，不承诺节点内 HTTP 在 8s 内停止</li>
 *   <li>已发生的外部副作用不回滚；取消不得伪装为 TIMEOUT</li>
 * </ul>
 */
public final class RuntimeGraphSpecExecutionEngine {

    private static final int MAX_LINEAR_STEPS = 64;
    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final long MAX_RETRY_BACKOFF_MS = 10_000L;
    private static final Set<String> EXECUTABLE_NODE_TYPES = Set.of(
            "USER_INPUT",
            "INTENT_CLASSIFIER",
            "IF_ELSE",
            "ANSWER",
            "LLM",
            "TOOL",
            "PAGE_ACTION",
            "INTERACTION",
            "PARAMETER_EXTRACT",
            "VARIABLE_ASSIGN",
            "TEMPLATE",
            "VARIABLE_AGGREGATOR",
            "KNOWLEDGE_RETRIEVAL",
            "HTTP_REQUEST",
            "LOOP",
            "PARALLEL",
            "CODE",
            "LIST_OPERATOR",
            "SUB_WORKFLOW",
            "KNOWLEDGE_WRITE",
            "HUMAN_APPROVAL");
    static final int MAX_SUB_WORKFLOW_DEPTH = RuntimeIntegrationNodeHandlers.MAX_SUB_WORKFLOW_DEPTH;

    private final ObjectMapper objectMapper;
    private final RuntimeBusinessIndexResolutionPort businessIndexResolutionPort;
    private final RuntimeNodeValueResolver nodeValueResolver;
    private final RuntimeDeterministicNodeHandlers deterministicNodeHandlers;
    private final RuntimeIoNodeHandlers ioNodeHandlers;
    private final RuntimeModelNodeHandlers modelNodeHandlers;
    private final RuntimeModelUsageTap modelUsage;
    private final RuntimeActionNodeHandlers actionNodeHandlers;
    private final RuntimeLoopNodeHandler loopNodeHandler;
    private final RuntimeParallelNodeHandler parallelNodeHandler;
    private final RuntimeConcurrentNodeRunner concurrentRunner;
    private final RuntimeComputeNodeHandlers computeNodeHandlers;
    private final RuntimeIntegrationNodeHandlers integrationNodeHandlers;
    private final RuntimeNodeRetryPolicy retryPolicy;
    private final RuntimeNodeHandlerRegistry nodeHandlerRegistry;
    private final GraphSpecCompiler graphSpecCompiler;

    public RuntimeGraphSpecExecutionEngine(ObjectMapper objectMapper,
                                           RuntimeModelServiceClient modelServiceClient,
                                           RuntimeCapabilityCatalogClient capabilityClient,
                                           RuntimeControlCatalogClient controlClient) {
        this(objectMapper, modelServiceClient, capabilityClient, controlClient, null, null, null);
    }

    public RuntimeGraphSpecExecutionEngine(ObjectMapper objectMapper,
                                           RuntimeModelServiceClient modelServiceClient,
                                           RuntimeCapabilityCatalogClient capabilityClient,
                                           RuntimeControlCatalogClient controlClient,
                                           RuntimeKnowledgeRetrievalClient knowledgeClient,
                                           WorkflowHttpClient httpClient) {
        this(objectMapper, modelServiceClient, capabilityClient, controlClient,
                knowledgeClient, httpClient, null);
    }

    public RuntimeGraphSpecExecutionEngine(ObjectMapper objectMapper,
                                           RuntimeModelServiceClient modelServiceClient,
                                           RuntimeCapabilityCatalogClient capabilityClient,
                                           RuntimeControlCatalogClient controlClient,
                                           RuntimeKnowledgeRetrievalClient knowledgeClient,
                                           WorkflowHttpClient httpClient,
                                           RuntimeBusinessIndexResolutionPort businessIndexResolutionPort) {
        this(objectMapper, modelServiceClient, capabilityClient, controlClient, knowledgeClient,
                httpClient, businessIndexResolutionPort, null);
    }

    public RuntimeGraphSpecExecutionEngine(ObjectMapper objectMapper,
                                           RuntimeModelServiceClient modelServiceClient,
                                           RuntimeCapabilityCatalogClient capabilityClient,
                                           RuntimeControlCatalogClient controlClient,
                                           RuntimeKnowledgeRetrievalClient knowledgeClient,
                                           WorkflowHttpClient httpClient,
                                           RuntimeBusinessIndexResolutionPort businessIndexResolutionPort,
                                           RuntimeHttpApiToolExecutionPort httpApis) {
        this.objectMapper = objectMapper;
        this.businessIndexResolutionPort = businessIndexResolutionPort;
        this.nodeValueResolver = new RuntimeNodeValueResolver();
        this.deterministicNodeHandlers = new RuntimeDeterministicNodeHandlers(objectMapper, nodeValueResolver);
        this.ioNodeHandlers = new RuntimeIoNodeHandlers(objectMapper, knowledgeClient, httpClient, nodeValueResolver);
        this.modelUsage = new RuntimeModelUsageTap(modelServiceClient);
        this.modelNodeHandlers = new RuntimeModelNodeHandlers(objectMapper, modelUsage, nodeValueResolver);
        this.actionNodeHandlers = new RuntimeActionNodeHandlers(capabilityClient, controlClient, nodeValueResolver, httpApis);
        this.concurrentRunner = new RuntimeConcurrentNodeRunner(scopeCarriers(capabilityClient, httpApis));
        this.loopNodeHandler = new RuntimeLoopNodeHandler(
                nodeValueResolver, this::executeLoopBody, concurrentRunner, deterministicNodeHandlers);
        this.parallelNodeHandler = new RuntimeParallelNodeHandler(this::executeParallelBranch, concurrentRunner);
        this.computeNodeHandlers = new RuntimeComputeNodeHandlers(objectMapper, nodeValueResolver, deterministicNodeHandlers);
        this.retryPolicy = new RuntimeNodeRetryPolicy(ioNodeHandlers);
        this.integrationNodeHandlers = new RuntimeIntegrationNodeHandlers(nodeValueResolver, objectMapper,
                (childGraph, childRequest, childCancel, childIdentity, childEval) -> executeInternal(childGraph, childRequest,
                        null, RuntimeGraphSpecExecutionEventSink.NOOP, childCancel, childIdentity, childEval, false));
        this.graphSpecCompiler = new GraphSpecCompiler(objectMapper);
        this.nodeHandlerRegistry = buildNodeHandlerRegistry();
    }

    private RuntimeNodeHandlerRegistry buildNodeHandlerRegistry() {
        return RuntimeNodeHandlerRegistry.builder()
                .register("USER_INPUT", deterministicNodeHandlers::executeUserInput)
                .register("INTENT_CLASSIFIER", modelNodeHandlers::executeIntentClassifier)
                .register("IF_ELSE", deterministicNodeHandlers::executeCondition)
                .register("PARAMETER_EXTRACT", modelNodeHandlers::executeParameterExtract)
                .register("ANSWER", deterministicNodeHandlers::executeAnswer)
                .register("LLM", modelNodeHandlers::executeLlm)
                .register("TOOL", actionNodeHandlers::executeTool)
                .register("PAGE_ACTION", actionNodeHandlers::executePageAction)
                .register("INTERACTION", actionNodeHandlers::executeInteraction)
                .register("VARIABLE_ASSIGN", deterministicNodeHandlers::executeVariableAssign)
                .register("TEMPLATE", deterministicNodeHandlers::executeTemplate)
                .register("VARIABLE_AGGREGATOR", deterministicNodeHandlers::executeVariableAggregator)
                .register("KNOWLEDGE_RETRIEVAL", ioNodeHandlers::executeKnowledgeRetrieval)
                .register("HTTP_REQUEST", ioNodeHandlers::executeHttpRequest)
                .register("LOOP", loopNodeHandler::executeLoop)
                .register("PARALLEL", parallelNodeHandler::executeParallel)
                .register("CODE", computeNodeHandlers::executeCode)
                .register("LIST_OPERATOR", computeNodeHandlers::executeListOperator)
                .register("SUB_WORKFLOW", integrationNodeHandlers::executeSubWorkflow)
                .register("KNOWLEDGE_WRITE", integrationNodeHandlers::executeKnowledgeWrite)
                .register("HUMAN_APPROVAL", actionNodeHandlers::executeHumanApproval)
                .build(EXECUTABLE_NODE_TYPES);
    }

    private static List<RuntimeConcurrentNodeRunner.ScopeCarrier> scopeCarriers(
            RuntimeCapabilityCatalogClient capabilityClient,
            RuntimeHttpApiToolExecutionPort httpApis) {
        List<RuntimeConcurrentNodeRunner.ScopeCarrier> carriers = new ArrayList<>();
        if (capabilityClient != null) {
            carriers.add(new RuntimeConcurrentNodeRunner.ScopeCarrier() {
                @Override public Object capture() { return capabilityClient.captureThreadScope(); }
                @Override public void install(Object captured) { capabilityClient.installThreadScope(captured); }
                @Override public void clear() { capabilityClient.clearThreadScope(); }
            });
        }
        if (httpApis != null) {
            carriers.add(new RuntimeConcurrentNodeRunner.ScopeCarrier() {
                @Override public Object capture() { return httpApis.captureThreadScope(); }
                @Override public void install(Object captured) { httpApis.installThreadScope(captured); }
                @Override public void clear() { httpApis.clearThreadScope(); }
            });
        }
        return carriers;
    }

    /**
     * Immutable set of node types that currently have a real Runtime handler.
     * Product openness (Studio / publish / AI authoring) is decided by the node capability registry.
     */
    public static Set<String> handledNodeTypes() {
        return EXECUTABLE_NODE_TYPES;
    }

    public static final String TRUSTED_IDENTITY_CONTEXT_KEY = RuntimeTrustedExecutionContexts.IDENTITY_CONTEXT_KEY;
    private static final String TRUSTED_EVAL_CONTEXT_KEY = RuntimeTrustedExecutionContexts.EVAL_CONTEXT_KEY;

    public RuntimeGraphSpecExecutionResult execute(String graphSpecJson, Map<String, Object> request) {
        return execute(graphSpecJson, request, null,
                RuntimeGraphSpecExecutionEventSink.NOOP, RuntimeGraphSpecExecutionCancellation.none(),
                WorkflowExecutionIdentity.untrustedDebug(), RuntimeEvalExecutionContext.none());
    }

    public RuntimeGraphSpecExecutionResult execute(String graphSpecJson,
                                                   Map<String, Object> request,
                                                   WorkflowExecutionIdentity identity) {
        return execute(graphSpecJson, request, null,
                RuntimeGraphSpecExecutionEventSink.NOOP, RuntimeGraphSpecExecutionCancellation.none(),
                identity, RuntimeEvalExecutionContext.none());
    }

    public RuntimeGraphSpecExecutionResult execute(String graphSpecJson,
                                                   Map<String, Object> request,
                                                   RuntimeEvalExecutionContext evalContext) {
        return execute(graphSpecJson, request, null,
                RuntimeGraphSpecExecutionEventSink.NOOP, RuntimeGraphSpecExecutionCancellation.none(),
                WorkflowExecutionIdentity.untrustedDebug(), evalContext);
    }

    public RuntimeGraphSpecExecutionResult execute(String graphSpecJson,
                                                   Map<String, Object> request,
                                                   RuntimeGraphSpecExecutionEventSink sink,
                                                   RuntimeGraphSpecExecutionCancellation cancellation) {
        return execute(graphSpecJson, request, null, sink, cancellation,
                WorkflowExecutionIdentity.untrustedDebug(), RuntimeEvalExecutionContext.none());
    }

    public RuntimeGraphSpecExecutionResult execute(String graphSpecJson,
                                                   Map<String, Object> request,
                                                   RuntimeGraphSpecExecutionEventSink sink,
                                                   RuntimeGraphSpecExecutionCancellation cancellation,
                                                   WorkflowExecutionIdentity identity) {
        return execute(graphSpecJson, request, null, sink, cancellation, identity,
                RuntimeEvalExecutionContext.none());
    }

    public RuntimeGraphSpecExecutionResult execute(String graphSpecJson,
                                                   Map<String, Object> request,
                                                   RuntimeGraphSpecExecutionEventSink sink,
                                                   RuntimeGraphSpecExecutionCancellation cancellation,
                                                   WorkflowExecutionIdentity identity,
                                                   RuntimeEvalExecutionContext evalContext) {
        return execute(graphSpecJson, request, null, sink, cancellation, identity, evalContext);
    }

    public RuntimeGraphSpecExecutionResult executeFromNode(String graphSpecJson,
                                                           Map<String, Object> request,
                                                           String entryNodeId) {
        return execute(graphSpecJson, request, entryNodeId,
                RuntimeGraphSpecExecutionEventSink.NOOP, RuntimeGraphSpecExecutionCancellation.none(),
                WorkflowExecutionIdentity.untrustedDebug(), RuntimeEvalExecutionContext.none());
    }

    public RuntimeGraphSpecExecutionResult executeFromNode(String graphSpecJson,
                                                           Map<String, Object> request,
                                                           String entryNodeId,
                                                           RuntimeGraphSpecExecutionEventSink sink,
                                                           RuntimeGraphSpecExecutionCancellation cancellation) {
        return execute(graphSpecJson, request, entryNodeId, sink, cancellation,
                WorkflowExecutionIdentity.untrustedDebug(), RuntimeEvalExecutionContext.none());
    }

    public RuntimeGraphSpecExecutionResult executeFromNode(String graphSpecJson,
                                                           Map<String, Object> request,
                                                           String entryNodeId,
                                                           RuntimeGraphSpecExecutionEventSink sink,
                                                           RuntimeGraphSpecExecutionCancellation cancellation,
                                                           RuntimeEvalExecutionContext evalContext) {
        return execute(graphSpecJson, request, entryNodeId, sink, cancellation,
                WorkflowExecutionIdentity.untrustedDebug(), evalContext);
    }

    public RuntimeGraphSpecExecutionResult executeFromNode(String graphSpecJson,
                                                           Map<String, Object> request,
                                                           String entryNodeId,
                                                           RuntimeGraphSpecExecutionEventSink sink,
                                                           RuntimeGraphSpecExecutionCancellation cancellation,
                                                           WorkflowExecutionIdentity identity) {
        return execute(graphSpecJson, request, entryNodeId, sink, cancellation, identity,
                RuntimeEvalExecutionContext.none());
    }

    public RuntimeGraphSpecExecutionResult executeFromNode(String graphSpecJson,
                                                           Map<String, Object> request,
                                                           String entryNodeId,
                                                           RuntimeGraphSpecExecutionEventSink sink,
                                                           RuntimeGraphSpecExecutionCancellation cancellation,
                                                           WorkflowExecutionIdentity identity,
                                                           RuntimeEvalExecutionContext evalContext) {
        return execute(graphSpecJson, request, entryNodeId, sink, cancellation, identity, evalContext);
    }

    /**
     * Studio "test this node": runs only {@code nodeId} with the given state and stops, instead of
     * continuing to every downstream node.
     */
    public RuntimeGraphSpecExecutionResult executeSingleNode(String graphSpecJson,
                                                             Map<String, Object> request,
                                                             String nodeId,
                                                             WorkflowExecutionIdentity identity,
                                                             RuntimeEvalExecutionContext evalContext) {
        return execute(graphSpecJson, request, nodeId, RuntimeGraphSpecExecutionEventSink.NOOP,
                RuntimeGraphSpecExecutionCancellation.none(), identity, evalContext, true);
    }

    private RuntimeGraphSpecExecutionResult execute(String graphSpecJson,
                                                    Map<String, Object> request,
                                                    String entryOverride,
                                                    RuntimeGraphSpecExecutionEventSink sink,
                                                    RuntimeGraphSpecExecutionCancellation cancellation,
                                                    WorkflowExecutionIdentity identity,
                                                    RuntimeEvalExecutionContext evalContext) {
        return execute(graphSpecJson, request, entryOverride, sink, cancellation, identity, evalContext, false);
    }

    private RuntimeGraphSpecExecutionResult execute(String graphSpecJson,
                                                    Map<String, Object> request,
                                                    String entryOverride,
                                                    RuntimeGraphSpecExecutionEventSink sink,
                                                    RuntimeGraphSpecExecutionCancellation cancellation,
                                                    WorkflowExecutionIdentity identity,
                                                    RuntimeEvalExecutionContext evalContext,
                                                    boolean singleNodeOnly) {
        RuntimeExecutionEventBridgeSink eventBridge = new RuntimeExecutionEventBridgeSink(
                sink, request == null ? Map.of() : request);
        eventBridge.onRunStarted();
        try {
            RuntimeGraphSpecExecutionResult result = executeInternal(
                    graphSpecJson,
                    request,
                    entryOverride,
                    eventBridge,
                    cancellation,
                    identity,
                    evalContext,
                    singleNodeOnly);
            eventBridge.onRunTerminal(result);
            return result;
        } catch (RuntimeException ex) {
            eventBridge.onRunException(ex);
            throw ex;
        }
    }

    private RuntimeGraphSpecExecutionResult executeInternal(String graphSpecJson,
                                                            Map<String, Object> request,
                                                            String entryOverride,
                                                            RuntimeGraphSpecExecutionEventSink sink,
                                                            RuntimeGraphSpecExecutionCancellation cancellation,
                                                            WorkflowExecutionIdentity identity,
                                                            RuntimeEvalExecutionContext evalContext,
                                                            boolean singleNodeOnly) {
        RuntimeGraphSpecExecutionEventSink eventSink = sink == null
                ? RuntimeGraphSpecExecutionEventSink.NOOP : sink;
        RuntimeGraphSpecExecutionCancellation cancel = cancellation == null
                ? RuntimeGraphSpecExecutionCancellation.none() : cancellation;
        ExecutableGraph executableGraph;
        try {
            executableGraph = graphSpecCompiler.compile(graphSpecJson, entryOverride);
        } catch (GraphSpecCompilationException ex) {
            return failure(ex.code(), ex.getMessage(), ex.nodeId(), ex.nodeType());
        }
        GraphSpec graph = executableGraph.graph();
        Map<String, GraphSpec.Node> nodesById = executableGraph.nodesById();
        Set<String> exitNodeIds = executableGraph.exitNodeIds();
        String entry = executableGraph.executionEntryNodeId();

        Map<String, Object> context = RuntimeInitialContext.initialContext(request == null ? Map.of() : request, nodeValueResolver);
        context.put(RuntimeInvocationScope.KEY, RuntimeInvocationScope.from(context));
        // Trusted identity is never taken from business maps / model args.
        context.remove(TRUSTED_IDENTITY_CONTEXT_KEY);
        context.put(TRUSTED_IDENTITY_CONTEXT_KEY,
                identity == null ? WorkflowExecutionIdentity.untrustedDebug() : identity);
        // Eval policy is also server-owned. Public maps with the same key are discarded.
        context.remove(TRUSTED_EVAL_CONTEXT_KEY);
        context.put(TRUSTED_EVAL_CONTEXT_KEY,
                evalContext == null ? RuntimeEvalExecutionContext.none() : evalContext);
        RuntimeInitialContext.applyWorkflowEnvAndSystemValues(context, graph, identity);
        if (eventSink instanceof RuntimeExecutionEventBridgeSink bridge) {
            bridge.bindExecutionContext(context);
        }
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
            eventSink.onNodeInputSnapshot(node.getId(), context);
            eventSink.onNodeStarted(node.getId(), nodeType, nodeName, safeNodePayload(node, nodeType, null));
            RuntimeGraphSpecExecutionResult nodeResult = executeNodeWithPolicies(
                    node, context, graph, eventSink, cancel, nodesById);
            long nodeEndedAt = System.currentTimeMillis();
            long elapsedMs = Math.max(0L, nodeEndedAt - nodeStartedAt);
            steps.addAll(nodeResult.steps());
            nodeTraces.add(RuntimeNodeObservability.buildInternalNodeTrace(node, nodeType, nodeResult, nodeStartedAt, nodeEndedAt, elapsedMs));
            appendParallelBranchTraces(nodeTraces, nodeResult);
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
                        RuntimeNodeObservability.putAttemptObservability(waitingPayload, nodeResult);
                    }
                    eventSink.onNodeWaiting(node.getId(), nodeType, nodeName, waitingPayload);
                } else {
                    Map<String, Object> failedPayload = safeNodePayload(node, nodeType, elapsedMs);
                    failedPayload.put("status", "FAILED");
                    failedPayload.put("code", nodeResult.code());
                    failedPayload.put("summary", safeSummary(nodeResult.answer()));
                    RuntimeNodeObservability.putAttemptObservability(failedPayload, nodeResult);
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
            RuntimeNodeObservability.putAttemptObservability(completedPayload, nodeResult);
            if (nodeResult.uiRequest() != null) {
                completedPayload.put("uiRequest", nodeResult.uiRequest());
            }
            String forcedNext = forcedNextNodeId(nodeResult);
            String nextPreview = null;
            NextNodeResolution next = null;
            boolean unsafeKnowledgeNoEvidenceTarget = false;
            if (StringUtils.hasText(forcedNext)) {
                next = NextNodeResolution.node(forcedNext);
                nextPreview = forcedNext;
            } else if (!"ANSWER".equals(nodeResult.nodeType())) {
                next = resolveNextNode(graph, node.getId(), nodeResult);
                // Strict business routes must never fall through an unconditional edge.
                boolean strictInteractionRoute = ("INTERACTION".equals(nodeResult.nodeType())
                        || "HUMAN_APPROVAL".equals(nodeResult.nodeType()))
                        && isStrictInteractionRoute(resultRoute(nodeResult));
                if ((strictInteractionRoute || isStrictKnowledgeEvidenceRoute(nodeResult))
                        && !hasExactRouteEdge(graph, node.getId(), resultRoute(nodeResult))) {
                    next = NextNodeResolution.unmatched();
                }
                if (isStrictKnowledgeNoEvidenceRoute(nodeResult) && next != null && next.matched()) {
                    GraphSpec.Node noEvidenceTarget = nodesById.get(next.nodeId());
                    if (noEvidenceTarget == null || !"ANSWER".equals(noEvidenceTarget.getType())) {
                        unsafeKnowledgeNoEvidenceTarget = true;
                        next = NextNodeResolution.unmatched();
                    }
                }
                nextPreview = next.nodeId();
            }
            if (StringUtils.hasText(nextPreview)) {
                completedPayload.put("nextNodeId", nextPreview);
            }
            eventSink.onNodeCompleted(node.getId(), nodeType, nodeName, completedPayload);
            lastResult = withSteps(nodeResult, steps, nodeTraces);
            rememberNodeOutput(context, node, nodeResult);
            if (singleNodeOnly) {
                return withLiveContext(lastResult, context);
            }
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
            if (!StringUtils.hasText(forcedNext) && unsafeKnowledgeNoEvidenceTarget) {
                return withLiveContext(withSteps(failure(
                        "RUNTIME_KNOWLEDGE_NO_EVIDENCE_TARGET_UNSAFE",
                        "REQUIRED KNOWLEDGE_RETRIEVAL route:no_evidence must target an ANSWER node",
                        node.getId(),
                        nodeResult.nodeType()), steps, nodeTraces), context);
            }
            if (!StringUtils.hasText(forcedNext) && StringUtils.hasText(route) && next != null && !next.matched()) {
                if (("INTERACTION".equals(nodeResult.nodeType()) || "HUMAN_APPROVAL".equals(nodeResult.nodeType()))
                        && ("reject".equalsIgnoreCase(route) || "rejected".equalsIgnoreCase(route)
                        || "cancel".equalsIgnoreCase(route))) {
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
        if (context == null || context.isEmpty()) {
            return result.withResumeCheckpoint(Map.of());
        }
        Map<String, Object> checkpoint = new LinkedHashMap<>(context);
        checkpoint.put(RuntimeInvocationScope.KEY, RuntimeInvocationScope.from(context).snapshot());
        // Trusted identity is reconstructed from the authenticated resume path, never serialized.
        checkpoint.remove(TRUSTED_IDENTITY_CONTEXT_KEY);
        // Eval runs are intentionally not resumable through an untrusted serialized checkpoint.
        checkpoint.remove(TRUSTED_EVAL_CONTEXT_KEY);
        // Correlation markers are only valid in this live Supervisor invocation.
        // A serialized Workflow checkpoint must never restore one from input.
        checkpoint.remove(com.enterprise.ai.runtime.execution.context.TrustedSupervisorTrace.CONTEXT_KEY);
        return result.withResumeCheckpoint(checkpoint);
    }

    /** Convert a verified persisted checkpoint to the server-owned invocation scope. */
    public static Map<String, Object> restoreCheckpointScope(Map<String, Object> state) {
        Map<String, Object> restored = new LinkedHashMap<>(state == null ? Map.of() : state);
        restored.put(RuntimeInvocationScope.KEY, RuntimeInvocationScope.restore(restored.get(RuntimeInvocationScope.KEY)));
        return restored;
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
                if (!retryPolicy.allowsRetry(node, nodeType, last)) {
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
            last = executeNodeWithTimeout(node, context, graph, eventSink, cancel);
            last = withAttemptMetadata(last, attempt, maxAttempts, attempt > 1 ? "retry" : "initial");
            if (last.success()) {
                last = resolveBusinessIndexReferences(last, context);
            }
            if (last.success()
                    || last.isWaitingUser()
                    || WorkflowInteractionCodes.WAITING.equals(last.code())
                    || "RUNTIME_GRAPH_CANCELLED".equals(last.code())) {
                return last;
            }
            if (attempt < maxAttempts && !retryPolicy.allowsRetry(node, nodeType, last)) {
                break;
            }
        }
        return applyErrorPolicy(node, nodeType, last, graph, nodesById, context);
    }

    private RuntimeGraphSpecExecutionResult resolveBusinessIndexReferences(
            RuntimeGraphSpecExecutionResult result,
            Map<String, Object> context) {
        if (businessIndexResolutionPort == null || result == null || !result.success()) {
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
        HydrationBatch batch = businessIndexResolutionPort.hydrate(
                structured, RuntimeTrustedExecutionContexts.identity(context), context);
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
        metadata.put("businessIndexResolution", Map.of(
                "referenceCount", batch.referenceCount(),
                "resolvedCount", batch.resolvedCount(),
                "blockedCount", batch.blockedCount(),
                "versionChangedCount", batch.versionChangedCount()));
        return new RuntimeGraphSpecExecutionResult(
                result.success(), result.code(), safeAnswer, result.nodeId(), result.nodeType(),
                result.steps(), metadata, result.resumeCheckpoint());
    }

    private RuntimeGraphSpecExecutionResult applyErrorPolicy(GraphSpec.Node node,
                                                             String nodeType,
                                                             RuntimeGraphSpecExecutionResult failed,
                                                             GraphSpec graph,
                                                             Map<String, GraphSpec.Node> nodesById,
                                                             Map<String, Object> context) {
        GraphSpec.ErrorPolicy policy = node.getErrorPolicy();
        String strategy = policy == null
                ? "TERMINATE"
                : firstText(text(policy.getStrategy()), "TERMINATE").toUpperCase(Locale.ROOT);
        RuntimeGraphSpecExecutionResult base = failed == null
                ? failure("RUNTIME_GRAPH_NODE_FAILED", "Node execution failed", node.getId(), nodeType)
                : failed;
        if (base.metadata() != null && Boolean.TRUE.equals(base.metadata().get("nonRecoverableWrite"))) {
            Map<String, Object> metadata = new LinkedHashMap<>(base.metadata());
            metadata.put("errorPolicyDecision", "TERMINATE_WRITE");
            return base.withMetadata(metadata);
        }
        if ("TERMINATE".equals(strategy)) {
            RuntimeGraphSpecExecutionResult routed = routeFailureAlongEdge(node, nodeType, base, graph, context);
            if (routed != null) {
                return routed;
            }
        }
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

    /**
     * A failed node follows an explicit outgoing "error" edge instead of ending the run. A missing required
     * parameter counts as "nothing found" and prefers an "empty" edge. The failure stays visible through
     * {@code recoveredFromCode} and the {@code lastError} variable so a downstream ANSWER can explain it.
     */
    private RuntimeGraphSpecExecutionResult routeFailureAlongEdge(GraphSpec.Node node,
                                                                  String nodeType,
                                                                  RuntimeGraphSpecExecutionResult base,
                                                                  GraphSpec graph,
                                                                  Map<String, Object> context) {
        if (graph == null || graph.getEdges() == null) {
            return null;
        }
        String target = null;
        String decision = null;
        if ("RUNTIME_GRAPH_PARAMETER_REQUIRED".equals(base.code())) {
            target = conditionEdgeTarget(graph, node.getId(), EdgeConditions.EMPTY);
            decision = "EMPTY_EDGE";
        }
        if (!StringUtils.hasText(target)) {
            target = conditionEdgeTarget(graph, node.getId(), EdgeConditions.ERROR);
            decision = "ERROR_EDGE";
        }
        if (!StringUtils.hasText(target)) {
            return null;
        }
        Map<String, Object> lastError = new LinkedHashMap<>();
        lastError.put("nodeId", node.getId());
        lastError.put("nodeName", firstText(node.getName(), node.getId()));
        lastError.put("code", firstText(base.code(), "RUNTIME_GRAPH_NODE_FAILED"));
        // Only the fixed Chinese reason is exposed; raw upstream messages stay in the trace.
        lastError.put("message", firstText(
                RuntimeNodeFailureReasons.safeReason(base.code(), base.answer()), "节点执行失败"));
        context.put("lastError", lastError);
        Map<String, Object> metadata = new LinkedHashMap<>(base.metadata() == null ? Map.of() : base.metadata());
        metadata.put("errorPolicyDecision", decision);
        metadata.put("forceNextNodeId", target);
        metadata.put("recoveredFromCode", base.code());
        metadata.put("structuredOutput", Map.copyOf(lastError));
        // A failed node carries no step of its own; record one so traces and trials show the routed node.
        List<Map<String, Object>> steps = base.steps() == null || base.steps().isEmpty()
                ? new ArrayList<>(List.of(step("execute-node", node.getId())))
                : new ArrayList<>(base.steps());
        Map<String, Object> routedStep = new LinkedHashMap<>(steps.get(steps.size() - 1));
        routedStep.put("route", "EMPTY_EDGE".equals(decision) ? "empty" : "error");
        steps.set(steps.size() - 1, routedStep);
        return new RuntimeGraphSpecExecutionResult(
                true,
                "EMPTY_EDGE".equals(decision) ? "RUNTIME_GRAPH_EMPTY_ROUTED" : "RUNTIME_GRAPH_ERROR_ROUTED",
                firstText(base.answer(), ""),
                node.getId(),
                nodeType,
                steps,
                metadata,
                base.resumeCheckpoint());
    }

    private String conditionEdgeTarget(GraphSpec graph, String nodeId, Set<String> conditions) {
        return graph.getEdges().stream()
                .filter(edge -> edge != null && nodeId.equals(text(edge.getFrom())))
                .filter(edge -> EdgeConditions.matches(edge.getCondition(), conditions))
                .sorted(Comparator.comparing(edge -> edge.getPriority() == null ? Integer.MAX_VALUE : edge.getPriority()))
                .map(GraphSpec.Edge::getTo)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
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
        RuntimeNodeExecutionContext execution = new RuntimeNodeExecutionContext(
                graph, context, eventSink, cancel);
        return nodeHandlerRegistry.find(nodeType)
                .map(handler -> handler.execute(node, execution))
                .orElseGet(() -> failure("RUNTIME_GRAPH_NODE_UNSUPPORTED",
                        "Runtime GraphSpec node type is not executable yet: " + nodeType,
                        node.getId(),
                        nodeType));
    }

    /** Node types whose attempt may run under a timeout; pausing and page nodes keep their own lifecycle. */
    private static final Set<String> TIMEOUT_EXCLUDED_TYPES = Set.of(
            "USER_INPUT", "INTERACTION", "HUMAN_APPROVAL", "PAGE_ACTION", "ANSWER");
    /** Business calls whose outcome is unknown after a timeout: never continue, retry or fall back. */
    private static final Set<String> TIMEOUT_UNKNOWN_OUTCOME_TYPES = Set.of("TOOL", "HTTP_REQUEST", "MCP_CALL", "KNOWLEDGE_WRITE", "SUB_WORKFLOW");
    static final long MIN_NODE_TIMEOUT_MS = 1_000L;
    static final long MAX_NODE_TIMEOUT_MS = 600_000L;

    static Long nodeTimeoutMs(GraphSpec.Node node) {
        GraphSpec.ErrorPolicy policy = node == null ? null : node.getErrorPolicy();
        Long timeout = policy == null ? null : policy.getTimeoutMs();
        if (timeout == null || timeout <= 0) return null;
        return Math.max(MIN_NODE_TIMEOUT_MS, Math.min(MAX_NODE_TIMEOUT_MS, timeout));
    }

    private RuntimeGraphSpecExecutionResult executeNodeWithTimeout(GraphSpec.Node node,
                                                                   Map<String, Object> context,
                                                                   GraphSpec graph,
                                                                   RuntimeGraphSpecExecutionEventSink eventSink,
                                                                   RuntimeGraphSpecExecutionCancellation cancel) {
        String nodeType = AgentGraphNodeType.normalize(node.getType());
        Long timeoutMs = nodeTimeoutMs(node);
        if (timeoutMs == null || TIMEOUT_EXCLUDED_TYPES.contains(nodeType)) {
            return executeNode(node, context, graph, eventSink, cancel);
        }
        // The attempt works on its own copy; an abandoned (timed-out) worker can never touch live variables.
        Map<String, Object> attemptContext = RuntimeContextCopies.copy(context);
        RuntimeConcurrentNodeRunner.GuardedEventSink guarded =
                new RuntimeConcurrentNodeRunner.GuardedEventSink(eventSink, Map.of(), new Object());
        RuntimeGraphSpecExecutionResult result = concurrentRunner.runWithTimeout(
                () -> executeNode(node, attemptContext, graph, guarded, cancel),
                timeoutMs,
                () -> null);
        guarded.close();
        if (result == null) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("nodeId", node.getId());
            metadata.put("nodeType", nodeType);
            metadata.put("timeoutMs", timeoutMs);
            metadata.put("retryableFailure", false);
            metadata.put("failureCategory", "TIMEOUT");
            if (TIMEOUT_UNKNOWN_OUTCOME_TYPES.contains(nodeType)
                    || RuntimeNodeTimeoutSafety.containerMayWrite(node, graph)) {
                // The call may still complete remotely; treat it like an unknown write and stop here.
                metadata.put("nonRecoverableWrite", true);
                metadata.put("dispatchStage", "UNKNOWN");
            }
            return new RuntimeGraphSpecExecutionResult(false, "RUNTIME_GRAPH_NODE_TIMEOUT",
                    "Node did not finish within " + timeoutMs + " ms", node.getId(), nodeType, List.of(), metadata);
        }
        context.clear();
        context.putAll(attemptContext);
        return result;
    }

    private void appendParallelBranchTraces(List<Map<String, Object>> nodeTraces,
                                            RuntimeGraphSpecExecutionResult nodeResult) {
        if (nodeResult == null || nodeResult.metadata() == null
                || !(nodeResult.metadata().get("parallelBranchTraces") instanceof List<?> traces)) {
            return;
        }
        for (Object trace : traces) {
            if (trace instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((key, value) -> copy.put(String.valueOf(key), value));
                nodeTraces.add(copy);
            }
        }
    }

    /** Optional: without it SUB_WORKFLOW fails closed. Set once while wiring the executor bean. */
    public void setSubWorkflowPort(RuntimeSubWorkflowPort subWorkflowPort) {
        integrationNodeHandlers.setSubWorkflowPort(subWorkflowPort);
    }

    public void setModelUsageRecorder(com.enterprise.ai.runtime.trace.usage.RuntimeModelUsageRecorder recorder) {
        modelUsage.setRecorder(recorder);
    }

    public void setKnowledgeWriteClient(RuntimeKnowledgeWriteClient knowledgeWriteClient) {
        integrationNodeHandlers.setKnowledgeWriteClient(knowledgeWriteClient);
    }

    /**
     * Drives one PARALLEL branch on its own variables until it reaches the join node. Every branch node emits the
     * normal node events (tagged with the branch) so Debug and RunOps show what ran.
     */
    private RuntimeGraphSpecExecutionResult executeParallelBranch(GraphSpec graph,
                                                                  Map<String, GraphSpec.Node> nodesById,
                                                                  Set<String> branchNodeIds,
                                                                  String branchEntry,
                                                                  String joinNodeId,
                                                                  Map<String, Object> context,
                                                                  RuntimeGraphSpecExecutionEventSink eventSink,
                                                                  RuntimeGraphSpecExecutionCancellation cancel) {
        List<Map<String, Object>> steps = new ArrayList<>();
        List<Map<String, Object>> traces = new ArrayList<>();
        String current = branchEntry;
        RuntimeGraphSpecExecutionResult last = null;
        for (int guard = 0; guard < MAX_LINEAR_STEPS; guard++) {
            if (!StringUtils.hasText(current) || current.equals(joinNodeId)) {
                RuntimeGraphSpecExecutionResult done = last == null
                        ? new RuntimeGraphSpecExecutionResult(true, "RUNTIME_GRAPH_EXECUTED", "",
                                null, "PARALLEL", List.of(), new LinkedHashMap<>())
                        : last;
                return withSteps(done, steps, traces);
            }
            if (cancel.isCancelled()) {
                return withSteps(cancelled(current, null), steps, traces);
            }
            if (!branchNodeIds.contains(current)) {
                return withSteps(failure("RUNTIME_GRAPH_PARALLEL_BRANCH_ESCAPE",
                        "PARALLEL branch left its nodes at " + current, current, null), steps, traces);
            }
            GraphSpec.Node branchNode = nodesById.get(current);
            if (branchNode == null) {
                return withSteps(failure("RUNTIME_GRAPH_NEXT_NODE_INVALID",
                        "PARALLEL branch node missing: " + current, current, null), steps, traces);
            }
            String nodeType = AgentGraphNodeType.normalize(branchNode.getType());
            String nodeName = firstText(branchNode.getName(), branchNode.getId());
            long startedAt = System.currentTimeMillis();
            eventSink.onNodeStarted(branchNode.getId(), nodeType, nodeName, safeNodePayload(branchNode, nodeType, null));
            RuntimeGraphSpecExecutionResult nodeResult = executeNodeWithPolicies(
                    branchNode, context, graph, eventSink, cancel, nodesById);
            long endedAt = System.currentTimeMillis();
            long elapsed = Math.max(0L, endedAt - startedAt);
            steps.addAll(nodeResult.steps());
            Map<String, Object> trace = RuntimeNodeObservability.buildInternalNodeTrace(branchNode, nodeType, nodeResult, startedAt, endedAt, elapsed);
            trace.put("parallelBranch", true);
            traces.add(trace);
            if (cancel.isCancelled() || "RUNTIME_GRAPH_CANCELLED".equals(nodeResult.code())) {
                return withSteps(cancelled(branchNode.getId(), nodeType), steps, traces);
            }
            if (!nodeResult.success()) {
                Map<String, Object> failedPayload = safeNodePayload(branchNode, nodeType, elapsed);
                failedPayload.put("status", "FAILED");
                failedPayload.put("code", nodeResult.code());
                failedPayload.put("summary", safeSummary(nodeResult.answer()));
                RuntimeNodeObservability.putAttemptObservability(failedPayload, nodeResult);
                eventSink.onNodeFailed(branchNode.getId(), nodeType, nodeName, failedPayload);
                return withSteps(nodeResult, steps, traces);
            }
            rememberNodeOutput(context, branchNode, nodeResult);
            String forced = forcedNextNodeId(nodeResult);
            NextNodeResolution next = StringUtils.hasText(forced)
                    ? NextNodeResolution.node(forced)
                    : resolveNextNode(graph, branchNode.getId(), nodeResult);
            Map<String, Object> completedPayload = safeNodePayload(branchNode, nodeType, elapsed);
            completedPayload.put("status", "SUCCESS");
            completedPayload.put("summary", safeSummary(nodeResult.answer()));
            RuntimeNodeObservability.putAttemptObservability(completedPayload, nodeResult);
            if (next != null && StringUtils.hasText(next.nodeId())) {
                completedPayload.put("nextNodeId", next.nodeId());
            }
            eventSink.onNodeCompleted(branchNode.getId(), nodeType, nodeName, completedPayload);
            last = nodeResult;
            if (next == null || !StringUtils.hasText(next.nodeId())) {
                return withSteps(failure("RUNTIME_GRAPH_PARALLEL_BRANCH_DEAD_END",
                        "PARALLEL branch ended before reaching the join: " + branchNode.getId(),
                        branchNode.getId(), nodeType), steps, traces);
            }
            current = next.nodeId();
        }
        return withSteps(failure("RUNTIME_GRAPH_STEP_LIMIT_EXCEEDED",
                "PARALLEL branch exceeded step limit: " + MAX_LINEAR_STEPS, current, null), steps, traces);
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

    private RuntimeGraphSpecExecutionResult cancelled(String nodeId, String nodeType) {
        return failure("RUNTIME_GRAPH_CANCELLED", "Workflow execution cancelled", nodeId, nodeType);
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

    private NextNodeResolution resolveNextNode(GraphSpec graph,
                                               String nodeId,
                                               RuntimeGraphSpecExecutionResult nodeResult) {
        if (graph.getEdges() == null || graph.getEdges().isEmpty()) {
            return NextNodeResolution.unmatched();
        }
        String route = resultRoute(nodeResult);
        boolean outputEmpty = RuntimeOutputEmptiness.isEmpty(nodeResult);
        String target = graph.getEdges().stream()
                .filter(edge -> edge != null && nodeId.equals(text(edge.getFrom())))
                .filter(edge -> edgeMatchRank(edge, route, outputEmpty) < Integer.MAX_VALUE)
                .sorted(Comparator
                        .comparingInt((GraphSpec.Edge edge) -> edgeMatchRank(edge, route, outputEmpty))
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
                || "approved".equals(normalized)
                || "rejected".equals(normalized)
                || "cancel".equals(normalized)
                || "approve".equals(normalized)
                || "deny".equals(normalized);
    }

    private static boolean isStrictKnowledgeEvidenceRoute(RuntimeGraphSpecExecutionResult result) {
        Object route = result == null || result.metadata() == null
                ? null
                : result.metadata().get("route");
        return result != null
                && "KNOWLEDGE_RETRIEVAL".equals(result.nodeType())
                && result.metadata() != null
                && "REQUIRED".equalsIgnoreCase(String.valueOf(result.metadata().get("evidencePolicy")))
                && route instanceof String routeText
                && StringUtils.hasText(routeText);
    }

    private static boolean isStrictKnowledgeNoEvidenceRoute(RuntimeGraphSpecExecutionResult result) {
        return isStrictKnowledgeEvidenceRoute(result)
                && "no_evidence".equalsIgnoreCase(resultRouteValue(result));
    }

    private static String resultRouteValue(RuntimeGraphSpecExecutionResult result) {
        if (result == null || result.metadata() == null) {
            return null;
        }
        Object route = result.metadata().get("route");
        return route instanceof String routeText ? routeText : null;
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

    private int edgeMatchRank(GraphSpec.Edge edge, String route, boolean outputEmpty) {
        String condition = text(edge.getCondition());
        boolean unconditional = !StringUtils.hasText(condition)
                || "always".equalsIgnoreCase(condition)
                || "success".equalsIgnoreCase(condition);
        if (!StringUtils.hasText(route) || !route.equalsIgnoreCase(condition)) {
            if (EdgeConditions.matches(condition, EdgeConditions.EMPTY)) {
                return outputEmpty ? 0 : Integer.MAX_VALUE;
            }
            if (EdgeConditions.matches(condition, EdgeConditions.NOT_EMPTY)) {
                return outputEmpty ? Integer.MAX_VALUE : 0;
            }
        }
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
        // Unconditional edges always rank after a matching route or emptiness condition.
        return unconditional ? 1 : Integer.MAX_VALUE;
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

    private boolean isBusinessTerminalResult(RuntimeGraphSpecExecutionResult result) {
        return RuntimeNodeObservability.isBusinessTerminalResult(result);
    }

    private void putIfPresent(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
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

}
