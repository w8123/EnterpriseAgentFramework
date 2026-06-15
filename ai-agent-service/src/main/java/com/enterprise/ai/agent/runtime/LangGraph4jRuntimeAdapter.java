package com.enterprise.ai.agent.runtime;

import com.enterprise.ai.agent.client.ModelServiceClient;
import com.enterprise.ai.agent.client.SkillsServiceClient;
import com.enterprise.ai.agent.credential.WorkflowCredentialRuntime;
import com.enterprise.ai.agent.credential.WorkflowCredentialService;
import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.model.interactive.UiFieldPayload;
import com.enterprise.ai.agent.model.interactive.UiRequestPayload;
import com.enterprise.ai.agent.skill.interactive.HumanApprovalResumeService;
import com.enterprise.ai.agent.skill.interactive.InteractionSuspendedException;
import com.enterprise.ai.agent.skill.interactive.SkillInteractionEntity;
import com.enterprise.ai.agent.skill.interactive.SkillInteractionMapper;
import com.enterprise.ai.agent.skill.interactive.SkillInteractionStatus;
import com.enterprise.ai.agent.skill.ToolExecutionContextHolder;
import com.enterprise.ai.agent.tool.log.ToolCallLogService;
import com.enterprise.ai.agent.tool.log.ToolExecutionContext;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionService;
import com.enterprise.ai.agent.tools.dynamic.DynamicHttpAiTool;
import com.enterprise.ai.agent.trace.AgentTraceSpanService;
import com.enterprise.ai.agent.runtime.slot.SlotFillingService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

@Slf4j
@Component
@RequiredArgsConstructor
public class LangGraph4jRuntimeAdapter implements AgentRuntimeAdapter {

    static final String NODE_LLM = "llm";
    static final String NODE_USER_INPUT = "user_input";
    static final String NODE_TOOL = "tool";
    static final String NODE_CAPABILITY = "capability";
    static final String NODE_IF_ELSE = "if_else";
    static final String NODE_VARIABLE_ASSIGN = "variable_assign";
    static final String NODE_TEMPLATE = "template";
    static final String NODE_ANSWER = "answer";
    static final String NODE_CODE = "code";
    static final String NODE_INTENT_CLASSIFIER = "intent_classifier";
    static final String NODE_VARIABLE_AGGREGATOR = "variable_aggregator";
    static final String NODE_HUMAN_APPROVAL = "human_approval";
    static final String NODE_LOOP = "loop";
    static final String NODE_KNOWLEDGE_WRITE = "knowledge_write";
    static final String NODE_DOCUMENT_EXTRACT = "document_extract";
    static final String NODE_MCP_CALL = "mcp_call";
    static final String NODE_PARAMETER_EXTRACT = "parameter_extract";
    static final String NODE_HTTP_REQUEST = "http_request";
    static final String NODE_KNOWLEDGE_RETRIEVAL = "knowledge_retrieval";
    static final String INPUT = "input";
    static final String SYSTEM_PROMPT = "systemPrompt";
    static final String MODEL_INSTANCE_ID = "modelInstanceId";
    static final String ANSWER = "answer";
    static final String MODEL = "model";
    static final String PROVIDER = "provider";
    static final String TOKEN_USAGE = "tokenUsage";
    static final String FINISH_REASON = "finishReason";
    static final String LAST_OUTPUT = "lastOutput";
    static final String LAST_SUCCESS = "lastSuccess";
    static final String LAST_ERROR = "lastError";
    static final String LAST_ROUTE = "lastRoute";
    static final String REQUEST_PARAMS = "__requestParams";
    static final String CONFIG_ARGS = "args";
    static final String CONFIG_TOOL_ARGS = "toolArgs";
    static final String CONFIG_TOOL_NAME = "toolName";
    static final String CONFIG_QUALIFIED_NAME = "qualifiedName";
    static final String CONFIG_INPUT_MAPPING = "inputMapping";
    static final String CONFIG_MAX_REQUEST_TIME_MS = "maxRequestTimeMs";
    static final String CONFIG_OUTPUT_ALIAS = "outputAlias";
    static final String CONFIG_ASSIGNMENTS = "assignments";
    static final String CONFIG_TEMPLATE = "template";
    static final String CONFIG_WRITE_TO_ANSWER = "writeToAnswer";
    static final String CONFIG_PARAMETERS = "parameters";
    static final String CONFIG_OUTPUTS = "outputs";
    static final String CONFIG_ITEMS = "items";
    static final String CONFIG_AGGREGATE_MODE = "aggregateMode";
    static final String CONFIG_INPUT_EXPRESSION = "inputExpression";
    static final String CONFIG_CLASSES = "classes";
    static final String CONFIG_KEYWORDS = "keywords";
    static final String CONFIG_APPROVERS = "approvers";
    static final String CONFIG_TIMEOUT_SECONDS = "timeoutSeconds";
    static final String CONFIG_LOOP_KEY = "loopKey";
    static final String CONFIG_MAX_ITERATIONS = "maxIterations";
    static final String CONFIG_ITEM_EXPRESSION = "itemExpression";
    static final String CONFIG_BREAK_CONDITION = "breakCondition";
    static final String CONFIG_KNOWLEDGE_BASE_CODE = "knowledgeBaseCode";
    static final String CONFIG_TITLE_EXPRESSION = "titleExpression";
    static final String CONFIG_CONTENT_EXPRESSION = "contentExpression";
    static final String CONFIG_TAGS = "tags";
    static final String CONFIG_WRITE_MODE = "writeMode";
    static final String CONFIG_SOURCE_EXPRESSION = "sourceExpression";
    static final String CONFIG_FORMAT = "format";
    static final String CONFIG_SERVER_REF = "serverRef";
    static final String CONFIG_MESSAGES = "messages";
    static final String CONFIG_USER_PROMPT = "userPrompt";
    static final String CONFIG_CONTEXT_VARIABLES = "contextVariables";
    static final String CONFIG_MODEL_PARAMS = "modelParams";
    static final String CONFIG_OUTPUT_FORMAT = "outputFormat";
    static final String CONFIG_STRUCTURED_OUTPUT = "structuredOutput";
    static final String CONFIG_STRICT_JSON_SCHEMA = "strictJsonSchema";
    static final String CONFIG_OUTPUT_SCHEMA = "outputSchema";
    static final String CONFIG_VISION_ENABLED = "visionEnabled";
    static final String CONFIG_VISION_INPUTS = "visionInputs";
    static final String CONFIG_FIELDS = "fields";
    static final String CONFIG_EXTRACT_MODE = "extractMode";
    static final String CONFIG_METHOD = "method";
    static final String CONFIG_URL = "url";
    static final String CONFIG_QUERY_PARAMS = "queryParams";
    static final String CONFIG_HEADERS = "headers";
    static final String CONFIG_BODY_TYPE = "bodyType";
    static final String CONFIG_BODY = "body";
    static final String CONFIG_TIMEOUT_MS = "timeoutMs";
    static final String CONFIG_CREDENTIAL_REF = "credentialRef";
    static final String CONFIG_KNOWLEDGE_BASE_CODES = "knowledgeBaseCodes";
    static final String CONFIG_KNOWLEDGE_BASE_GROUP_ID = "knowledgeBaseGroupId";
    static final String CONFIG_QUERY = "query";
    static final String CONFIG_TOP_K = "topK";
    static final String CONFIG_SEARCH_MODE = "searchMode";
    static final String CONFIG_RERANK_ENABLED = "rerankEnabled";
    static final String CONFIG_SIMILARITY_THRESHOLD = "similarityThreshold";
    static final String CONFIG_DIRECT_RETURN_ENABLED = "directReturnEnabled";
    static final String CONFIG_DIRECT_RETURN_THRESHOLD = "directReturnThreshold";
    static final String CONFIG_CONDITION_GROUPS = "conditionGroups";
    static final String CONFIG_DEFAULT_ROUTE = "defaultRoute";
    static final String CONFIG_INTERACTION_TYPE = "interactionType";
    static final String CONFIG_COMPONENT = "component";
    static final String CONFIG_DATA_EXPRESSION = "dataExpression";
    static final String CONFIG_RENDER_SCHEMA = "renderSchema";
    static final String CONFIG_DATA_SOURCES = "dataSources";
    static final String CONFIG_BEHAVIOR = "behavior";
    static final String TOOL_SEARCH_KNOWLEDGE = "search_knowledge";
    static final String VAR_PREFIX = "var.";
    static final String SPAN_ROOT = "agent-run";

    private static final int SUCCESS_CODE = 200;
    private static final Pattern TEMPLATE_VARIABLE = Pattern.compile("\\{\\{\\s*([^}]+?)\\s*}}");

    private final ModelServiceClient modelServiceClient;
    private final ToolDefinitionService toolDefinitionService;
    private final ToolCallLogService toolCallLogService;
    private final AgentTraceSpanService traceSpanService;
    private final ObjectMapper objectMapper;
    private final WorkflowCredentialService workflowCredentialService;
    private final SkillsServiceClient skillsServiceClient;
    private final SkillInteractionMapper skillInteractionMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public String runtimeType() {
        return LANGGRAPH4J_RUNTIME_TYPE;
    }

    @Override
    public AgentRuntimeCapability capability() {
        return AgentRuntimeCapability.builder()
                .runtimeType(LANGGRAPH4J_RUNTIME_TYPE)
                .displayName("LangGraph4j")
                .agentMode("WORKFLOW")
                .configurationSurface("STUDIO")
                .primaryAction("进入流程画布")
                .resourcePolicy("NODE_LEVEL")
                .description("Java graph runtime that compiles platform GraphSpec into observable LLM, Tool, variable, template and conditional edge execution.")
                .available(true)
                .supportedModelType("LLM")
                .supportsStreaming(false)
                .supportsTools(true)
                .supportsHandoff(false)
                .supportsGraph(true)
                .supportsHumanInterrupt(false)
                .supportsArtifacts(false)
                .supportsCodeWorkspace(false)
                .supportsCloudExecution(false)
                .securityLevel("PROJECT")
                .build();
    }

    @Override
    public boolean supports(AgentRuntimeRequest request) {
        GraphSpec spec = request == null ? null : request.getGraphSpec();
        if (spec == null) {
            return false;
        }
        List<GraphSpec.Node> nodes = orderedExecutableNodes(spec);
        return !nodes.isEmpty() && nodes.stream().allMatch(this::isSupportedNode);
    }

    @Override
    public String unsupportedReason(AgentRuntimeRequest request) {
        GraphSpec spec = request == null ? null : request.getGraphSpec();
        if (spec == null || spec.getNodes() == null || spec.getNodes().isEmpty()) {
            return "LangGraph4j requires graphSpec and graphRuntimeContext.";
        }
        return "LangGraph4j currently supports USER_INPUT, INTERACTION, PAGE_ACTION, LLM, TOOL, CAPABILITY, IF_ELSE, VARIABLE_ASSIGN, TEMPLATE, ANSWER, CODE, INTENT_CLASSIFIER, VARIABLE_AGGREGATOR, HUMAN_APPROVAL, LOOP, KNOWLEDGE_WRITE, DOCUMENT_EXTRACT, MCP_CALL, PARAMETER_EXTRACT, HTTP_REQUEST, KNOWLEDGE_RETRIEVAL nodes and simple conditional edges.";
    }

    @Override
    public AgentRuntimeResult execute(AgentRuntimeRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("runtime request is required");
        }
        if (request.getGraphSpec() == null || request.getGraphRuntimeContext() == null) {
            throw new IllegalArgumentException("graphSpec and graphRuntimeContext are required");
        }
        return execute(request.getGraphSpec(), request.getGraphRuntimeContext(), request);
    }

    public AgentRuntimeResult execute(GraphSpec graphSpec,
                                      GraphRuntimeContext runtimeContext,
                                      AgentRuntimeRequest request) {
        if (graphSpec == null || runtimeContext == null || request == null) {
            throw new IllegalArgumentException("graphSpec, runtimeContext and request are required");
        }
        String traceId = request.getTraceId();
        long start = System.currentTimeMillis();
        LocalDateTime startedAt = LocalDateTime.now();
        ToolExecutionContext context = buildExecutionContext(request, runtimeContext);
        List<GraphSpec.Node> nodes = orderedExecutableNodes(graphSpec);
        Map<String, Object> initialState = initialState(request, runtimeContext, nodes);
        try {
            StateGraph<LangGraphState> graph = buildGraph(context, graphSpec, runtimeContext, nodes);
            LangGraphState finalState = graph.compile()
                    .invoke(initialState,
                            RunnableConfig.builder()
                                    .threadId(request.getSessionId())
                                    .graphId(graphRunId(runtimeContext))
                                    .build())
                    .orElseThrow(() -> new IllegalStateException("LangGraph4j execution returned no final state"));

            String answer = finalAnswer(finalState);
            UiRequestPayload uiRequest = uiRequestFromOutput(finalState.value(LAST_OUTPUT).orElse(null));
            long elapsed = System.currentTimeMillis() - start;
            Map<String, Object> metadata = metadata(request, runtimeContext, graphSpec, elapsed, finalState, nodes);
            logRuntimeRun(context, request, runtimeContext, metadata, true, null, elapsed);
            recordSpan(context, runtimeContext, AgentTraceSpanService.SpanRecord.builder()
                    .traceId(traceId)
                    .spanId(SPAN_ROOT)
                    .spanType("AGENT_RUN")
                    .runtimeType(runtimeType())
                    .input(request.getMessage())
                    .output(answer)
                    .success(true)
                    .latencyMs(elapsed)
                    .startedAt(startedAt)
                    .endedAt(LocalDateTime.now())
                    .metadata(metadata)
                    .build());
            return AgentRuntimeResult.builder()
                    .success(true)
                    .answer(answer)
                    .runtimeType(runtimeType())
                    .traceId(traceId)
                    .agentName(runtimeContext.getName())
                    .steps(steps(nodes))
                    .toolCalls(toolCalls(nodes))
                    .uiRequest(uiRequest)
                    .tokenUsage(asMap(finalState.value(TOKEN_USAGE).orElse(null)))
                    .metadata(metadata)
                    .build();
        } catch (InteractionSuspendedException suspended) {
            return suspendedRuntimeResult(request, runtimeContext, context, graphSpec, nodes, start, suspended);
        } catch (Exception ex) {
            InteractionSuspendedException suspended = findSuspended(ex);
            if (suspended != null) {
                return suspendedRuntimeResult(request, runtimeContext, context, graphSpec, nodes, start, suspended);
            }
            long elapsed = System.currentTimeMillis() - start;
            log.error("[LangGraph4jRuntime] execution failed: agent={}, traceId={}", runtimeContext.getName(), traceId, ex);
            logRuntimeRun(context, request, runtimeContext, Map.of("error", ex.getMessage()), false, ex, elapsed);
            recordSpan(context, runtimeContext, AgentTraceSpanService.SpanRecord.builder()
                    .traceId(traceId)
                    .spanId(SPAN_ROOT)
                    .spanType("AGENT_RUN")
                    .runtimeType(runtimeType())
                    .input(request.getMessage())
                    .output(Map.of("error", nullToEmpty(ex.getMessage())))
                    .success(false)
                    .errorCode(ex.getClass().getSimpleName())
                    .errorMessage(ex.getMessage())
                    .latencyMs(elapsed)
                    .startedAt(startedAt)
                    .endedAt(LocalDateTime.now())
                    .metadata(Map.of("agentName", runtimeContext.getName(), "runtimeType", runtimeType()))
                    .build());
            return AgentRuntimeResult.builder()
                    .success(false)
                    .answer("LangGraph4j Runtime execution failed: " + ex.getMessage())
                    .runtimeType(runtimeType())
                    .traceId(traceId)
                    .agentName(runtimeContext.getName())
                    .errorCode(ex.getClass().getSimpleName())
                    .errorMessage(ex.getMessage())
                    .metadata(Map.of(
                            "traceId", traceId,
                            "agentName", runtimeContext.getName(),
                            "runtimeType", runtimeType(),
                            "elapsedMs", elapsed))
                    .build();
        }
    }

    private AgentRuntimeResult suspendedRuntimeResult(AgentRuntimeRequest request,
                                                      GraphRuntimeContext runtimeContext,
                                                      ToolExecutionContext context,
                                                      GraphSpec graphSpec,
                                                      List<GraphSpec.Node> nodes,
                                                      long start,
                                                      InteractionSuspendedException suspended) {
        long elapsed = System.currentTimeMillis() - start;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("traceId", request.getTraceId());
        metadata.put("agentName", runtimeContext.getName());
        metadata.put("runtimeType", runtimeType());
        metadata.put("elapsedMs", elapsed);
        metadata.put("uiRequest", suspended.getPayload());
        mergeWorkflowMetadata(metadata, runtimeContext);
        logRuntimeRun(context, request, runtimeContext, metadata, true, null, elapsed);
        return AgentRuntimeResult.builder()
                .success(true)
                .answer(suspended.getMessage())
                .runtimeType(runtimeType())
                .traceId(request.getTraceId())
                .agentName(runtimeContext.getName())
                .uiRequest(suspended.getPayload())
                .steps(steps(nodes))
                .toolCalls(toolCalls(nodes))
                .metadata(metadata)
                .build();
    }

    private String graphRunId(GraphRuntimeContext runtimeContext) {
        return "graph:" + firstNonBlank(runtimeContext.getSourceId(), runtimeContext.getSourceKeySlug(), "runtime");
    }

    private InteractionSuspendedException findSuspended(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof InteractionSuspendedException suspended) {
                return suspended;
            }
            current = current.getCause();
        }
        return null;
    }

    public AgentRuntimeResult resumeFromHumanApproval(AgentRuntimeRequest request,
                                                      Map<String, Object> suspendedState,
                                                      String approvalNodeId,
                                                      String route) {
        if (request.getGraphSpec() == null || request.getGraphRuntimeContext() == null) {
            throw new IllegalArgumentException("resume requires graphSpec and graphRuntimeContext");
        }
        GraphSpec baseSpec = request.getGraphSpec();
        GraphRuntimeContext runtimeContext = request.getGraphRuntimeContext();

        Map<String, Object> resumeState = new LinkedHashMap<>(suspendedState == null ? Map.of() : suspendedState);
        String normalizedRoute = firstNonBlank(route, "approved");
        Map<String, Object> approval = new LinkedHashMap<>();
        approval.put("status", normalizedRoute);
        approval.put("route", normalizedRoute);
        approval.put("interactive", true);
        approval.put("resumed", true);
        resumeState.put(LAST_OUTPUT, approval);
        resumeState.put(LAST_SUCCESS, true);
        resumeState.put(LAST_ERROR, "");
        resumeState.put(LAST_ROUTE, normalizedRoute);
        resumeState.put(nodeOutputKey(approvalNodeId), approval);

        List<GraphSpec.Node> allNodes = orderedExecutableNodes(baseSpec);
        Map<String, GraphSpec.Node> nodeById = new LinkedHashMap<>();
        for (GraphSpec.Node node : allNodes) {
            nodeById.put(node.getId(), node);
        }
        List<GraphEdgeRoute> outgoing = outgoingRoutes(baseSpec, approvalNodeId, nodeById);
        String next = nextTargetAfterApproval(new LangGraphState(resumeState), outgoing);
        if (next == null || END.equals(next)) {
            return resumedResult(request, runtimeContext, new LangGraphState(resumeState), allNodes, "Human approval completed");
        }

        GraphSpec resumedSpec = graphSpecWithEntry(baseSpec, next);
        List<GraphSpec.Node> resumedNodes = orderedExecutableNodes(resumedSpec);
        ToolExecutionContext context = buildExecutionContext(request, runtimeContext);
        long start = System.currentTimeMillis();
        try {
            StateGraph<LangGraphState> graph = buildGraph(context, resumedSpec, runtimeContext, resumedNodes);
            LangGraphState finalState = graph.compile()
                    .invoke(resumeState,
                            RunnableConfig.builder()
                                    .threadId(request.getSessionId())
                                    .graphId(graphRunId(runtimeContext) + ":resume:" + approvalNodeId)
                                    .build())
                    .orElseThrow(() -> new IllegalStateException("LangGraph4j resume returned no final state"));
            UiRequestPayload uiRequest = uiRequestFromOutput(finalState.value(LAST_OUTPUT).orElse(null));
            long elapsed = System.currentTimeMillis() - start;
            Map<String, Object> metadata = metadata(request, runtimeContext, resumedSpec, elapsed, finalState, resumedNodes);
            metadata.put("resumedFrom", approvalNodeId);
            metadata.put("approvalRoute", normalizedRoute);
            logRuntimeRun(context, request, runtimeContext, metadata, true, null, elapsed);
            return AgentRuntimeResult.builder()
                    .success(true)
                    .answer(finalAnswer(finalState))
                    .runtimeType(runtimeType())
                    .traceId(request.getTraceId())
                    .agentName(runtimeContext.getName())
                    .steps(steps(resumedNodes))
                    .toolCalls(toolCalls(resumedNodes))
                    .uiRequest(uiRequest)
                    .tokenUsage(asMap(finalState.value(TOKEN_USAGE).orElse(null)))
                    .metadata(metadata)
                    .build();
        } catch (Exception ex) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[LangGraph4jRuntime] resume failed: graph={}, node={}, traceId={}",
                    runtimeContext.getName(), approvalNodeId, request.getTraceId(), ex);
            logRuntimeRun(context, request, runtimeContext, Map.of("error", ex.getMessage()), false, ex, elapsed);
            return AgentRuntimeResult.builder()
                    .success(false)
                    .answer("LangGraph4j Runtime resume failed: " + ex.getMessage())
                    .runtimeType(runtimeType())
                    .traceId(request.getTraceId())
                    .agentName(runtimeContext.getName())
                    .errorCode(ex.getClass().getSimpleName())
                    .errorMessage(ex.getMessage())
                    .metadata(Map.of(
                            "traceId", request.getTraceId(),
                            "agentName", runtimeContext.getName(),
                            "runtimeType", runtimeType(),
                            "elapsedMs", elapsed,
                            "resumedFrom", approvalNodeId,
                            "approvalRoute", normalizedRoute))
                    .build();
        }
    }

    private AgentRuntimeResult resumedResult(AgentRuntimeRequest request,
                                             GraphRuntimeContext runtimeContext,
                                             LangGraphState state,
                                             List<GraphSpec.Node> nodes,
                                             String defaultAnswer) {
        String answer = firstNonBlank(finalAnswer(state), defaultAnswer);
        Map<String, Object> metadata = metadata(request, runtimeContext, null, 0L, state, nodes);
        return AgentRuntimeResult.builder()
                .success(true)
                .answer(answer)
                .runtimeType(runtimeType())
                .traceId(request.getTraceId())
                .agentName(runtimeContext.getName())
                .steps(steps(nodes))
                .toolCalls(toolCalls(nodes))
                .uiRequest(uiRequestFromOutput(state.value(LAST_OUTPUT).orElse(null)))
                .tokenUsage(asMap(state.value(TOKEN_USAGE).orElse(null)))
                .metadata(metadata)
                .build();
    }

    private String nextTargetAfterApproval(LangGraphState state, List<GraphEdgeRoute> outgoing) {
        if (outgoing == null || outgoing.isEmpty()) {
            return null;
        }
        if (outgoing.size() == 1 && isAlwaysCondition(outgoing.get(0).condition())) {
            return outgoing.get(0).target();
        }
        String selectedKey = selectRouteKey(state, outgoing);
        return outgoing.stream()
                .filter(route -> route.key().equals(selectedKey))
                .map(GraphEdgeRoute::target)
                .findFirst()
                .orElse(null);
    }

    private GraphSpec graphSpecWithEntry(GraphSpec source, String entry) {
        return GraphSpec.builder()
                .code(source == null ? null : source.getCode())
                .name(source == null ? null : source.getName())
                .mode(source == null ? null : source.getMode())
                .runtimeHint(source == null ? null : source.getRuntimeHint())
                .inputSchema(source == null ? null : source.getInputSchema())
                .stateSchema(source == null ? null : source.getStateSchema())
                .layout(source == null ? null : source.getLayout())
                .nodes(source == null ? List.of() : source.getNodes())
                .edges(source == null ? List.of() : source.getEdges())
                .entry(entry)
                .finish(source == null ? List.of() : source.getFinish())
                .build();
    }

    public NodeDebugResult debugNode(GraphSpec graphSpec,
                                     GraphRuntimeContext runtimeContext,
                                     String nodeId,
                                     String message,
                                     Map<String, Object> stateOverrides) {
        if (graphSpec == null || runtimeContext == null) {
            throw new IllegalArgumentException("graphSpec and runtimeContext are required");
        }
        GraphSpec.Node node = graphSpec.getNodes() == null ? null : graphSpec.getNodes().stream()
                .filter(item -> item != null && nodeId != null && nodeId.equals(item.getId()))
                .findFirst()
                .orElse(null);
        if (node == null || !isSupportedNode(node)) {
            throw new IllegalArgumentException("Executable node not found: " + nodeId);
        }
        GraphSpec singleNodeSpec = GraphSpec.builder()
                .code(firstNonBlank(graphSpec.getCode(), "studio_node_debug"))
                .name(firstNonBlank(graphSpec.getName(), runtimeContext.getName()))
                .runtimeHint(LANGGRAPH4J_RUNTIME_TYPE)
                .entry(node.getId())
                .finishNode(node.getId())
                .node(node)
                .edge(GraphSpec.Edge.builder().from(START).to(node.getId()).condition("always").build())
                .edge(GraphSpec.Edge.builder().from(node.getId()).to(END).condition("always").build())
                .build();
        AgentRuntimeRequest request = AgentRuntimeRequest.builder()
                .traceId("studio-node-debug-" + UUID.randomUUID())
                .sessionId("studio-node-debug")
                .userId("studio-debug")
                .message(nullToEmpty(message))
                .intentType(runtimeContext.getIntentType())
                .build();
        List<GraphSpec.Node> executableNodes = List.of(node);
        Map<String, Object> initial = initialState(request, runtimeContext, executableNodes);
        if (stateOverrides != null) {
            initial.putAll(stateOverrides);
        }
        long start = System.currentTimeMillis();
        ToolExecutionContext context = buildExecutionContext(request, runtimeContext);
        try {
            StateGraph<LangGraphState> graph = buildGraph(context, singleNodeSpec, runtimeContext, executableNodes);
            LangGraphState finalState = graph.compile()
                    .invoke(initial, RunnableConfig.builder()
                            .threadId(request.getSessionId())
                            .graphId("studio-node-debug:" + runtimeContext.getSourceId() + ":" + node.getId())
                            .build())
                    .orElseThrow(() -> new IllegalStateException("LangGraph4j node debug returned no final state"));
            Map<String, Object> outputState = new LinkedHashMap<>(finalState.data());
            return NodeDebugResult.builder()
                    .nodeId(node.getId())
                    .nodeType(node.getType())
                    .success(true)
                    .elapsedMs(System.currentTimeMillis() - start)
                    .inputState(initial)
                    .outputState(outputState)
                    .nodeOutput(outputState.get(LAST_OUTPUT))
                    .lastRoute(asString(outputState.get(LAST_ROUTE)))
                    .traceId(request.getTraceId())
                    .build();
        } catch (Exception ex) {
            return NodeDebugResult.builder()
                    .nodeId(node.getId())
                    .nodeType(node.getType())
                    .success(false)
                    .elapsedMs(System.currentTimeMillis() - start)
                    .inputState(initial)
                    .outputState(Map.of(
                            LAST_SUCCESS, false,
                            LAST_ERROR, nullToEmpty(ex.getMessage())))
                    .errorCode(ex.getClass().getSimpleName())
                    .errorMessage(ex.getMessage())
                    .traceId(request.getTraceId())
                    .build();
        }
    }

    public WorkflowDebugRunResult debugRun(GraphSpec graphSpec,
                                           GraphRuntimeContext runtimeContext,
                                           String message,
                                           Map<String, Object> inputParams,
                                           Map<String, Object> debugOptions) {
        if (graphSpec == null || runtimeContext == null) {
            throw new IllegalArgumentException("graphSpec and runtimeContext are required");
        }
        String runId = "studio-debug-run-" + UUID.randomUUID();
        String traceId = runId;
        AgentRuntimeRequest request = AgentRuntimeRequest.builder()
                .traceId(firstNonBlank(asString(debugOptions == null ? null : debugOptions.get("traceId")), traceId))
                .sessionId(firstNonBlank(asString(debugOptions == null ? null : debugOptions.get("sessionId")), runId))
                .userId("studio-debug")
                .message(nullToEmpty(message))
                .intentType(runtimeContext.getIntentType())
                .runtimeOptions(Map.of("params", inputParams == null ? Map.of() : inputParams))
                .build();
        runId = firstNonBlank(asString(debugOptions == null ? null : debugOptions.get("runId")), runId);
        traceId = request.getTraceId();
        List<GraphSpec.Node> executableNodes = orderedExecutableNodes(graphSpec);
        if (executableNodes.isEmpty()) {
            throw new IllegalArgumentException("LangGraph4j GraphSpec has no executable nodes");
        }
        Map<String, GraphSpec.Node> nodeById = new LinkedHashMap<>();
        for (GraphSpec.Node node : executableNodes) {
            nodeById.put(node.getId(), node);
        }
        Map<String, Object> stateData = new LinkedHashMap<>(initialState(request, runtimeContext, executableNodes));
        Object stateOverride = debugOptions == null ? null : debugOptions.get("state");
        if (stateOverride instanceof Map<?, ?> map) {
            map.forEach((key, value) -> stateData.put(String.valueOf(key), value));
        }
        ToolExecutionContext context = buildExecutionContext(request, runtimeContext);
        List<WorkflowDebugStepResult> steps = new ArrayList<>();
        String currentNodeId = firstNonBlank(asString(debugOptions == null ? null : debugOptions.get("entryNodeId")),
                resolveEntry(graphSpec, nodeById));
        String status = "SUCCESS";
        String errorCode = null;
        String errorMessage = null;
        String suspendedAnswer = null;
        String resultCurrentNodeId = null;
        UiRequestPayload resultUiRequest = null;
        Set<String> visited = new HashSet<>();
        int maxSteps = Math.max(1, executableNodes.size() * 4);
        for (int index = 0; index < maxSteps && currentNodeId != null && !END.equals(currentNodeId); index++) {
            GraphSpec.Node node = nodeById.get(currentNodeId);
            if (node == null) {
                status = "ERROR";
                errorCode = "NODE_NOT_FOUND";
                errorMessage = "Executable node not found: " + currentNodeId;
                break;
            }
            if (!visited.add(currentNodeId + "#" + index) && index > maxSteps) {
                status = "ERROR";
                errorCode = "LOOP_LIMIT";
                errorMessage = "Debug run exceeded max steps";
                break;
            }
            Map<String, Object> inputState = new LinkedHashMap<>(stateData);
            long start = System.currentTimeMillis();
            LocalDateTime startedAt = LocalDateTime.now();
            try {
                LangGraphState state = new LangGraphState(stateData);
                List<GraphEdgeRoute> outgoing = outgoingRoutes(graphSpec, node.getId(), nodeById);
                boolean allowErrorRoute = hasErrorRoute(outgoing);
                Map<String, Object> resolvedInput = resolveNodeInput(state, node);
                Map<String, Object> update = executeDebugNode(state, context, runtimeContext, graphSpec, node, allowErrorRoute);
                stateData.putAll(update);
                RouteDecision routeDecision = decideNextRoute(new LangGraphState(stateData), outgoing);
                long elapsed = System.currentTimeMillis() - start;
                boolean nodeSuccess = !Boolean.FALSE.equals(update.get(LAST_SUCCESS))
                        && asString(update.get(LAST_ERROR)).isBlank();
                Object rawOutput = rawNodeOutput(update, node);
                UiRequestPayload stepUiRequest = uiRequestFromDebugOutput(rawOutput);
                steps.add(WorkflowDebugStepResult.builder()
                        .index(index)
                        .nodeId(node.getId())
                        .nodeType(node.getType())
                        .nodeName(node.getName())
                        .status(nodeSuccess ? "SUCCESS" : "ERROR")
                        .startedAt(startedAt)
                        .endedAt(LocalDateTime.now())
                        .elapsedMs(elapsed)
                        .input(inputState)
                        .resolvedInput(resolvedInput)
                        .output(update)
                        .rawOutput(rawOutput)
                        .publishedVariables(publishedVariables(update, node))
                        .statePatch(update)
                        .eventType(stepUiRequest == null ? "NODE" : "OUTPUT")
                        .uiRequest(stepUiRequest)
                        .artifact(stepUiRequest == null ? null : safeMap(asMap(rawOutput)))
                        .route(firstNonBlank(asString(update.get(LAST_ROUTE)), routeDecision == null ? "" : routeDecision.route()))
                        .condition(routeDecision == null ? null : routeDecision.condition())
                        .nextNodeId(routeDecision == null ? null : routeDecision.target())
                        .errorMessage(asString(update.get(LAST_ERROR)))
                        .build());
                currentNodeId = routeDecision == null ? END : routeDecision.target();
            } catch (InteractionSuspendedException suspended) {
                long elapsed = System.currentTimeMillis() - start;
                status = "WAITING";
                suspendedAnswer = suspended.getMessage();
                resultCurrentNodeId = node.getId();
                resultUiRequest = suspended.getPayload();
                Map<String, Object> waitingOutput = new LinkedHashMap<>();
                waitingOutput.put("status", "WAITING");
                waitingOutput.put("message", suspended.getMessage());
                waitingOutput.put("uiRequest", suspended.getPayload());
                Map<String, Object> patch = new LinkedHashMap<>();
                patch.put(LAST_SUCCESS, true);
                patch.put(LAST_ERROR, "");
                patch.put(LAST_OUTPUT, waitingOutput);
                patch.put(nodeOutputKey(node.getId()), waitingOutput);
                putOutputAlias(patch, node, waitingOutput);
                steps.add(WorkflowDebugStepResult.builder()
                        .index(index)
                        .nodeId(node.getId())
                        .nodeType(node.getType())
                        .nodeName(node.getName())
                        .status("WAITING")
                        .startedAt(startedAt)
                        .endedAt(LocalDateTime.now())
                        .elapsedMs(elapsed)
                        .input(inputState)
                        .resolvedInput(resolveNodeInput(new LangGraphState(inputState), node))
                        .output(patch)
                        .rawOutput(waitingOutput)
                        .publishedVariables(publishedVariables(patch, node))
                        .statePatch(patch)
                        .eventType("WAITING")
                        .uiRequest(suspended.getPayload())
                        .artifact(waitingOutput)
                        .build());
                stateData.putAll(patch);
                break;
            } catch (Exception ex) {
                long elapsed = System.currentTimeMillis() - start;
                status = "ERROR";
                errorCode = ex.getClass().getSimpleName();
                errorMessage = ex.getMessage();
                resultCurrentNodeId = node.getId();
                Map<String, Object> patch = new LinkedHashMap<>();
                patch.put(LAST_SUCCESS, false);
                patch.put(LAST_ERROR, nullToEmpty(ex.getMessage()));
                steps.add(WorkflowDebugStepResult.builder()
                        .index(index)
                        .nodeId(node.getId())
                        .nodeType(node.getType())
                        .nodeName(node.getName())
                        .status("ERROR")
                        .startedAt(startedAt)
                        .endedAt(LocalDateTime.now())
                        .elapsedMs(elapsed)
                        .input(inputState)
                        .resolvedInput(resolveNodeInput(new LangGraphState(inputState), node))
                        .output(patch)
                        .rawOutput(null)
                        .publishedVariables(publishedVariables(patch, node))
                        .statePatch(patch)
                        .eventType("ERROR")
                        .errorCode(errorCode)
                        .errorMessage(errorMessage)
                        .build());
                stateData.putAll(patch);
                break;
            }
        }
        if (steps.size() >= maxSteps && !END.equals(currentNodeId)) {
            status = "ERROR";
            errorCode = "LOOP_LIMIT";
            errorMessage = "Debug run exceeded max steps";
            resultCurrentNodeId = currentNodeId;
        }
        String answer = firstNonBlank(suspendedAnswer, finalAnswer(new LangGraphState(stateData)));
        return WorkflowDebugRunResult.builder()
                .runId(runId)
                .traceId(traceId)
                .status(status)
                .success(!"ERROR".equals(status))
                .answer(answer)
                .steps(steps)
                .finalState(stateData)
                .currentNodeId(resultCurrentNodeId)
                .uiRequest(resultUiRequest)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }

    private Map<String, Object> executeDebugNode(LangGraphState state,
                                                 ToolExecutionContext context,
                                                 GraphRuntimeContext runtimeContext,
                                                 GraphSpec graphSpec,
                                                 GraphSpec.Node node,
                                                 boolean allowErrorRoute) {
        if (isLlmNode(node)) {
            return callModelNode(state, context, runtimeContext, node, allowErrorRoute);
        }
        if (isToolNode(node)) {
            return callToolNode(state, context, runtimeContext, node, allowErrorRoute);
        }
        if (isFlowNode(node)) {
            return callFlowNode(state, context, runtimeContext, graphSpec, node, allowErrorRoute);
        }
        throw new IllegalArgumentException("Unsupported debug node: " + node.getType());
    }

    private RouteDecision decideNextRoute(LangGraphState state, List<GraphEdgeRoute> routes) {
        if (routes == null || routes.isEmpty()) {
            return null;
        }
        if (routes.size() == 1 && isAlwaysCondition(routes.get(0).condition())) {
            GraphEdgeRoute route = routes.get(0);
            return new RouteDecision(route.target(), route.condition(), routeName(state, route.condition()));
        }
        String key = selectRouteKey(state, routes);
        GraphEdgeRoute selected = routes.stream()
                .filter(route -> route.key().equals(key))
                .findFirst()
                .orElse(routes.get(0));
        return new RouteDecision(selected.target(), selected.condition(), routeName(state, selected.condition()));
    }

    private String routeName(LangGraphState state, String condition) {
        String lastRoute = state.value(LAST_ROUTE, "");
        if (!lastRoute.isBlank()) {
            return lastRoute;
        }
        String normalized = normalizeCondition(condition);
        if (normalized.toLowerCase().startsWith("route:")) {
            return normalized.substring("route:".length()).trim();
        }
        if ("else".equalsIgnoreCase(normalized) || "default".equalsIgnoreCase(normalized)) {
            return "else";
        }
        return normalized;
    }

    private StateGraph<LangGraphState> buildGraph(ToolExecutionContext context,
                                                  GraphSpec spec,
                                                  GraphRuntimeContext runtimeContext,
                                                  List<GraphSpec.Node> nodes) throws Exception {
        StateGraph<LangGraphState> graph = new StateGraph<>(LangGraphState::new);
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("LangGraph4j GraphSpec has no executable nodes");
        }
        if (spec == null) {
            throw new IllegalArgumentException("LangGraph4j GraphSpec is required");
        }
        Map<String, GraphSpec.Node> nodeById = new LinkedHashMap<>();
        for (GraphSpec.Node node : nodes) {
            nodeById.put(node.getId(), node);
        }
        for (GraphSpec.Node graphNode : nodes) {
            GraphSpec.Node node = graphNode;
            boolean allowErrorRoute = hasErrorRoute(outgoingRoutes(spec, node.getId(), nodeById));
            if (isLlmNode(node)) {
                graph = graph.addNode(node.getId(), node_async(state -> callModelNode(state, context, runtimeContext, node, allowErrorRoute)));
            } else if (isToolNode(node)) {
                graph = graph.addNode(node.getId(), node_async(state -> callToolNode(state, context, runtimeContext, node, allowErrorRoute)));
            } else if (isFlowNode(node)) {
                graph = graph.addNode(node.getId(), node_async(state -> callFlowNode(state, context, runtimeContext, spec, node, allowErrorRoute)));
            }
        }
        String entry = resolveEntry(spec, nodeById);
        graph = graph.addEdge(START, entry);
        for (GraphSpec.Node node : nodes) {
            List<GraphEdgeRoute> outgoing = outgoingRoutes(spec, node.getId(), nodeById);
            if (outgoing.isEmpty()) {
                graph = graph.addEdge(node.getId(), END);
            } else if (outgoing.size() == 1 && isAlwaysCondition(outgoing.get(0).condition())) {
                graph = graph.addEdge(node.getId(), outgoing.get(0).target());
            } else {
                Map<String, String> mappings = new LinkedHashMap<>();
                for (GraphEdgeRoute route : outgoing) {
                    mappings.put(route.key(), route.target());
                }
                List<GraphEdgeRoute> routes = List.copyOf(outgoing);
                graph = graph.addConditionalEdges(node.getId(), edge_async(state -> selectRouteKey(state, routes)), mappings);
            }
        }
        return graph;
    }

    private Map<String, Object> callModelNode(LangGraphState state,
                                              ToolExecutionContext context,
                                              GraphRuntimeContext runtimeContext,
                                              GraphSpec.Node node,
                                              boolean allowErrorRoute) {
        long start = System.currentTimeMillis();
        LocalDateTime startedAt = LocalDateTime.now();
        Map<String, Object> config = safeMap(node.getConfig());
        String modelInstanceId = firstNonBlank(asString(config.get(MODEL_INSTANCE_ID)), state.value(MODEL_INSTANCE_ID, ""));
        String systemPrompt = firstNonBlank(asString(config.get(SYSTEM_PROMPT)), state.value(SYSTEM_PROMPT, ""));
        String configuredPrompt = asString(config.get(CONFIG_USER_PROMPT));
        String userMessage = configuredPrompt.isBlank() ? renderModelInput(state) : renderTemplate(state, configuredPrompt);
        List<ModelServiceClient.ModelChatRequest.ChatMessage> messages = buildLlmMessages(state, config, systemPrompt, userMessage);
        Object traceInput = llmTraceInput(messages);
        Map<String, Object> modelParams = asMap(config.get(CONFIG_MODEL_PARAMS));
        try {
            ModelServiceClient.ModelChatResult result = modelServiceClient.chat(ModelServiceClient.ModelChatRequest.builder()
                    .modelInstanceId(modelInstanceId)
                    .messages(messages)
                    .options(modelParams.isEmpty() ? null : modelParams)
                    .build());
            if (result == null || result.getCode() != SUCCESS_CODE || result.getData() == null) {
                String message = result == null ? "empty response" : result.getMessage();
                throw new IllegalStateException("model call failed: " + message);
            }

            ModelServiceClient.ModelChatData data = result.getData();
            Object modelOutput = normalizeLlmOutput(config, nullToEmpty(data.getContent()));
            Map<String, Object> update = new LinkedHashMap<>();
            update.put(ANSWER, stringify(modelOutput));
            update.put(LAST_OUTPUT, modelOutput);
            update.put(LAST_SUCCESS, true);
            update.put(LAST_ERROR, "");
            update.put(nodeOutputKey(node.getId()), modelOutput);
            putOutputAlias(update, node, modelOutput);
            update.put(MODEL, data.getModel());
            update.put(PROVIDER, data.getProvider());
            update.put(TOKEN_USAGE, tokenUsage(data.getUsage()));
            update.put(FINISH_REASON, data.getFinishReason());
            long elapsed = System.currentTimeMillis() - start;
            logNode(context, node, traceInput, update, true, null, elapsed);
            recordSpan(context, runtimeContext, AgentTraceSpanService.SpanRecord.builder()
                    .spanType("LLM_CALL")
                    .runtimeType(runtimeType())
                    .parentSpanId(SPAN_ROOT)
                    .nodeId(node.getId())
                    .modelInstanceId(modelInstanceId)
                    .input(traceInput)
                    .output(update)
                    .success(true)
                    .latencyMs(elapsed)
                    .tokenCost(tokenCostFrom(update))
                    .startedAt(startedAt)
                    .endedAt(LocalDateTime.now())
                    .metadata(nodeMetadata(node, update))
                    .build());
            return update;
        } catch (Exception ex) {
            long elapsed = System.currentTimeMillis() - start;
            Map<String, Object> error = Map.of("error", nullToEmpty(ex.getMessage()));
            Map<String, Object> update = new LinkedHashMap<>(error);
            update.put(LAST_OUTPUT, nullToEmpty(ex.getMessage()));
            update.put(LAST_SUCCESS, false);
            update.put(LAST_ERROR, nullToEmpty(ex.getMessage()));
            putOutputAlias(update, node, nullToEmpty(ex.getMessage()));
            logNode(context, node, traceInput, update, false, ex, elapsed);
            recordSpan(context, runtimeContext, AgentTraceSpanService.SpanRecord.builder()
                    .spanType("LLM_CALL")
                    .runtimeType(runtimeType())
                    .parentSpanId(SPAN_ROOT)
                    .nodeId(node.getId())
                    .modelInstanceId(modelInstanceId)
                    .input(traceInput)
                    .output(update)
                    .success(false)
                    .errorCode(ex.getClass().getSimpleName())
                    .errorMessage(ex.getMessage())
                    .latencyMs(elapsed)
                    .startedAt(startedAt)
                    .endedAt(LocalDateTime.now())
                    .metadata(nodeMetadata(node, update))
                    .build());
            if (allowErrorRoute) {
                return update;
            }
            throw new IllegalStateException(ex.getMessage(), ex);
        }
    }

    private Map<String, Object> callToolNode(LangGraphState state,
                                             ToolExecutionContext context,
                                             GraphRuntimeContext runtimeContext,
                                             GraphSpec.Node node,
                                             boolean allowErrorRoute) {
        long start = System.currentTimeMillis();
        LocalDateTime startedAt = LocalDateTime.now();
        String toolName = resolveExecutableToolName(node);
        Map<String, Object> args = resolveToolArgs(state, node);
        Duration requestTimeout = resolveToolRequestTimeout(node);
        applyToolCredentialArgs(args, node, runtimeContext);
        ToolExecutionContext prev = ToolExecutionContextHolder.get();
        try {
            ToolExecutionContextHolder.set(context);
            Object result = toolDefinitionService.executeTool(toolName, args, requestTimeout);
            Map<String, Object> update = new LinkedHashMap<>();
            update.put(LAST_OUTPUT, result);
            update.put(LAST_SUCCESS, true);
            update.put(LAST_ERROR, "");
            update.put(nodeOutputKey(node.getId()), result);
            putOutputAlias(update, node, result);
            long elapsed = System.currentTimeMillis() - start;
            logNode(context, node, args, result, true, null, elapsed);
            recordSpan(context, runtimeContext, AgentTraceSpanService.SpanRecord.builder()
                    .spanType("TOOL_CALL")
                    .runtimeType(runtimeType())
                    .parentSpanId(SPAN_ROOT)
                    .nodeId(node.getId())
                    .toolName(toolName)
                    .input(args)
                    .output(result)
                    .success(true)
                    .latencyMs(elapsed)
                    .startedAt(startedAt)
                    .endedAt(LocalDateTime.now())
                    .metadata(nodeMetadata(node))
                    .build());
            return update;
        } catch (Exception ex) {
            long elapsed = System.currentTimeMillis() - start;
            Map<String, Object> error = Map.of("error", nullToEmpty(ex.getMessage()));
            Map<String, Object> update = new LinkedHashMap<>(error);
            update.put(LAST_OUTPUT, nullToEmpty(ex.getMessage()));
            update.put(LAST_SUCCESS, false);
            update.put(LAST_ERROR, nullToEmpty(ex.getMessage()));
            putOutputAlias(update, node, nullToEmpty(ex.getMessage()));
            logNode(context, node, args, update, false, ex, elapsed);
            recordSpan(context, runtimeContext, AgentTraceSpanService.SpanRecord.builder()
                    .spanType("TOOL_CALL")
                    .runtimeType(runtimeType())
                    .parentSpanId(SPAN_ROOT)
                    .nodeId(node.getId())
                    .toolName(toolName)
                    .input(args)
                    .output(update)
                    .success(false)
                    .errorCode(ex.getClass().getSimpleName())
                    .errorMessage(ex.getMessage())
                    .latencyMs(elapsed)
                    .startedAt(startedAt)
                    .endedAt(LocalDateTime.now())
                    .metadata(nodeMetadata(node))
                    .build());
            if (allowErrorRoute) {
                return update;
            }
            throw new IllegalStateException(ex.getMessage(), ex);
        } finally {
            ToolExecutionContextHolder.set(prev);
        }
    }

    private List<ModelServiceClient.ModelChatRequest.ChatMessage> buildLlmMessages(LangGraphState state,
                                                                                   Map<String, Object> config,
                                                                                   String systemPrompt,
                                                                                   String userMessage) {
        List<ModelServiceClient.ModelChatRequest.ChatMessage> messages = new ArrayList<>();
        List<String> rawTemplates = new ArrayList<>();
        Object configured = config.get(CONFIG_MESSAGES);
        if (configured instanceof List<?> list && !list.isEmpty()) {
            for (Object item : list) {
                Map<String, Object> raw = asMap(item);
                if (Boolean.FALSE.equals(raw.get("enabled"))) {
                    continue;
                }
                String role = normalizeMessageRole(asString(raw.get("role")));
                String template = asString(raw.get("content"));
                rawTemplates.add(template);
                String content = renderTemplate(state, template);
                if (content.isBlank() && !"system".equals(role)) {
                    continue;
                }
                messages.add(ModelServiceClient.ModelChatRequest.ChatMessage.builder()
                        .role(role)
                        .content(content)
                        .build());
            }
        }
        if (messages.isEmpty()) {
            messages.add(ModelServiceClient.ModelChatRequest.ChatMessage.builder()
                    .role("system")
                    .content(systemPrompt)
                    .build());
            messages.add(ModelServiceClient.ModelChatRequest.ChatMessage.builder()
                    .role("user")
                    .content(userMessage)
                    .build());
            rawTemplates.add(systemPrompt);
            rawTemplates.add(configuredPromptForReference(config, userMessage));
        }
        appendSelectedContextVariables(messages, state, config, rawTemplates);
        appendStructuredOutputInstruction(messages, config);
        appendVisionInputs(messages, state, config);
        return messages;
    }

    private String configuredPromptForReference(Map<String, Object> config, String userMessage) {
        String configured = asString(config.get(CONFIG_USER_PROMPT));
        return configured.isBlank() ? userMessage : configured;
    }

    private void appendSelectedContextVariables(List<ModelServiceClient.ModelChatRequest.ChatMessage> messages,
                                                LangGraphState state,
                                                Map<String, Object> config,
                                                List<String> rawTemplates) {
        Object raw = config.get(CONFIG_CONTEXT_VARIABLES);
        if (!(raw instanceof List<?> variables) || variables.isEmpty()) {
            return;
        }
        List<String> lines = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object item : variables) {
            String expression = asString(item);
            if (expression.isBlank() || !seen.add(expression) || templateReferences(rawTemplates, expression)) {
                continue;
            }
            Object value = resolveExpression(state, expression);
            String rendered = contextValueText(value);
            if (rendered.isBlank()) {
                continue;
            }
            lines.add(expression + " = " + rendered);
        }
        if (lines.isEmpty()) {
            return;
        }
        messages.add(ModelServiceClient.ModelChatRequest.ChatMessage.builder()
                .role("user")
                .content("Selected runtime context:\n" + String.join("\n", lines))
                .build());
    }

    private boolean templateReferences(List<String> rawTemplates, String expression) {
        Pattern pattern = Pattern.compile("\\{\\{\\s*" + Pattern.quote(expression) + "\\s*}}");
        for (String template : rawTemplates == null ? List.<String>of() : rawTemplates) {
            if (pattern.matcher(template == null ? "" : template).find()) {
                return true;
            }
        }
        return false;
    }

    private String contextValueText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return writeJson(value);
        }
        return stringify(value);
    }

    private void appendStructuredOutputInstruction(List<ModelServiceClient.ModelChatRequest.ChatMessage> messages,
                                                   Map<String, Object> config) {
        if (!isStructuredOutput(config)) {
            return;
        }
        List<Map<String, Object>> schema = fieldList(config.get(CONFIG_OUTPUT_SCHEMA));
        String schemaText = schema.isEmpty() ? "{}" : writeJson(schema);
        String instruction = "Return only a valid JSON object. Do not wrap it in Markdown fences."
                + " Output fields schema: " + schemaText;
        if (Boolean.TRUE.equals(config.get(CONFIG_STRICT_JSON_SCHEMA))) {
            instruction += " Required fields must be present and types must match the schema.";
        }
        messages.add(ModelServiceClient.ModelChatRequest.ChatMessage.builder()
                .role("system")
                .content(instruction)
                .build());
    }

    private void appendVisionInputs(List<ModelServiceClient.ModelChatRequest.ChatMessage> messages,
                                    LangGraphState state,
                                    Map<String, Object> config) {
        if (!Boolean.TRUE.equals(config.get(CONFIG_VISION_ENABLED))) {
            return;
        }
        Object raw = config.get(CONFIG_VISION_INPUTS);
        if (!(raw instanceof List<?> inputs) || inputs.isEmpty()) {
            return;
        }
        List<String> resolved = inputs.stream()
                .map(item -> {
                    String expression = asString(item);
                    Object value = resolveExpression(state, expression);
                    return expression + " = " + stringify(value);
                })
                .filter(text -> !text.isBlank())
                .toList();
        if (resolved.isEmpty()) {
            return;
        }
        messages.add(ModelServiceClient.ModelChatRequest.ChatMessage.builder()
                .role("user")
                .content("Visual/file input references:\n" + String.join("\n", resolved))
                .build());
    }

    private Object llmTraceInput(List<ModelServiceClient.ModelChatRequest.ChatMessage> messages) {
        return messages.stream()
                .map(message -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("role", message.getRole());
                    item.put("content", message.getContent());
                    return item;
                })
                .toList();
    }

    private String normalizeMessageRole(String role) {
        String normalized = role == null ? "" : role.toLowerCase();
        if ("assistant".equals(normalized) || "system".equals(normalized)) {
            return normalized;
        }
        return "user";
    }

    private Map<String, Object> callFlowNode(LangGraphState state,
                                             ToolExecutionContext context,
                                             GraphRuntimeContext runtimeContext,
                                             GraphSpec graphSpec,
                                             GraphSpec.Node node,
                                             boolean allowErrorRoute) {
        long start = System.currentTimeMillis();
        LocalDateTime startedAt = LocalDateTime.now();
        try {
            Map<String, Object> update = switch (normalizeNodeType(node)) {
                case "USER_INPUT" -> executeUserInput(state, node);
                case "INTERACTION" -> executeInteraction(state, node);
                case "PAGE_ACTION" -> executePageAction(state, node);
                case "VARIABLE_ASSIGN" -> executeVariableAssign(state, node);
                case "TEMPLATE" -> executeTemplate(state, node);
                case "ANSWER" -> executeAnswer(state, node);
                case "CODE" -> executeCode(state, node);
                case "INTENT_CLASSIFIER" -> executeIntentClassifier(state, node);
                case "VARIABLE_AGGREGATOR" -> executeVariableAggregator(state, node);
                case "HUMAN_APPROVAL" -> executeHumanApproval(state, runtimeContext, graphSpec, node);
                case "LOOP" -> executeLoop(state, node);
                case "KNOWLEDGE_WRITE" -> executeKnowledgeWritePersisted(state, node);
                case "DOCUMENT_EXTRACT" -> executeDocumentExtract(state, node);
                case "MCP_CALL" -> executeMcpCall(state, node);
                case "IF_ELSE" -> executeIfElse(state, node);
                case "PARAMETER_EXTRACT" -> executeParameterExtract(state, node);
                case "HTTP_REQUEST" -> executeHttpRequest(state, runtimeContext, node);
                case "KNOWLEDGE_RETRIEVAL" -> executeKnowledgeRetrieval(state, node);
                default -> throw new IllegalArgumentException("Unsupported flow node: " + node.getType());
            };
            long elapsed = System.currentTimeMillis() - start;
            logNode(context, node, node.getConfig(), update, true, null, elapsed);
            recordSpan(context, runtimeContext, AgentTraceSpanService.SpanRecord.builder()
                    .spanType("FLOW_NODE")
                    .runtimeType(runtimeType())
                    .parentSpanId(SPAN_ROOT)
                    .nodeId(node.getId())
                    .input(node.getConfig())
                    .output(update)
                    .success(true)
                    .latencyMs(elapsed)
                    .startedAt(startedAt)
                    .endedAt(LocalDateTime.now())
                    .metadata(nodeMetadata(node))
                    .build());
            return update;
        } catch (InteractionSuspendedException suspended) {
            long elapsed = System.currentTimeMillis() - start;
            Map<String, Object> waitingOutput = new LinkedHashMap<>();
            waitingOutput.put("status", "WAITING");
            waitingOutput.put("message", suspended.getMessage());
            waitingOutput.put("uiRequest", suspended.getPayload());
            Map<String, Object> metadata = nodeMetadata(node);
            metadata.put("workflowStatus", "WAITING");
            metadata.put("interactionId", suspended.getPayload() == null ? null : suspended.getPayload().getInteractionId());
            metadata.put("uiRequest", suspended.getPayload());
            recordSpan(context, runtimeContext, AgentTraceSpanService.SpanRecord.builder()
                    .spanType("FLOW_NODE")
                    .runtimeType(runtimeType())
                    .parentSpanId(SPAN_ROOT)
                    .nodeId(node.getId())
                    .input(node.getConfig())
                    .output(waitingOutput)
                    .status("WAITING")
                    .success(true)
                    .latencyMs(elapsed)
                    .startedAt(startedAt)
                    .endedAt(LocalDateTime.now())
                    .metadata(metadata)
                    .build());
            throw suspended;
        } catch (Exception ex) {
            long elapsed = System.currentTimeMillis() - start;
            Map<String, Object> update = new LinkedHashMap<>();
            update.put(LAST_OUTPUT, nullToEmpty(ex.getMessage()));
            update.put(LAST_SUCCESS, false);
            update.put(LAST_ERROR, nullToEmpty(ex.getMessage()));
            logNode(context, node, node.getConfig(), update, false, ex, elapsed);
            recordSpan(context, runtimeContext, AgentTraceSpanService.SpanRecord.builder()
                    .spanType("FLOW_NODE")
                    .runtimeType(runtimeType())
                    .parentSpanId(SPAN_ROOT)
                    .nodeId(node.getId())
                    .input(node.getConfig())
                    .output(update)
                    .success(false)
                    .errorCode(ex.getClass().getSimpleName())
                    .errorMessage(ex.getMessage())
                    .latencyMs(elapsed)
                    .startedAt(startedAt)
                    .endedAt(LocalDateTime.now())
                    .metadata(nodeMetadata(node))
                    .build());
            if (allowErrorRoute) {
                return update;
            }
            throw new IllegalStateException(ex.getMessage(), ex);
        }
    }

    private Map<String, Object> executeVariableAssign(LangGraphState state, GraphSpec.Node node) {
        Map<String, Object> config = safeMap(node.getConfig());
        Map<String, Object> assigned = new LinkedHashMap<>();
        Object rawAssignments = config.get(CONFIG_ASSIGNMENTS);
        if (rawAssignments instanceof Map<?, ?> assignments) {
            assignments.forEach((target, expression) -> {
                String key = asString(target);
                if (key.isBlank()) {
                    return;
                }
                assigned.put(key, resolveExpression(state, String.valueOf(expression)));
            });
        }
        Map<String, Object> update = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : assigned.entrySet()) {
            update.put(entry.getKey(), entry.getValue());
            update.put(VAR_PREFIX + entry.getKey(), entry.getValue());
        }
        update.put(LAST_OUTPUT, assigned);
        update.put(LAST_SUCCESS, true);
        update.put(LAST_ERROR, "");
        update.put(nodeOutputKey(node.getId()), assigned);
        putOutputAlias(update, node, assigned);
        return update;
    }

    private Map<String, Object> executeTemplate(LangGraphState state, GraphSpec.Node node) {
        Map<String, Object> config = safeMap(node.getConfig());
        String template = asString(config.get(CONFIG_TEMPLATE));
        String rendered = renderTemplate(state, template);
        Map<String, Object> update = new LinkedHashMap<>();
        update.put(LAST_OUTPUT, rendered);
        update.put(LAST_SUCCESS, true);
        update.put(LAST_ERROR, "");
        update.put(nodeOutputKey(node.getId()), rendered);
        if (Boolean.TRUE.equals(config.get(CONFIG_WRITE_TO_ANSWER)) || !config.containsKey(CONFIG_WRITE_TO_ANSWER)) {
            update.put(ANSWER, rendered);
        }
        putOutputAlias(update, node, rendered);
        return update;
    }

    private Map<String, Object> executeAnswer(LangGraphState state, GraphSpec.Node node) {
        Map<String, Object> config = safeMap(node.getConfig());
        String template = firstNonBlank(asString(config.get(CONFIG_TEMPLATE)), "{{ lastOutput }}");
        String rendered = renderTemplate(state, template);
        Map<String, Object> update = new LinkedHashMap<>();
        update.put(ANSWER, rendered);
        update.put(LAST_OUTPUT, rendered);
        update.put(LAST_SUCCESS, true);
        update.put(LAST_ERROR, "");
        update.put(nodeOutputKey(node.getId()), rendered);
        putOutputAlias(update, node, rendered);
        return update;
    }

    private Map<String, Object> executeCode(LangGraphState state, GraphSpec.Node node) {
        Map<String, Object> config = safeMap(node.getConfig());
        Map<String, Object> outputs = resolveOutputMap(state, config.get(CONFIG_OUTPUTS));
        Map<String, Object> update = new LinkedHashMap<>();
        update.put(LAST_OUTPUT, outputs);
        update.put(LAST_SUCCESS, true);
        update.put(LAST_ERROR, "");
        update.put(nodeOutputKey(node.getId()), outputs);
        putOutputAlias(update, node, outputs);
        return update;
    }

    private Map<String, Object> executeUserInput(LangGraphState state, GraphSpec.Node node) {
        Map<String, Object> config = safeMap(node.getConfig());
        Map<String, Object> incoming = asMap(state.value(REQUEST_PARAMS).orElse(Map.of()));
        Map<String, Object> params = new LinkedHashMap<>();
        for (Map<String, Object> field : fieldList(config.get(CONFIG_FIELDS))) {
            String name = asString(field.get("name"));
            if (name.isBlank()) {
                continue;
            }
            Object value = incoming.get(name);
            if (isBlankValue(value) && field.containsKey("defaultValue")) {
                value = field.get("defaultValue");
            }
            if (Boolean.TRUE.equals(field.get("required")) && isBlankValue(value)) {
                throw new IllegalArgumentException("Required user input field is missing: " + name);
            }
            params.put(name, coerceFieldValue(value, asString(field.get("type"))));
        }
        incoming.forEach(params::putIfAbsent);

        Map<String, Object> update = new LinkedHashMap<>();
        update.put("params", params);
        update.put(LAST_OUTPUT, params);
        update.put(LAST_SUCCESS, true);
        update.put(LAST_ERROR, "");
        update.put(nodeOutputKey(node.getId()), params);
        putOutputAlias(update, node, params);
        return update;
    }

    private Map<String, Object> executeInteraction(LangGraphState state, GraphSpec.Node node) {
        Map<String, Object> config = safeMap(node.getConfig());
        String interactionType = firstNonBlank(asString(config.get(CONFIG_INTERACTION_TYPE)), "COLLECT_INPUT").toUpperCase();
        String component = firstNonBlank(asString(config.get(CONFIG_COMPONENT)), "FORM").toUpperCase();
        String title = firstNonBlank(asString(config.get("title")), asString(node.getName()), asString(node.getId()));

        Object output;
        if ("PRESENT_OUTPUT".equals(interactionType)) {
            Map<String, Object> renderSchema = new LinkedHashMap<>(safeMap(asMap(config.get(CONFIG_RENDER_SCHEMA))));
            String rendererKey = nullToEmpty(firstNonBlank(asString(config.get("rendererKey")), asString(renderSchema.get("rendererKey"))));
            if (!rendererKey.isBlank()) {
                renderSchema.put("rendererKey", rendererKey);
            }
            Map<String, Object> presented = new LinkedHashMap<>();
            presented.put("interactionType", interactionType);
            presented.put("component", component);
            presented.put("title", title);
            presented.put("data", resolveExpression(state, firstNonBlank(asString(config.get(CONFIG_DATA_EXPRESSION)), LAST_OUTPUT)));
            presented.put("renderSchema", renderSchema);
            presented.put("dataSources", safeMap(asMap(config.get(CONFIG_DATA_SOURCES))));
            output = presented;
        } else {
            output = collectInteractionInput(state, node, config, interactionType, component, title);
        }

        Map<String, Object> update = new LinkedHashMap<>();
        update.put(LAST_OUTPUT, output);
        update.put(LAST_SUCCESS, true);
        update.put(LAST_ERROR, "");
        update.put(nodeOutputKey(node.getId()), output);
        putOutputAlias(update, node, output);
        return update;
    }

    private Map<String, Object> executePageAction(LangGraphState state, GraphSpec.Node node) {
        Map<String, Object> config = safeMap(node.getConfig());
        String actionKey = firstNonBlank(asString(config.get("actionKey")), asString(config.get("action")));
        if (actionKey == null || actionKey.isBlank()) {
            throw new IllegalArgumentException("PAGE_ACTION node requires actionKey: " + node.getId());
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("type", "page.action.requested");
        request.put("requestId", "page-action-" + node.getId() + "-" + UUID.randomUUID());
        request.put("actionKey", actionKey);
        String pageInstanceId = asString(safeMap(asMap(state.value("sys").orElse(Map.of()))).get("pageInstanceId"));
        if (pageInstanceId != null && !pageInstanceId.isBlank()) {
            request.put("target", Map.of("pageInstanceId", pageInstanceId));
        }
        request.put("title", firstNonBlank(asString(config.get("title")), asString(node.getName()), actionKey));
        request.put("nodeId", node.getId());
        request.put("confirm", Boolean.TRUE.equals(config.get("confirm")));
        request.put("args", resolveConfiguredMap(state, config.get(CONFIG_ARGS)));
        Map<String, Object> metadata = safeMap(asMap(config.get("metadata")));
        if (!metadata.isEmpty()) {
            request.put("metadata", metadata);
        }

        Map<String, Object> update = new LinkedHashMap<>();
        update.put(LAST_OUTPUT, request);
        update.put(LAST_SUCCESS, true);
        update.put(LAST_ERROR, "");
        update.put(nodeOutputKey(node.getId()), request);
        putOutputAlias(update, node, request);
        return update;
    }

    private Map<String, Object> collectInteractionInput(LangGraphState state,
                                                        GraphSpec.Node node,
                                                        Map<String, Object> config,
                                                        String interactionType,
                                                        String component,
                                                        String title) {
        Map<String, Object> incoming = asMap(state.value(REQUEST_PARAMS).orElse(Map.of()));
        Map<String, Object> collected = new LinkedHashMap<>();
        Map<String, Object> targetArgs = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> missingLabels = new ArrayList<>();
        List<String> confirmationLabels = new ArrayList<>();
        List<UiFieldPayload> uiFields = new ArrayList<>();
        List<Map<String, Object>> fields = fieldList(config.get(CONFIG_FIELDS));
        SlotFillingService.SlotFillingOutcome slotOutcome = new SlotFillingService(modelServiceClient, objectMapper)
                .fill(new SlotFillingService.SlotFillingRequest(
                        stringify(state.value(INPUT).orElse("")),
                        incoming,
                        fields,
                        firstNonBlank(asString(config.get(MODEL_INSTANCE_ID)), state.value(MODEL_INSTANCE_ID, ""))));
        for (Map<String, Object> field : fields) {
            String name = fieldName(field);
            if (name.isBlank()) {
                continue;
            }
            String targetPath = targetPathOrName(field, name);
            Object value = slotOutcome.values().get(name);
            if (slotOutcome.confirmationRequired().contains(name)) {
                confirmationLabels.add(firstNonBlank(asString(field.get("label")), asString(field.get("description")), name));
            } else if (Boolean.TRUE.equals(field.get("required")) && isBlankValue(value)) {
                missing.add(name);
                missingLabels.add(firstNonBlank(asString(field.get("label")), asString(field.get("description")), name));
            } else {
                Object coerced = coerceFieldValue(value, asString(field.get("type")));
                collected.put(name, coerced);
                putMappedArg(targetArgs, targetPath, coerced);
            }
            uiFields.add(UiFieldPayload.builder()
                    .key(name)
                    .label(firstNonBlank(asString(field.get("label")), asString(field.get("description")), name))
                    .type(firstNonBlank(asString(field.get("type")), "string"))
                    .required(Boolean.TRUE.equals(field.get("required")))
                    .targetPath(targetPath)
                    .build());
        }
        incoming.forEach(collected::putIfAbsent);
        if (!missing.isEmpty() || !confirmationLabels.isEmpty()) {
            List<String> waitingKeys = new ArrayList<>(missing);
            waitingKeys.addAll(slotOutcome.confirmationRequired());
            Map<String, Object> prefilled = new LinkedHashMap<>(collected);
            prefilled.putAll(slotOutcome.prefilled());
            String message = confirmationLabels.isEmpty()
                    ? friendlyInteractionMessage(missingLabels)
                    : friendlyConfirmationMessage(confirmationLabels);
            UiRequestPayload payload = UiRequestPayload.builder()
                    .component(interactionComponent(interactionType, component))
                    .interactionId(node.getId() + "-" + UUID.randomUUID())
                    .title(title)
                    .ttlSeconds((int) longValue(safeMap(asMap(config.get(CONFIG_BEHAVIOR))).get("ttlSeconds"), 600L))
                    .fields(uiFields)
                    .prefilled(prefilled)
                    .missing(waitingKeys)
                    .message(firstNonBlank(asString(config.get("message")), message, title))
                    .build();
            throw new InteractionSuspendedException(payload, message);
        }
        Map<String, Object> output = new LinkedHashMap<>(collected);
        output.put("fields", new LinkedHashMap<>(collected));
        output.put("targetArgs", targetArgs);
        output.put("slots", slotSummaries(slotOutcome));
        output.put("missing", List.of());
        return output;
    }

    private Map<String, Object> slotSummaries(SlotFillingService.SlotFillingOutcome outcome) {
        Map<String, Object> slots = new LinkedHashMap<>();
        outcome.slots().forEach((key, value) -> slots.put(key, value.toMap()));
        return slots;
    }

    private Object resolveInteractionFieldValue(LangGraphState state,
                                                Map<String, Object> incoming,
                                                Map<String, Object> config,
                                                Map<String, Object> field,
                                                String name,
                                                String targetPath) {
        Object value = incoming.get(name);
        if (isBlankValue(value) && !targetPath.equals(name)) {
            value = incoming.get(targetPath);
        }
        if (isBlankValue(value) && targetPath.contains(".")) {
            value = incoming.get(targetPath.replace(".", "_"));
        }
        if (isBlankValue(value) && targetPath.contains(".")) {
            value = traverse(incoming, targetPath.split("\\."), 0, null);
        }
        if (isBlankValue(value) && field.containsKey("defaultValue")) {
            value = field.get("defaultValue");
        }
        if (isBlankValue(value) && interactionHeuristicExtractionEnabled(config, field)) {
            value = extractSimpleInteractionValue(state, field, name, targetPath);
        }
        return value;
    }

    private boolean interactionHeuristicExtractionEnabled(Map<String, Object> config, Map<String, Object> field) {
        if (Boolean.TRUE.equals(field.get("allowHeuristicExtraction"))) {
            return true;
        }
        Map<String, Object> behavior = safeMap(asMap(config.get(CONFIG_BEHAVIOR)));
        return Boolean.TRUE.equals(behavior.get("allowHeuristicExtraction"));
    }

    private Object extractSimpleInteractionValue(LangGraphState state,
                                                 Map<String, Object> field,
                                                 String name,
                                                 String targetPath) {
        String type = asString(field.get("type"));
        if (!type.isBlank() && !"string".equalsIgnoreCase(type)) {
            return null;
        }
        String message = stringify(state.value(INPUT).orElse(""));
        if (message.isBlank()) {
            return null;
        }
        String label = firstNonBlank(asString(field.get("label")), asString(field.get("description")), name);
        String signal = (name + " " + targetPath + " " + label).toLowerCase();
        if (label.contains("班组") || signal.contains("team")) {
            String candidate = firstPatternGroup(message,
                    "(.{1,40}?)(?:的)?班组(?:名称|信息|数据|详情)?",
                    "班组(?:名称)?(?:是|为|叫|:|：)?\\s*([^，。,.\\s]+)");
            return cleanExtractedSlot(candidate);
        }
        if (label.contains("名称") || signal.contains("name")) {
            String candidate = firstPatternGroup(message,
                    "(?:名称|名字|name)(?:是|为|叫|=|:|：)\\s*([^，。,.\\s]+)",
                    "(?:叫|是)\\s*([^，。,.\\s]+)");
            return cleanExtractedSlot(candidate);
        }
        return null;
    }

    private String firstPatternGroup(String text, String... patterns) {
        for (String pattern : patterns) {
            Matcher matcher = Pattern.compile(pattern).matcher(text);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private String cleanExtractedSlot(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isBlank()) {
            return null;
        }
        text = text.replaceFirst("^(请|帮我|麻烦|查询一下|查一下|查询|查|获取|看一下|看看|一下|我要|我想|想要)\\s*", "");
        text = text.replaceFirst("(的信息|的信息查询|信息|数据|详情)$", "");
        text = text.replaceAll("^[，。,.\\s]+|[，。,.\\s]+$", "");
        return text.isBlank() ? null : text;
    }

    private String targetPathOrName(Map<String, Object> field, String name) {
        return firstNonBlank(asString(field.get("targetPath")), asString(field.get("target")), name);
    }

    private String friendlyInteractionMessage(List<String> missingLabels) {
        List<String> labels = missingLabels.stream()
                .filter(label -> label != null && !label.isBlank())
                .distinct()
                .toList();
        return labels.isEmpty() ? "请补充必要信息" : "请补充：" + String.join("、", labels);
    }

    private String friendlyConfirmationMessage(List<String> confirmationLabels) {
        List<String> labels = confirmationLabels.stream()
                .filter(label -> label != null && !label.isBlank())
                .distinct()
                .toList();
        return labels.isEmpty() ? "请确认抽取结果" : "请确认：" + String.join("、", labels);
    }

    private Map<String, Object> executeIntentClassifier(LangGraphState state, GraphSpec.Node node) {
        Map<String, Object> config = safeMap(node.getConfig());
        String inputExpression = firstNonBlank(asString(config.get(CONFIG_INPUT_EXPRESSION)), INPUT);
        String input = stringify(resolveExpression(state, inputExpression)).toLowerCase();
        String route = firstNonBlank(asString(config.get(CONFIG_DEFAULT_ROUTE)), "else");
        Object rawClasses = config.get(CONFIG_CLASSES);
        if (rawClasses instanceof List<?> classes) {
            for (Object raw : classes) {
                Map<String, Object> item = asMap(raw);
                String id = asString(item.get("id"));
                if (id.isBlank()) {
                    continue;
                }
                Object rawKeywords = item.get(CONFIG_KEYWORDS);
                if (rawKeywords instanceof List<?> keywords && keywords.stream()
                        .map(LangGraph4jRuntimeAdapter::asString)
                        .filter(keyword -> !keyword.isBlank())
                        .anyMatch(keyword -> input.contains(keyword.toLowerCase()))) {
                    route = id;
                    break;
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("route", route);
        result.put("input", input);
        Map<String, Object> update = new LinkedHashMap<>();
        update.put(LAST_OUTPUT, result);
        update.put(LAST_SUCCESS, true);
        update.put(LAST_ERROR, "");
        update.put(LAST_ROUTE, route);
        update.put(nodeOutputKey(node.getId()), result);
        putOutputAlias(update, node, result);
        return update;
    }

    private Map<String, Object> executeVariableAggregator(LangGraphState state, GraphSpec.Node node) {
        Map<String, Object> config = safeMap(node.getConfig());
        List<Map<String, Object>> items = fieldList(config.get(CONFIG_ITEMS));
        Map<String, Object> object = new LinkedHashMap<>();
        List<Object> array = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String name = asString(item.get("name"));
            String source = firstNonBlank(asString(item.get("source")), name);
            Object value = resolveExpression(state, source);
            if (!name.isBlank()) {
                object.put(name, value);
            }
            array.add(value);
        }
        String mode = firstNonBlank(asString(config.get(CONFIG_AGGREGATE_MODE)), "object").toLowerCase();
        Object result;
        if ("array".equals(mode)) {
            result = array;
        } else if ("text".equals(mode)) {
            Map<String, Object> merged = new LinkedHashMap<>(state.data());
            object.forEach((key, value) -> {
                merged.put(key, value);
                merged.put(VAR_PREFIX + key, value);
            });
            result = renderTemplate(new LangGraphState(merged), firstNonBlank(asString(config.get(CONFIG_TEMPLATE)), "{{ lastOutput }}"));
        } else {
            result = object;
        }
        Map<String, Object> update = new LinkedHashMap<>();
        update.put(LAST_OUTPUT, result);
        update.put(LAST_SUCCESS, true);
        update.put(LAST_ERROR, "");
        update.put(nodeOutputKey(node.getId()), result);
        putOutputAlias(update, node, result);
        return update;
    }

    private Map<String, Object> executeHumanApproval(LangGraphState state,
                                                     GraphRuntimeContext runtimeContext,
                                                     GraphSpec graphSpec,
                                                     GraphSpec.Node node) {
        Map<String, Object> config = safeMap(node.getConfig());
        String prompt = renderTemplate(state, firstNonBlank(asString(config.get("prompt")), "{{ lastOutput }}"));
        String route = firstNonBlank(asString(config.get(CONFIG_DEFAULT_ROUTE)), "approved");
        if (skillInteractionMapper != null && !Boolean.TRUE.equals(config.get("autoApprove"))) {
            suspendHumanApproval(state, runtimeContext, graphSpec, node, config, prompt);
        }
        Map<String, Object> approval = new LinkedHashMap<>();
        approval.put("status", route);
        approval.put("title", firstNonBlank(asString(config.get("title")), "人工确认"));
        approval.put("prompt", prompt);
        approval.put("approvers", config.getOrDefault(CONFIG_APPROVERS, List.of()));
        approval.put("timeoutSeconds", longValue(config.get(CONFIG_TIMEOUT_SECONDS), 3600L));
        approval.put("interactive", true);
        Map<String, Object> update = new LinkedHashMap<>();
        update.put(LAST_OUTPUT, approval);
        update.put(LAST_SUCCESS, true);
        update.put(LAST_ERROR, "");
        update.put(LAST_ROUTE, route);
        update.put(nodeOutputKey(node.getId()), approval);
        putOutputAlias(update, node, approval);
        return update;
    }

    private void suspendHumanApproval(LangGraphState state,
                                      GraphRuntimeContext runtimeContext,
                                      GraphSpec graphSpec,
                                      GraphSpec.Node node,
                                      Map<String, Object> config,
                                      String prompt) {
        String interactionId = UUID.randomUUID().toString().replace("-", "");
        long ttlSeconds = Math.max(60L, longValue(config.get(CONFIG_TIMEOUT_SECONDS), 3600L));
        LocalDateTime now = LocalDateTime.now();
        String title = firstNonBlank(asString(config.get("title")), "人工确认");
        UiRequestPayload payload = UiRequestPayload.builder()
                .component("confirm")
                .interactionId(interactionId)
                .traceId(asString(state.value("traceId").orElse(null)))
                .skillName(HumanApprovalResumeService.SKILL_PREFIX + node.getId())
                .title(title)
                .ttlSeconds((int) Math.min(ttlSeconds, Integer.MAX_VALUE))
                .message(prompt)
                .summary(Map.of(
                        "nodeId", node.getId(),
                        "approvers", config.getOrDefault(CONFIG_APPROVERS, List.of()),
                        "defaultRoute", firstNonBlank(asString(config.get(CONFIG_DEFAULT_ROUTE)), "approved")))
                .build();

        SkillInteractionEntity row = new SkillInteractionEntity();
        row.setId(interactionId);
        row.setTraceId(payload.getTraceId());
        row.setSessionId(asString(state.value("sessionId").orElse(null)));
        row.setUserId(asString(state.value("userId").orElse(null)));
        row.setAgentId(longValue(state.value("agentId").orElse(null), 0L));
        row.setSkillName(HumanApprovalResumeService.SKILL_PREFIX + node.getId());
        row.setStatus(SkillInteractionStatus.PENDING);
        row.setSlotState(writeJson(buildApprovalResumeDocument(state, runtimeContext, graphSpec, node, prompt)));
        row.setPendingKeys(writeJson(List.of("decision")));
        row.setUiPayload(writeJson(payload));
        row.setSpecSnapshot(writeJson(Map.of("nodeId", node.getId(), "config", config)));
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setExpiresAt(now.plusSeconds(ttlSeconds));
        skillInteractionMapper.insert(row);
        throw new InteractionSuspendedException(payload, "流程已暂停，等待人工审批");
    }

    private Map<String, Object> buildApprovalResumeDocument(LangGraphState state,
                                                             GraphRuntimeContext runtimeContext,
                                                             GraphSpec graphSpec,
                                                             GraphSpec.Node node,
                                                             String prompt) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("phase", "approval");
        document.put("nodeId", node.getId());
        document.put("prompt", prompt);
        document.put("state", state.data());
        if (runtimeContext != null && graphSpec != null) {
            document.put("graphSpec", graphSpec);
            document.put("runtimeContext", runtimeContext);
        }
        return document;
    }

    private Map<String, Object> executeLoop(LangGraphState state, GraphSpec.Node node) {
        Map<String, Object> config = safeMap(node.getConfig());
        String loopKey = firstNonBlank(asString(config.get(CONFIG_LOOP_KEY)), node.getId());
        String stateKey = VAR_PREFIX + loopKey + ".iteration";
        int current = (int) longValue(state.value(stateKey).orElse(0), 0L);
        int next = current + 1;
        int max = Math.max(1, (int) longValue(config.get(CONFIG_MAX_ITERATIONS), 1L));
        Object itemSource = String.valueOf(config.getOrDefault(CONFIG_ITEM_EXPRESSION, "")).isBlank()
                ? state.value(LAST_OUTPUT).orElse(null)
                : resolveExpression(state, asString(config.get(CONFIG_ITEM_EXPRESSION)));
        boolean breakHit = false;
        String breakCondition = asString(config.get(CONFIG_BREAK_CONDITION));
        if (!breakCondition.isBlank()) {
            breakHit = matchesCondition(state, breakCondition);
        }
        String route = breakHit || next >= max ? "done" : "continue";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("loopKey", loopKey);
        result.put("iteration", next);
        result.put("maxIterations", max);
        result.put("route", route);
        result.put("item", itemSource);
        Map<String, Object> update = new LinkedHashMap<>();
        update.put(stateKey, next);
        update.put(VAR_PREFIX + loopKey, result);
        update.put(LAST_OUTPUT, result);
        update.put(LAST_SUCCESS, true);
        update.put(LAST_ERROR, "");
        update.put(LAST_ROUTE, route);
        update.put(nodeOutputKey(node.getId()), result);
        putOutputAlias(update, node, result);
        return update;
    }

    private Map<String, Object> executeKnowledgeWrite(LangGraphState state, GraphSpec.Node node) {
        Map<String, Object> config = safeMap(node.getConfig());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("knowledgeBaseCode", asString(config.get(CONFIG_KNOWLEDGE_BASE_CODE)));
        payload.put("title", stringify(resolveExpression(state, firstNonBlank(asString(config.get(CONFIG_TITLE_EXPRESSION)), "const:工作流写入"))));
        payload.put("content", stringify(resolveExpression(state, firstNonBlank(asString(config.get(CONFIG_CONTENT_EXPRESSION)), LAST_OUTPUT))));
        payload.put("tags", config.getOrDefault(CONFIG_TAGS, List.of()));
        payload.put("mode", firstNonBlank(asString(config.get(CONFIG_WRITE_MODE)), "draft"));
        payload.put("status", "prepared");
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("pendingKnowledgeWrite", payload);
        update.put(LAST_OUTPUT, payload);
        update.put(LAST_SUCCESS, true);
        update.put(LAST_ERROR, "");
        update.put(nodeOutputKey(node.getId()), payload);
        putOutputAlias(update, node, payload);
        return update;
    }

    private Map<String, Object> executeKnowledgeWritePersisted(LangGraphState state, GraphSpec.Node node) {
        Map<String, Object> config = safeMap(node.getConfig());
        String knowledgeBaseCode = asString(config.get(CONFIG_KNOWLEDGE_BASE_CODE));
        String title = stringify(resolveExpression(state, firstNonBlank(asString(config.get(CONFIG_TITLE_EXPRESSION)), "const:workflow-write")));
        String content = stringify(resolveExpression(state, firstNonBlank(asString(config.get(CONFIG_CONTENT_EXPRESSION)), LAST_OUTPUT)));
        if (knowledgeBaseCode.isBlank()) {
            throw new IllegalArgumentException("KNOWLEDGE_WRITE knowledgeBaseCode is required");
        }
        if (content.isBlank()) {
            throw new IllegalArgumentException("KNOWLEDGE_WRITE contentExpression resolved empty content");
        }

        String fileId = knowledgeWriteFileId(node, title, content);
        String fileName = sanitizeKnowledgeFileName(title);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("knowledgeBaseCode", knowledgeBaseCode);
        payload.put("title", title);
        payload.put("content", content);
        payload.put("tags", config.getOrDefault(CONFIG_TAGS, List.of()));
        payload.put("mode", firstNonBlank(asString(config.get(CONFIG_WRITE_MODE)), "draft"));
        payload.put("fileId", fileId);
        payload.put("fileName", fileName);
        payload.put("chunks", List.of(content));
        payload.put("status", "prepared");
        if (skillsServiceClient != null) {
            SkillsServiceClient.KnowledgeImportResult result = skillsServiceClient.importKnowledgeChunks(
                    SkillsServiceClient.KnowledgeImportRequest.builder()
                            .knowledgeBaseCode(knowledgeBaseCode)
                            .fileId(fileId)
                            .fileName(fileName)
                            .chunks(List.of(content))
                            .build());
            if (result == null || result.getCode() != SUCCESS_CODE) {
                throw new IllegalStateException("Knowledge write failed: "
                        + (result == null ? "empty response" : firstNonBlank(result.getMessage(), "code=" + result.getCode())));
            }
            payload.put("status", "persisted");
            payload.put("message", result.getMessage());
        }

        Map<String, Object> update = new LinkedHashMap<>();
        update.put("knowledgeWrite", payload);
        update.put("pendingKnowledgeWrite", payload);
        update.put(LAST_OUTPUT, payload);
        update.put(LAST_SUCCESS, true);
        update.put(LAST_ERROR, "");
        update.put(nodeOutputKey(node.getId()), payload);
        putOutputAlias(update, node, payload);
        return update;
    }

    private String knowledgeWriteFileId(GraphSpec.Node node, String title, String content) {
        String seed = firstNonBlank(node == null ? "" : node.getId(), "knowledge") + "|" + title + "|" + content;
        return "agent-write-" + Integer.toUnsignedString(seed.hashCode(), 36);
    }

    private String sanitizeKnowledgeFileName(String title) {
        String value = firstNonBlank(title, "agent-workflow-write").trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!value.endsWith(".md") && !value.endsWith(".txt")) {
            value = value + ".md";
        }
        return value.length() > 120 ? value.substring(0, 116) + ".md" : value;
    }

    private Map<String, Object> executeDocumentExtract(LangGraphState state, GraphSpec.Node node) {
        Map<String, Object> config = safeMap(node.getConfig());
        String text = stringify(resolveExpression(state, firstNonBlank(asString(config.get(CONFIG_SOURCE_EXPRESSION)), LAST_OUTPUT)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", firstNonBlank(asString(config.get(CONFIG_FORMAT)), "text"));
        result.put("text", text);
        result.put("length", text.length());
        for (Map<String, Object> field : fieldList(config.get(CONFIG_FIELDS))) {
            String name = asString(field.get("name"));
            if (name.isBlank()) {
                continue;
            }
            String source = asString(field.get("source"));
            result.put(name, source.startsWith("regex:")
                    ? firstRegexGroup(text, source.substring("regex:".length()))
                    : source.isBlank() ? "" : resolveExpression(state, source));
        }
        Map<String, Object> update = new LinkedHashMap<>();
        update.put(LAST_OUTPUT, result);
        update.put(LAST_SUCCESS, true);
        update.put(LAST_ERROR, "");
        update.put(nodeOutputKey(node.getId()), result);
        putOutputAlias(update, node, result);
        return update;
    }

    private Map<String, Object> executeMcpCall(LangGraphState state, GraphSpec.Node node) {
        Map<String, Object> config = safeMap(node.getConfig());
        String toolName = firstNonBlank(asString(config.get(CONFIG_TOOL_NAME)), asString(config.get("name")));
        if (toolName.isBlank()) {
            throw new IllegalArgumentException("MCP toolName is required");
        }
        Map<String, Object> args = resolveToolArgs(state, node);
        args.put("mcpServerRef", asString(config.get(CONFIG_SERVER_REF)));
        Object result = toolDefinitionService.executeTool(toolName, args);
        Map<String, Object> update = new LinkedHashMap<>();
        update.put(LAST_OUTPUT, result);
        update.put(LAST_SUCCESS, true);
        update.put(LAST_ERROR, "");
        update.put(nodeOutputKey(node.getId()), result);
        putOutputAlias(update, node, result);
        return update;
    }

    private Map<String, Object> executeIfElse(LangGraphState state, GraphSpec.Node node) {
        Map<String, Object> config = safeMap(node.getConfig());
        Object lastOutput = state.value(LAST_OUTPUT).orElse(state.value(ANSWER).orElse(null));
        String route = selectConditionRoute(state, config);
        Map<String, Object> update = new LinkedHashMap<>();
        update.put(LAST_OUTPUT, lastOutput);
        update.put(LAST_SUCCESS, Boolean.TRUE.equals(state.value(LAST_SUCCESS).orElse(Boolean.TRUE)));
        update.put(LAST_ERROR, state.value(LAST_ERROR, ""));
        update.put(LAST_ROUTE, route);
        update.put(nodeOutputKey(node.getId()), lastOutput);
        putOutputAlias(update, node, lastOutput);
        return update;
    }

    private Map<String, Object> executeParameterExtract(LangGraphState state, GraphSpec.Node node) {
        Map<String, Object> config = safeMap(node.getConfig());
        String mode = firstNonBlank(asString(config.get(CONFIG_EXTRACT_MODE)), "expression").toLowerCase();
        List<Map<String, Object>> fields = fieldConfigs(config);
        Map<String, Object> extracted = "llm".equals(mode)
                ? extractParametersWithLlm(state, config, fields)
                : extractParametersByExpression(state, config, fields);
        validateExtractedFields(fields, extracted);
        Map<String, Object> update = new LinkedHashMap<>();
        update.put(LAST_OUTPUT, extracted);
        update.put(LAST_SUCCESS, true);
        update.put(LAST_ERROR, "");
        update.put(nodeOutputKey(node.getId()), extracted);
        putOutputAlias(update, node, extracted);
        return update;
    }

    private Map<String, Object> executeKnowledgeRetrieval(LangGraphState state, GraphSpec.Node node) {
        Map<String, Object> config = safeMap(node.getConfig());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("input", state.value(INPUT, ""));
        args.put("query", stringify(resolveExpression(state, firstNonBlank(asString(config.get(CONFIG_QUERY)), "input"))));
        putIfPresent(args, "knowledgeBaseCodes", config.get(CONFIG_KNOWLEDGE_BASE_CODES));
        putIfText(args, "knowledgeBaseGroupId", config.get(CONFIG_KNOWLEDGE_BASE_GROUP_ID));
        putIfText(args, "searchMode", config.get(CONFIG_SEARCH_MODE));
        putIfPresent(args, "topK", config.get(CONFIG_TOP_K));
        putIfPresent(args, "rerankEnabled", config.get(CONFIG_RERANK_ENABLED));
        putIfPresent(args, "similarityThreshold", config.get(CONFIG_SIMILARITY_THRESHOLD));
        putIfPresent(args, "directReturnEnabled", config.get(CONFIG_DIRECT_RETURN_ENABLED));
        putIfPresent(args, "directReturnThreshold", config.get(CONFIG_DIRECT_RETURN_THRESHOLD));
        Object result = toolDefinitionService.executeTool(TOOL_SEARCH_KNOWLEDGE, args);
        Map<String, Object> update = new LinkedHashMap<>();
        update.put(LAST_OUTPUT, result);
        update.put(LAST_SUCCESS, true);
        update.put(LAST_ERROR, "");
        update.put(nodeOutputKey(node.getId()), result);
        putOutputAlias(update, node, result);
        return update;
    }

    private Map<String, Object> executeHttpRequest(LangGraphState state,
                                                   GraphRuntimeContext runtimeContext,
                                                   GraphSpec.Node node) throws Exception {
        Map<String, Object> config = safeMap(node.getConfig());
        String method = firstNonBlank(asString(config.get(CONFIG_METHOD)), "GET").toUpperCase();
        String url = appendQueryParams(renderTemplate(state, asString(config.get(CONFIG_URL))), config.get(CONFIG_QUERY_PARAMS), state);
        url = appendQueryParams(url, credentialQueryParams(config, runtimeContext), state);
        if (url.isBlank()) {
            throw new IllegalArgumentException("HTTP_REQUEST node requires url: " + node.getId());
        }
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(longValue(config.get(CONFIG_TIMEOUT_MS), 30000L)));
        Object headers = config.get(CONFIG_HEADERS);
        if (headers instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                String headerName = asString(key);
                if (!headerName.isBlank()) {
                    request.header(headerName, renderTemplate(state, String.valueOf(value)));
                }
            });
        }
        applyCredential(request, config, runtimeContext, state);
        String bodyType = firstNonBlank(asString(config.get(CONFIG_BODY_TYPE)), "json").toLowerCase();
        String body = renderTemplate(state, asString(config.get(CONFIG_BODY)));
        if ("GET".equals(method) || "DELETE".equals(method)) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            if ("json".equals(bodyType) && !hasHeader(config.get(CONFIG_HEADERS), "Content-Type")) {
                request.header("Content-Type", "application/json");
            }
            request.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", response.statusCode());
        result.put("body", response.body());
        result.put("headers", response.headers().map());
        Map<String, Object> update = new LinkedHashMap<>();
        update.put(LAST_OUTPUT, result);
        update.put(LAST_SUCCESS, response.statusCode() >= 200 && response.statusCode() < 400);
        update.put(LAST_ERROR, response.statusCode() >= 400 ? "HTTP " + response.statusCode() : "");
        update.put(nodeOutputKey(node.getId()), result);
        putOutputAlias(update, node, result);
        return update;
    }

    private String selectConditionRoute(LangGraphState state, Map<String, Object> config) {
        Object rawGroups = config.get(CONFIG_CONDITION_GROUPS);
        if (rawGroups instanceof List<?> groups) {
            for (Object rawGroup : groups) {
                Map<String, Object> group = asMap(rawGroup);
                String groupId = asString(group.get("id"));
                if (!groupId.isBlank() && matchesConditionGroup(state, group)) {
                    return groupId;
                }
            }
        }
        return firstNonBlank(asString(config.get(CONFIG_DEFAULT_ROUTE)), "else");
    }

    private boolean matchesConditionGroup(LangGraphState state, Map<String, Object> group) {
        Object rawConditions = group.get("conditions");
        if (!(rawConditions instanceof List<?> conditions) || conditions.isEmpty()) {
            return false;
        }
        boolean andLogic = !"OR".equalsIgnoreCase(asString(group.get("logic")));
        boolean matchedAny = false;
        for (Object rawCondition : conditions) {
            Map<String, Object> condition = asMap(rawCondition);
            boolean matched = matchesStructuredCondition(state, condition);
            matchedAny = matchedAny || matched;
            if (andLogic && !matched) {
                return false;
            }
            if (!andLogic && matched) {
                return true;
            }
        }
        return andLogic ? matchedAny : false;
    }

    private boolean matchesStructuredCondition(LangGraphState state, Map<String, Object> condition) {
        Object left = resolveExpression(state, asString(condition.get("left")));
        String operator = firstNonBlank(asString(condition.get("operator")), "exists").toLowerCase();
        Object right = condition.containsKey("right")
                ? resolveExpression(state, asString(condition.get("right")))
                : "";
        String leftText = stringify(left);
        String rightText = stringify(right);
        return switch (operator) {
            case "eq", "equals" -> leftText.equals(rightText);
            case "neq", "not_equals" -> !leftText.equals(rightText);
            case "contains" -> leftText.contains(rightText);
            case "not_contains" -> !leftText.contains(rightText);
            case "empty" -> leftText.isBlank();
            case "not_empty", "exists" -> !leftText.isBlank();
            case "gt" -> doubleValue(left, 0D) > doubleValue(right, 0D);
            case "gte" -> doubleValue(left, 0D) >= doubleValue(right, 0D);
            case "lt" -> doubleValue(left, 0D) < doubleValue(right, 0D);
            case "lte" -> doubleValue(left, 0D) <= doubleValue(right, 0D);
            default -> false;
        };
    }

    private List<Map<String, Object>> fieldConfigs(Map<String, Object> config) {
        Object rawFields = config.get(CONFIG_FIELDS);
        List<Map<String, Object>> fields = fieldList(rawFields);
        if (!fields.isEmpty()) {
            return fields;
        }
        Object rawParameters = config.get(CONFIG_PARAMETERS);
        if (rawParameters instanceof Map<?, ?> parameters) {
            List<Map<String, Object>> legacyFields = new ArrayList<>();
            parameters.forEach((name, expression) -> {
                String fieldName = asString(name);
                if (!fieldName.isBlank()) {
                    Map<String, Object> field = new LinkedHashMap<>();
                    field.put("name", fieldName);
                    field.put("type", "string");
                    field.put("source", String.valueOf(expression));
                    legacyFields.add(field);
                }
            });
            return legacyFields;
        }
        return List.of();
    }

    private static List<Map<String, Object>> fieldList(Object rawFields) {
        if (rawFields instanceof List<?> fields) {
            return fields.stream()
                    .map(LangGraph4jRuntimeAdapter::asMap)
                    .map(field -> {
                        Map<String, Object> normalized = new LinkedHashMap<>(field);
                        String name = fieldName(normalized);
                        if (!name.isBlank()) {
                            normalized.put("name", name);
                            normalized.putIfAbsent("key", name);
                        }
                        return normalized;
                    })
                    .filter(field -> !fieldName(field).isBlank())
                    .toList();
        }
        return List.of();
    }

    private static String fieldName(Map<String, Object> field) {
        return firstNonBlank(asString(field.get("key")), asString(field.get("name")));
    }

    private static String interactionComponent(String interactionType, String component) {
        if ("CONFIRM_ACTION".equals(interactionType)) {
            return "confirm";
        }
        if ("USER_CHOICE".equals(interactionType)) {
            return "select";
        }
        if ("PRESENT_OUTPUT".equals(interactionType)) {
            if ("TABLE".equalsIgnoreCase(component)) {
                return "table";
            }
            if ("DETAIL".equalsIgnoreCase(component)) {
                return "detail";
            }
            if ("CARD".equalsIgnoreCase(component)) {
                return "output_card";
            }
            if ("CUSTOM".equalsIgnoreCase(component)) {
                return "custom";
            }
            return "summary_card";
        }
        if ("REVIEW_EDIT".equals(interactionType)) {
            return "form";
        }
        if ("DETAIL".equals(component) || "CARD".equals(component) || "REPORT".equals(component)) {
            return "summary_card";
        }
        if ("TABLE".equals(component)) {
            return "summary_card";
        }
        return "form";
    }

    private UiRequestPayload uiRequestFromDebugOutput(Object rawOutput) {
        return uiRequestFromOutput(rawOutput);
    }

    private UiRequestPayload uiRequestFromOutput(Object rawOutput) {
        UiRequestPayload pageAction = uiRequestFromPageAction(rawOutput);
        return pageAction != null ? pageAction : uiRequestFromPresentOutput(rawOutput);
    }

    private UiRequestPayload uiRequestFromPageAction(Object rawOutput) {
        Map<String, Object> raw = safeMap(asMap(rawOutput));
        if (!"page.action.requested".equals(asString(raw.get("type")))) {
            return null;
        }
        Map<String, Object> extension = new LinkedHashMap<>();
        extension.put("eventType", "page.action.requested");
        extension.put("pageActionRequest", raw);
        return UiRequestPayload.builder()
                .component("page_action")
                .interactionId(asString(raw.get("requestId")))
                .title(firstNonBlank(asString(raw.get("title")), asString(raw.get("actionKey")), "Page action"))
                .message(firstNonBlank(asString(raw.get("message")),
                        "Page action requested: " + asString(raw.get("actionKey"))))
                .data(raw)
                .summary(Map.of(
                        "actionKey", asString(raw.get("actionKey")),
                        "confirm", Boolean.TRUE.equals(raw.get("confirm"))))
                .schema(Map.of("eventType", "page.action.requested"))
                .extension(extension)
                .build();
    }

    private UiRequestPayload uiRequestFromPresentOutput(Object rawOutput) {
        Map<String, Object> raw = safeMap(asMap(rawOutput));
        String interactionType = asString(raw.get("interactionType"));
        if (!"PRESENT_OUTPUT".equalsIgnoreCase(interactionType)) {
            return null;
        }
        String component = asString(raw.get("component"));
        Object data = raw.get("data");
        Map<String, Object> schema = safeMap(asMap(firstNonNull(raw.get("renderSchema"), raw.get("schema"))));
        String rendererKey = nullToEmpty(firstNonBlank(asString(raw.get("rendererKey")), asString(schema.get("rendererKey"))));
        Map<String, Object> extension = rendererKey.isBlank() ? Map.of() : Map.of("rendererKey", rendererKey);
        Map<String, Object> summary = new LinkedHashMap<>();
        if (data instanceof Map<?, ?> map) {
            map.forEach((key, value) -> summary.put(String.valueOf(key), value));
        } else if (data != null) {
            summary.put("value", data);
        }
        return UiRequestPayload.builder()
                .component(interactionComponent(interactionType.toUpperCase(), component.toUpperCase()))
                .title(firstNonBlank(asString(raw.get("title")), "输出节点"))
                .message(asString(raw.get("message")))
                .data(data)
                .schema(schema)
                .summary(summary)
                .datasources(safeMap(asMap(firstNonNull(raw.get("dataSources"), raw.get("datasources")))))
                .extension(extension)
                .build();
    }

    private static Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private Map<String, Object> resolveOutputMap(LangGraphState state, Object rawOutputs) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (rawOutputs instanceof Map<?, ?> outputs) {
            outputs.forEach((target, expression) -> {
                String key = asString(target);
                if (!key.isBlank()) {
                    result.put(key, resolveExpression(state, String.valueOf(expression)));
                }
            });
        }
        if (result.isEmpty()) {
            result.put("result", state.value(LAST_OUTPUT).orElse(state.value(INPUT).orElse("")));
        }
        return result;
    }

    private Map<String, Object> resolveConfiguredMap(LangGraphState state, Object rawValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (rawValues instanceof Map<?, ?> values) {
            values.forEach((target, expression) -> {
                String key = asString(target);
                if (!key.isBlank()) {
                    Object resolved = expression instanceof String text
                            ? resolveExpression(state, text)
                            : expression;
                    putMappedArg(result, key, resolved);
                }
            });
        }
        return result;
    }

    private Map<String, Object> extractParametersByExpression(LangGraphState state,
                                                              Map<String, Object> config,
                                                              List<Map<String, Object>> fields) {
        Map<String, Object> extracted = new LinkedHashMap<>();
        if (fields.isEmpty()) {
            Object rawParameters = config.get(CONFIG_PARAMETERS);
            if (rawParameters instanceof Map<?, ?> parameters) {
                parameters.forEach((target, expression) -> {
                    String key = asString(target);
                    if (!key.isBlank()) {
                        extracted.put(key, resolveExpression(state, String.valueOf(expression)));
                    }
                });
            }
            return extracted;
        }
        for (Map<String, Object> field : fields) {
            String name = asString(field.get("name"));
            String source = firstNonBlank(asString(field.get("source")), name);
            Object value = resolveExpression(state, source);
            if (isBlankValue(value) && field.containsKey("defaultValue")) {
                value = field.get("defaultValue");
            }
            extracted.put(name, coerceFieldValue(value, asString(field.get("type"))));
        }
        return extracted;
    }

    private Object normalizeLlmOutput(Map<String, Object> config, String content) {
        List<Map<String, Object>> schema = fieldList(config.get(CONFIG_OUTPUT_SCHEMA));
        if (!isStructuredOutput(config) && schema.isEmpty()) {
            return content;
        }
        Map<String, Object> parsed = parseJsonObject(content, "LLM output");
        if (!schema.isEmpty() && !Boolean.FALSE.equals(config.get(CONFIG_STRICT_JSON_SCHEMA))) {
            validateStructuredFields(schema, parsed, "LLM output");
        }
        return parsed;
    }

    private boolean isStructuredOutput(Map<String, Object> config) {
        String outputFormat = asString(config.get(CONFIG_OUTPUT_FORMAT)).toLowerCase();
        return "json".equals(outputFormat) || Boolean.TRUE.equals(config.get(CONFIG_STRUCTURED_OUTPUT));
    }

    private Map<String, Object> extractParametersWithLlm(LangGraphState state,
                                                         Map<String, Object> config,
                                                         List<Map<String, Object>> fields) {
        String modelInstanceId = firstNonBlank(asString(config.get(MODEL_INSTANCE_ID)), state.value(MODEL_INSTANCE_ID, ""));
        String schema = stringify(fields);
        String prompt = "Extract JSON fields from the input. Return only a JSON object.\nSchema: "
                + schema
                + "\nInput:\n"
                + renderModelInput(state);
        ModelServiceClient.ModelChatResult result = modelServiceClient.chat(ModelServiceClient.ModelChatRequest.builder()
                .modelInstanceId(modelInstanceId)
                .messages(List.of(
                        ModelServiceClient.ModelChatRequest.ChatMessage.builder()
                                .role("system")
                                .content("You extract structured JSON parameters.")
                                .build(),
                        ModelServiceClient.ModelChatRequest.ChatMessage.builder()
                                .role("user")
                                .content(prompt)
                                .build()))
                .build());
        if (result == null || result.getCode() != SUCCESS_CODE || result.getData() == null) {
            String message = result == null ? "empty response" : result.getMessage();
            throw new IllegalStateException("parameter extraction model call failed: " + message);
        }
        return parseJsonObject(nullToEmpty(result.getData().getContent()), "parameter extraction");
    }

    private void validateExtractedFields(List<Map<String, Object>> fields, Map<String, Object> extracted) {
        validateStructuredFields(fields, extracted, "parameter extraction");
    }

    private void validateStructuredFields(List<Map<String, Object>> fields, Map<String, Object> extracted, String source) {
        for (Map<String, Object> field : fields) {
            String name = asString(field.get("name"));
            if (!extracted.containsKey(name) && field.containsKey("defaultValue")) {
                extracted.put(name, field.get("defaultValue"));
            }
            if (Boolean.TRUE.equals(field.get("required")) && isBlankValue(extracted.get(name))) {
                throw new IllegalArgumentException(source + " required field is missing: " + name);
            }
            if (extracted.containsKey(name)) {
                extracted.put(name, coerceFieldValue(extracted.get(name), asString(field.get("type"))));
            }
        }
    }

    private Map<String, Object> parseJsonObject(String content, String source) {
        String normalized = stripJsonFence(content);
        try {
            return objectMapper.readValue(normalized, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception ex) {
            throw new IllegalArgumentException(source + " did not return valid JSON object");
        }
    }

    private String stripJsonFence(String content) {
        String text = content == null ? "" : content.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?\\s*", "");
            text = text.replaceFirst("\\s*```$", "");
        }
        return text.trim();
    }

    private Object coerceFieldValue(Object value, String type) {
        if (value == null) {
            return null;
        }
        return switch (type == null ? "" : type.toLowerCase()) {
            case "number" -> doubleValue(value, 0D);
            case "integer" -> (long) doubleValue(value, 0D);
            case "boolean" -> Boolean.parseBoolean(stringify(value));
            default -> value;
        };
    }

    private String appendQueryParams(String baseUrl, Object rawParams, LangGraphState state) {
        if (!(rawParams instanceof Map<?, ?> params) || params.isEmpty()) {
            return baseUrl;
        }
        StringBuilder builder = new StringBuilder(baseUrl);
        builder.append(baseUrl.contains("?") ? "&" : "?");
        boolean first = true;
        for (Map.Entry<?, ?> entry : params.entrySet()) {
            String key = asString(entry.getKey());
            if (key.isBlank()) {
                continue;
            }
            String value = renderTemplate(state, String.valueOf(entry.getValue()));
            if (!first) {
                builder.append("&");
            }
            builder.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                    .append("=")
                    .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
            first = false;
        }
        return first ? baseUrl : builder.toString();
    }

    private boolean hasHeader(Object rawHeaders, String expected) {
        if (!(rawHeaders instanceof Map<?, ?> headers)) {
            return false;
        }
        return headers.keySet().stream().map(LangGraph4jRuntimeAdapter::asString)
                .anyMatch(key -> expected.equalsIgnoreCase(key));
    }

    private void applyCredential(HttpRequest.Builder request,
                                 Map<String, Object> config,
                                 GraphRuntimeContext runtimeContext,
                                 LangGraphState state) {
        String credentialRef = asString(config.get(CONFIG_CREDENTIAL_REF));
        if (credentialRef.isBlank() || workflowCredentialService == null) {
            return;
        }
        WorkflowCredentialRuntime credential = workflowCredentialService
                .resolve(credentialRef, runtimeContext == null ? null : runtimeContext.getProjectId(),
                        runtimeContext == null ? null : runtimeContext.getProjectCode())
                .orElseThrow(() -> new IllegalArgumentException("Credential not found: " + credentialRef));
        Map<String, Object> secret = credential.getSecret() == null ? Map.of() : credential.getSecret();
        String type = firstNonBlank(asString(credential.getType()), asString(config.get("authType"))).toUpperCase();
        switch (type) {
            case "BEARER" -> {
                String token = firstNonBlank(asString(secret.get("token")), asString(secret.get("apiKey")));
                if (!token.isBlank()) {
                    request.header("Authorization", "Bearer " + renderTemplate(state, token));
                }
            }
            case "BASIC" -> {
                String username = asString(secret.get("username"));
                String password = asString(secret.get("password"));
                String raw = username + ":" + password;
                request.header("Authorization", "Basic " + java.util.Base64.getEncoder()
                        .encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
            }
            case "API_KEY_HEADER" -> {
                String header = firstNonBlank(asString(secret.get("headerName")), "X-API-Key");
                String value = firstNonBlank(asString(secret.get("apiKey")), asString(secret.get("token")));
                if (!value.isBlank()) {
                    request.header(header, renderTemplate(state, value));
                }
            }
            case "CUSTOM_HEADERS" -> {
                Object headers = secret.get("headers");
                if (headers instanceof Map<?, ?> map) {
                    map.forEach((key, value) -> {
                        String header = asString(key);
                        if (!header.isBlank()) {
                            request.header(header, renderTemplate(state, String.valueOf(value)));
                        }
                    });
                }
            }
            case "API_KEY_QUERY" -> {
                // Query credentials are added before URI creation by appendQueryParams.
            }
            default -> {
            }
        }
    }

    private Map<String, Object> credentialQueryParams(Map<String, Object> config, GraphRuntimeContext runtimeContext) {
        String credentialRef = asString(config.get(CONFIG_CREDENTIAL_REF));
        if (credentialRef.isBlank() || workflowCredentialService == null) {
            return Map.of();
        }
        WorkflowCredentialRuntime credential = workflowCredentialService
                .resolve(credentialRef, runtimeContext == null ? null : runtimeContext.getProjectId(),
                        runtimeContext == null ? null : runtimeContext.getProjectCode())
                .orElse(null);
        if (credential == null || !"API_KEY_QUERY".equalsIgnoreCase(credential.getType())) {
            return Map.of();
        }
        Map<String, Object> secret = credential.getSecret() == null ? Map.of() : credential.getSecret();
        String name = firstNonBlank(asString(secret.get("paramName")), "api_key");
        String value = firstNonBlank(asString(secret.get("apiKey")), asString(secret.get("token")));
        return value.isBlank() ? Map.of() : Map.of(name, value);
    }

    private Map<String, Object> metadata(AgentRuntimeRequest request,
                                         GraphRuntimeContext runtimeContext,
                                         GraphSpec graphSpec,
                                         long elapsed,
                                         LangGraphState state,
                                         List<GraphSpec.Node> nodes) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (request.getMetadata() != null) {
            metadata.putAll(request.getMetadata());
        }
        metadata.put("traceId", request.getTraceId());
        metadata.put("runtimeType", runtimeType());
        metadata.put("runtimePlacement", runtimeContext.getRuntimePlacement());
        metadata.put("agentName", runtimeContext.getName());
        if (isWorkflowSourceType(runtimeContext.getSourceType())) {
            metadata.putIfAbsent("workflowName", runtimeContext.getName());
        }
        metadata.put("sourceType", runtimeContext.getSourceType());
        mergeWorkflowMetadata(metadata, runtimeContext);
        if (runtimeContext.getExtra() != null) {
            metadata.putIfAbsent("version", runtimeContext.getExtra().get("__version"));
            metadata.putIfAbsent("versionId", runtimeContext.getExtra().get("__versionId"));
        }
        metadata.put("intentType", request.getIntentType());
        metadata.put("graphCode", graphSpec == null ? null : graphSpec.getCode());
        metadata.put("graphNodes", configuredGraphNodes(nodes));
        metadata.put("graphEdges", configuredGraphEdges(graphSpec));
        metadata.put("elapsedMs", elapsed);
        metadata.put("model", state.value(MODEL).orElse(null));
        metadata.put("provider", state.value(PROVIDER).orElse(null));
        metadata.put("finishReason", state.value(FINISH_REASON).orElse(null));
        metadata.put("finalOutput", state.value(LAST_OUTPUT).orElse(null));
        return metadata;
    }

    private void mergeWorkflowMetadata(Map<String, Object> metadata, GraphRuntimeContext runtimeContext) {
        if (runtimeContext == null) {
            return;
        }
        metadata.putIfAbsent("sourceType", runtimeContext.getSourceType());
        metadata.putIfAbsent("sourceId", runtimeContext.getSourceId());
        Map<String, Object> extra = runtimeContext.getExtra();
        if (extra != null) {
            metadata.putIfAbsent("workflowId", extra.get("workflowId"));
            metadata.putIfAbsent("workflowKeySlug", extra.get("workflowKeySlug"));
            metadata.putIfAbsent("workflowVersion", extra.get("workflowVersion"));
            metadata.putIfAbsent("workflowVersionId", extra.get("workflowVersionId"));
            metadata.putIfAbsent("entryAgentId", extra.get("entryAgentId"));
            metadata.putIfAbsent("entryAgentKeySlug", extra.get("entryAgentKeySlug"));
            metadata.putIfAbsent("bindingId", extra.get("bindingId"));
            metadata.putIfAbsent("bindingType", extra.get("bindingType"));
        }
        if (isWorkflowSourceType(runtimeContext.getSourceType())) {
            metadata.putIfAbsent("workflowId", runtimeContext.getSourceId());
            metadata.putIfAbsent("workflowKeySlug", runtimeContext.getSourceKeySlug());
            metadata.putIfAbsent("workflowVersion", runtimeContext.getSourceVersion());
            metadata.putIfAbsent("workflowVersionId", runtimeContext.getSourceVersionId());
        }
    }

    private boolean isWorkflowSourceType(String sourceType) {
        return sourceType != null && sourceType.toUpperCase().startsWith("WORKFLOW");
    }

    private ToolExecutionContext buildExecutionContext(AgentRuntimeRequest request, GraphRuntimeContext runtimeContext) {
        return ToolExecutionContext.builder()
                .traceId(request.getTraceId())
                .sessionId(request.getSessionId())
                .userId(request.getUserId())
                .agentName(runtimeContext.getName())
                .agentId(runtimeContext.getSourceId())
                .intentType(request.getIntentType())
                .projectCode(runtimeContext.getProjectCode())
                .tenantId(metadataString(request, "tenantId"))
                .appId(metadataString(request, "appId"))
                .externalUserId(firstNonBlank(metadataString(request, "externalUserId"), request.getUserId()))
                .globalUserId(metadataString(request, "globalUserId"))
                .pageInstanceId(metadataString(request, "pageInstanceId"))
                .origin(metadataString(request, "origin"))
                .route(metadataString(request, "route"))
                .allowIrreversible(runtimeContext.isAllowIrreversible())
                .roles(request.getRoles())
                .currentTurnMessage(request.getMessage())
                .build();
    }

    private Map<String, Object> initialState(AgentRuntimeRequest request,
                                             GraphRuntimeContext runtimeContext,
                                             List<GraphSpec.Node> nodes) {
        GraphSpec.Node firstLlm = nodes.stream().filter(this::isLlmNode).findFirst().orElse(null);
        Map<String, Object> config = firstLlm == null ? Map.of() : safeMap(firstLlm.getConfig());
        Map<String, Object> state = new LinkedHashMap<>();
        String message = nullToEmpty(request.getMessage());
        Map<String, Object> requestParams = requestParams(request);
        state.put(INPUT, message);
        state.put("input.message", message);
        state.put("params", requestParams);
        state.put(REQUEST_PARAMS, requestParams);
        state.put("traceId", nullToEmpty(request.getTraceId()));
        state.put("sessionId", nullToEmpty(request.getSessionId()));
        state.put("userId", nullToEmpty(request.getUserId()));
        state.put("sys", sysContext(request, runtimeContext));
        state.put("agentId", longValue(runtimeContext.getSourceId(), 0L));
        if (isWorkflowSourceType(runtimeContext.getSourceType())) {
            state.put("workflowId", nullToEmpty(runtimeContext.getSourceId()));
        }
        state.put(SYSTEM_PROMPT, firstNonBlank(asString(config.get(SYSTEM_PROMPT)), nullToEmpty(runtimeContext.getSystemPrompt())));
        state.put(MODEL_INSTANCE_ID, firstNonBlank(asString(config.get(MODEL_INSTANCE_ID)), nullToEmpty(runtimeContext.getModelInstanceId())));
        return state;
    }

    private Map<String, Object> requestParams(AgentRuntimeRequest request) {
        Map<String, Object> params = firstMap(
                request == null ? null : request.getRuntimeOptions(),
                request == null ? null : request.getMetadata(),
                "params");
        if (params.isEmpty()) {
            params = firstMap(
                    request == null ? null : request.getRuntimeOptions(),
                    request == null ? null : request.getMetadata(),
                    "inputs");
        }
        return params;
    }

    private Map<String, Object> firstMap(Map<String, Object> primary,
                                         Map<String, Object> secondary,
                                         String key) {
        Object value = primary == null ? null : primary.get(key);
        if (!(value instanceof Map<?, ?>) && secondary != null) {
            value = secondary.get(key);
        }
        return new LinkedHashMap<>(asMap(value));
    }

    private Map<String, Object> sysContext(AgentRuntimeRequest request, GraphRuntimeContext runtimeContext) {
        Map<String, Object> sys = new LinkedHashMap<>();
        sys.put("traceId", nullToEmpty(request == null ? null : request.getTraceId()));
        sys.put("sessionId", nullToEmpty(request == null ? null : request.getSessionId()));
        sys.put("userId", nullToEmpty(request == null ? null : request.getUserId()));
        sys.put("roles", request == null || request.getRoles() == null ? List.of() : request.getRoles());
        sys.put("agentId", runtimeContext == null ? null : runtimeContext.getSourceId());
        sys.put("agentName", nullToEmpty(runtimeContext == null ? null : runtimeContext.getName()));
        sys.put("projectCode", nullToEmpty(runtimeContext == null ? null : runtimeContext.getProjectCode()));
        sys.put("tenantId", metadataString(request, "tenantId"));
        sys.put("appId", metadataString(request, "appId"));
        sys.put("externalUserId", firstNonBlank(metadataString(request, "externalUserId"), request == null ? null : request.getUserId()));
        sys.put("globalUserId", metadataString(request, "globalUserId"));
        sys.put("pageInstanceId", metadataString(request, "pageInstanceId"));
        sys.put("origin", metadataString(request, "origin"));
        sys.put("route", metadataString(request, "route"));
        if (runtimeContext != null) {
            sys.put("sourceType", runtimeContext.getSourceType());
            if (runtimeContext.getExtra() != null) {
                sys.put("workflowId", runtimeContext.getExtra().get("workflowId"));
                sys.put("workflowKeySlug", runtimeContext.getExtra().get("workflowKeySlug"));
            }
        }
        return sys;
    }

    private String metadataString(AgentRuntimeRequest request, String key) {
        if (request == null || request.getMetadata() == null) {
            return "";
        }
        Object value = request.getMetadata().get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private void logNode(ToolExecutionContext context,
                         GraphSpec.Node node,
                         Object input,
                         Object result,
                         boolean success,
                         Exception error,
                         long elapsedMs) {
        if (toolCallLogService == null) {
            return;
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("runtimeType", runtimeType());
        args.put("node", node.getId());
        args.put("nodeType", node.getType());
        args.put("input", redactSensitive(input));
        toolCallLogService.record(context,
                "runtime.langgraph4j.node." + node.getId(),
                args,
                redactSensitive(result),
                success,
                error == null ? null : error.getClass().getSimpleName(),
                elapsedMs,
                tokenCostFrom(result));
    }

    private void logRuntimeRun(ToolExecutionContext context,
                               AgentRuntimeRequest request,
                               GraphRuntimeContext runtimeContext,
                               Object result,
                               boolean success,
                               Exception error,
                               long elapsedMs) {
        if (toolCallLogService == null) {
            return;
        }
        toolCallLogService.record(context,
                "runtime.agent.run",
                Map.of(
                        "runtimeType", runtimeType(),
                        "graph", runtimeContext == null ? "" : nullToEmpty(runtimeContext.getSourceId()),
                        "agentName", runtimeContext == null ? "" : nullToEmpty(runtimeContext.getName()),
                        "userInput", nullToEmpty(request.getMessage())),
                result,
                success,
                error == null ? null : error.getClass().getSimpleName(),
                elapsedMs,
                null);
    }

    private void recordSpan(ToolExecutionContext context,
                            GraphRuntimeContext runtimeContext,
                            AgentTraceSpanService.SpanRecord record) {
        if (traceSpanService != null) {
            traceSpanService.record(context, runtimeContext, record);
        }
    }

    private List<GraphSpec.Node> orderedExecutableNodes(GraphSpec spec) {
        if (spec == null || spec.getNodes() == null) {
            return List.of();
        }
        Map<String, GraphSpec.Node> byId = new LinkedHashMap<>();
        for (GraphSpec.Node node : spec.getNodes()) {
            if (node != null && !asString(node.getId()).isBlank() && isSupportedNode(node)) {
                byId.put(node.getId(), node);
            }
        }
        if (byId.isEmpty()) {
            return List.of();
        }
        List<GraphSpec.Node> walked = walkReachableGraph(spec, byId);
        if (!walked.isEmpty()) {
            return walked;
        }
        return byId.values().stream().toList();
    }

    private List<GraphSpec.Node> walkReachableGraph(GraphSpec spec, Map<String, GraphSpec.Node> byId) {
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        if (spec.getEdges() != null) {
            for (GraphSpec.Edge edge : spec.getEdges()) {
                String from = asString(edge.getFrom());
                String to = asString(edge.getTo());
                if (!from.isBlank() && !to.isBlank()) {
                    outgoing.computeIfAbsent(from, key -> new ArrayList<>()).add(to);
                }
            }
        }
        List<GraphSpec.Node> ordered = new ArrayList<>();
        List<String> queue = new ArrayList<>();
        queue.add(resolveEntry(spec, byId));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < queue.size(); i++) {
            String current = queue.get(i);
            if (current == null || !byId.containsKey(current) || !seen.add(current)) {
                continue;
            }
            ordered.add(byId.get(current));
            List<String> targets = outgoing.getOrDefault(current, List.of());
            for (String target : targets) {
                if (byId.containsKey(target) && !seen.contains(target)) {
                    queue.add(target);
                }
            }
        }
        return ordered;
    }

    private String resolveEntry(GraphSpec spec, Map<String, GraphSpec.Node> byId) {
        String entry = firstNonBlank(asString(spec == null ? null : spec.getEntry()), null);
        if (entry != null && byId.containsKey(entry)) {
            return entry;
        }
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        if (spec != null && spec.getEdges() != null) {
            for (GraphSpec.Edge edge : spec.getEdges()) {
                outgoing.computeIfAbsent(asString(edge.getFrom()), key -> new ArrayList<>()).add(asString(edge.getTo()));
            }
        }
        String startTarget = firstExecutableTarget(outgoing.get(START), byId);
        if (startTarget != null) {
            return startTarget;
        }
        return byId.keySet().iterator().next();
    }

    private List<GraphEdgeRoute> outgoingRoutes(GraphSpec spec,
                                                String nodeId,
                                                Map<String, GraphSpec.Node> nodeById) {
        if (spec == null || spec.getEdges() == null) {
            return List.of();
        }
        List<GraphEdgeRoute> routes = new ArrayList<>();
        int index = 0;
        for (GraphSpec.Edge edge : spec.getEdges()) {
            if (!nodeId.equals(asString(edge.getFrom()))) {
                continue;
            }
            String rawTarget = asString(edge.getTo());
            String target = normalizeTarget(rawTarget, nodeById);
            if (target == null) {
                continue;
            }
            String condition = normalizeCondition(edge.getCondition());
            routes.add(new GraphEdgeRoute("route_" + index, target, condition));
            index++;
        }
        return routes;
    }

    private String normalizeTarget(String target, Map<String, GraphSpec.Node> nodeById) {
        if (target == null || target.isBlank()) {
            return null;
        }
        if (END.equals(target) || "end".equalsIgnoreCase(target)) {
            return END;
        }
        if (START.equals(target) || "start".equalsIgnoreCase(target)) {
            return null;
        }
        return nodeById.containsKey(target) ? target : null;
    }

    private String selectRouteKey(LangGraphState state, List<GraphEdgeRoute> routes) {
        GraphEdgeRoute fallback = null;
        for (GraphEdgeRoute route : routes) {
            if (isAlwaysCondition(route.condition())) {
                if (fallback == null) {
                    fallback = route;
                }
                continue;
            }
            if (matchesCondition(state, route.condition())) {
                return route.key();
            }
        }
        return (fallback == null ? routes.get(0) : fallback).key();
    }

    private boolean hasErrorRoute(List<GraphEdgeRoute> routes) {
        return routes.stream()
                .map(GraphEdgeRoute::condition)
                .anyMatch(condition -> "error".equalsIgnoreCase(condition) || "failure".equalsIgnoreCase(condition));
    }

    private boolean matchesCondition(LangGraphState state, String condition) {
        String normalized = normalizeCondition(condition);
        if (isAlwaysCondition(normalized)) {
            return true;
        }
        boolean success = Boolean.TRUE.equals(state.value(LAST_SUCCESS).orElse(Boolean.TRUE));
        if ("success".equalsIgnoreCase(normalized)) {
            return success;
        }
        if ("error".equalsIgnoreCase(normalized) || "failure".equalsIgnoreCase(normalized)) {
            return !success || !state.value(LAST_ERROR, "").isBlank();
        }
        String lowerCondition = normalized.toLowerCase();
        String route = state.value(LAST_ROUTE, "");
        if (lowerCondition.startsWith("route:")) {
            return route.equals(normalized.substring("route:".length()).trim());
        }
        if (!route.isBlank() && route.equals(normalized)) {
            return true;
        }
        String output = stringify(state.value(LAST_OUTPUT).orElse(state.value(ANSWER, "")));
        String lowerOutput = output.toLowerCase();
        if (lowerCondition.startsWith("contains:")) {
            return lowerOutput.contains(lowerCondition.substring("contains:".length()).trim());
        }
        if (lowerCondition.startsWith("not_contains:")) {
            return !lowerOutput.contains(lowerCondition.substring("not_contains:".length()).trim());
        }
        if (lowerCondition.startsWith("equals:")) {
            return output.trim().equals(normalized.substring("equals:".length()).trim());
        }
        if (lowerCondition.startsWith("not_equals:")) {
            return !output.trim().equals(normalized.substring("not_equals:".length()).trim());
        }
        if (lowerCondition.startsWith("empty")) {
            return output.isBlank();
        }
        if (lowerCondition.startsWith("not_empty")) {
            return !output.isBlank();
        }
        return lowerOutput.contains(lowerCondition);
    }

    private static boolean isAlwaysCondition(String condition) {
        String normalized = normalizeCondition(condition);
        return normalized.isBlank()
                || "always".equalsIgnoreCase(normalized)
                || "default".equalsIgnoreCase(normalized)
                || "else".equalsIgnoreCase(normalized);
    }

    private static String normalizeCondition(String condition) {
        return condition == null ? "" : condition.trim();
    }

    private static String firstExecutableTarget(List<String> targets, Map<String, GraphSpec.Node> byId) {
        if (targets == null) {
            return null;
        }
        return targets.stream()
                .map(LangGraph4jRuntimeAdapter::asString)
                .filter(byId::containsKey)
                .findFirst()
                .orElse(null);
    }

    private boolean isSupportedNode(GraphSpec.Node node) {
        return AgentGraphNodeType.supports(node == null ? null : node.getType());
    }

    private boolean isLlmNode(GraphSpec.Node node) {
        return AgentGraphNodeType.find(node == null ? null : node.getType())
                .map(AgentGraphNodeType::isLlm)
                .orElse(false);
    }

    private boolean isToolNode(GraphSpec.Node node) {
        return AgentGraphNodeType.find(node == null ? null : node.getType())
                .map(AgentGraphNodeType::isToolLike)
                .orElse(false);
    }

    private boolean isFlowNode(GraphSpec.Node node) {
        return AgentGraphNodeType.find(node == null ? null : node.getType())
                .map(AgentGraphNodeType::isFlow)
                .orElse(false);
    }

    private String normalizeNodeType(GraphSpec.Node node) {
        return AgentGraphNodeType.normalize(node == null ? null : node.getType());
    }

    private String resolveToolName(GraphSpec.Node node) {
        Map<String, Object> config = safeMap(node.getConfig());
        GraphSpec.CapabilityRef ref = node.getRef();
        String name = firstNonBlank(
                ref == null ? null : ref.getQualifiedName(),
                asString(config.get(CONFIG_QUALIFIED_NAME)));
        name = firstNonBlank(name, ref == null ? null : ref.getName());
        name = firstNonBlank(name, asString(config.get(CONFIG_TOOL_NAME)));
        name = firstNonBlank(name, node.getName());
        name = firstNonBlank(name, node.getId());
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tool node has no resolvable name: " + node.getId());
        }
        return name;
    }

    private String resolveExecutableToolName(GraphSpec.Node node) {
        String name = resolveToolName(node);
        if (toolDefinitionService.findByName(name).isPresent()) {
            return name;
        }
        return toolDefinitionService.findByQualifiedName(name)
                .map(entity -> firstNonBlank(entity.getName(), name))
                .orElse(name);
    }

    private Duration resolveToolRequestTimeout(GraphSpec.Node node) {
        Map<String, Object> config = safeMap(node == null ? null : node.getConfig());
        long timeoutMs = longValue(config.get(CONFIG_MAX_REQUEST_TIME_MS), DynamicHttpAiTool.DEFAULT_REQUEST_TIMEOUT_MS);
        return DynamicHttpAiTool.normalizeRequestTimeout(Duration.ofMillis(timeoutMs));
    }

    private void applyToolCredentialArgs(Map<String, Object> args, GraphSpec.Node node, GraphRuntimeContext runtimeContext) {
        Map<String, Object> config = safeMap(node.getConfig());
        String credentialRef = asString(config.get(CONFIG_CREDENTIAL_REF));
        if (credentialRef.isBlank() || workflowCredentialService == null) {
            return;
        }
        WorkflowCredentialRuntime credential = workflowCredentialService
                .resolve(credentialRef, runtimeContext == null ? null : runtimeContext.getProjectId(),
                        runtimeContext == null ? null : runtimeContext.getProjectCode())
                .orElseThrow(() -> new IllegalArgumentException("Credential not found: " + credentialRef));
        args.put("credentialRef", credentialRef);
        args.put("__credential", Map.of(
                "type", nullToEmpty(credential.getType()),
                "secret", credential.getSecret() == null ? Map.of() : credential.getSecret()));
    }

    private Map<String, Object> resolveToolArgs(LangGraphState state, GraphSpec.Node node) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("input", state.value(INPUT, ""));
        state.value(ANSWER).ifPresent(answer -> args.put("answer", answer));
        state.value(LAST_OUTPUT).ifPresent(output -> args.put("previousOutput", output));
        Map<String, Object> config = safeMap(node.getConfig());
        Object configured = config.containsKey(CONFIG_ARGS) ? config.get(CONFIG_ARGS) : config.get(CONFIG_TOOL_ARGS);
        if (configured instanceof Map<?, ?> map) {
            map.forEach((key, value) -> args.put(String.valueOf(key), value));
        }
        Object inputMapping = config.get(CONFIG_INPUT_MAPPING);
        if (inputMapping instanceof Map<?, ?> map) {
            map.forEach((target, expression) -> putMappedArg(args,
                    String.valueOf(target),
                    resolveExpression(state, String.valueOf(expression))));
        }
        args.putIfAbsent("query", firstNonBlank(asString(args.get("answer")), asString(args.get("input"))));
        return args;
    }

    private Map<String, Object> resolveNodeInput(LangGraphState state, GraphSpec.Node node) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        Map<String, Object> config = safeMap(node.getConfig());
        Object inputMapping = config.get(CONFIG_INPUT_MAPPING);
        if (inputMapping instanceof Map<?, ?> map) {
            map.forEach((target, expression) -> putMappedArg(resolved,
                    String.valueOf(target),
                    resolveExpression(state, String.valueOf(expression))));
        }
        if (!resolved.isEmpty()) {
            return resolved;
        }
        if (isToolNode(node) || "MCP_CALL".equals(normalizeNodeType(node))) {
            return resolveToolArgs(state, node);
        }
        String type = normalizeNodeType(node);
        if ("USER_INPUT".equals(type)) {
            resolved.put("params", state.value(REQUEST_PARAMS).orElse(Map.of()));
            return resolved;
        }
        if ("LLM".equals(type)) {
            resolved.put("input", renderModelInput(state));
            return resolved;
        }
        String expression = firstNonBlank(
                asString(config.get(CONFIG_INPUT_EXPRESSION)),
                asString(config.get(CONFIG_SOURCE_EXPRESSION)),
                asString(config.get(CONFIG_QUERY)),
                LAST_OUTPUT);
        resolved.put("input", resolveExpression(state, expression));
        return resolved;
    }

    private Object rawNodeOutput(Map<String, Object> update, GraphSpec.Node node) {
        if (update == null || node == null) {
            return null;
        }
        Object nodeOutput = update.get(nodeOutputKey(node.getId()));
        if (nodeOutput != null) {
            return nodeOutput;
        }
        return update.get(LAST_OUTPUT);
    }

    private Map<String, Object> publishedVariables(Map<String, Object> update, GraphSpec.Node node) {
        Map<String, Object> published = new LinkedHashMap<>();
        if (update == null) {
            return published;
        }
        String nodeOutput = node == null ? "" : nodeOutputKey(node.getId());
        update.forEach((key, value) -> {
            if (key == null) {
                return;
            }
            if (key.equals(nodeOutput) || key.startsWith(VAR_PREFIX)) {
                published.put(key, value);
            }
        });
        if (node != null) {
            String alias = asString(safeMap(node.getConfig()).get(CONFIG_OUTPUT_ALIAS));
            if (!alias.isBlank() && update.containsKey(alias)) {
                published.put(alias, update.get(alias));
            }
        }
        return published;
    }

    private String renderModelInput(LangGraphState state) {
        String input = state.value(INPUT, "");
        Object lastOutput = state.value(LAST_OUTPUT).orElse(null);
        if (lastOutput == null) {
            return input;
        }
        return input + "\n\nPrevious node output:\n" + stringify(lastOutput);
    }

    private Object resolveExpression(LangGraphState state, String expression) {
        String expr = expression == null ? "" : expression.trim();
        if (expr.isBlank()) {
            return "";
        }
        if (expr.startsWith("const:")) {
            return expr.substring("const:".length());
        }
        if ((expr.startsWith("\"") && expr.endsWith("\"")) || (expr.startsWith("'") && expr.endsWith("'"))) {
            return expr.substring(1, expr.length() - 1);
        }
        if ("true".equalsIgnoreCase(expr) || "false".equalsIgnoreCase(expr)) {
            return Boolean.parseBoolean(expr);
        }
        if ("null".equalsIgnoreCase(expr)) {
            return null;
        }
        Number numeric = tryParseNumber(expr);
        if (numeric != null) {
            return numeric;
        }

        String path = expr.startsWith("$") ? expr.substring(1) : expr;
        boolean referencePath = isReferencePathExpression(expr, path);
        Object unresolved = referencePath ? null : expr;
        Object exact = stateValue(state, path);
        if (exact != null) {
            return exact;
        }
        String[] parts = path.split("\\.");
        if (parts.length == 0) {
            return unresolved;
        }
        Object root = stateValue(state, parts[0]);
        if (root == null && parts.length > 1 && "nodeOutput".equals(parts[0])) {
            root = stateValue(state, nodeOutputKey(parts[1]));
            return traverse(root, parts, 2, unresolved);
        }
        if (root == null) {
            root = stateValue(state, VAR_PREFIX + parts[0]);
        }
        return traverse(root, parts, 1, unresolved);
    }

    private boolean isReferencePathExpression(String expression, String path) {
        if (expression != null && expression.trim().startsWith("$")) {
            return true;
        }
        return path != null && path.contains(".");
    }

    private String renderTemplate(LangGraphState state, String template) {
        if (template == null || template.isBlank()) {
            return "";
        }
        Matcher matcher = TEMPLATE_VARIABLE.matcher(template);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            Object value = resolveExpression(state, matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(stringify(value)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String firstRegexGroup(String text, String regex) {
        if (regex == null || regex.isBlank()) {
            return "";
        }
        try {
            Matcher matcher = Pattern.compile(regex, Pattern.DOTALL).matcher(text == null ? "" : text);
            if (!matcher.find()) {
                return "";
            }
            return matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
        } catch (Exception ignored) {
            return "";
        }
    }

    private Object stateValue(LangGraphState state, String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return switch (key) {
            case "input" -> state.value(INPUT).orElse(null);
            case "answer" -> state.value(ANSWER).orElse(null);
            case "lastOutput", "previousOutput" -> state.value(LAST_OUTPUT).orElse(null);
            case "lastError" -> state.value(LAST_ERROR).orElse(null);
            case "lastSuccess" -> state.value(LAST_SUCCESS).orElse(null);
            case "lastRoute" -> state.value(LAST_ROUTE).orElse(null);
            default -> {
                Object exact = state.value(key).orElse(null);
                if (exact != null) {
                    yield exact;
                }
                Object alias = state.value(VAR_PREFIX + key).orElse(null);
                if (alias != null) {
                    yield alias;
                }
                yield state.value(nodeOutputKey(key)).orElse(null);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private Object traverse(Object value, String[] parts, int start, Object fallback) {
        Object current = value;
        for (int i = start; i < parts.length; i++) {
            if (current == null) {
                return fallback;
            }
            String part = parts[i];
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
                continue;
            }
            if (current instanceof List<?> list) {
                Integer index = tryParseIndex(part);
                current = index == null || index < 0 || index >= list.size() ? null : list.get(index);
                continue;
            }
            return fallback;
        }
        return current == null ? fallback : current;
    }

    @SuppressWarnings("unchecked")
    private void putMappedArg(Map<String, Object> args, String targetPath, Object value) {
        String target = targetPath == null ? "" : targetPath.trim();
        if (target.isBlank()) {
            return;
        }
        String[] parts = target.split("\\.");
        Map<String, Object> cursor = args;
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i].trim();
            if (part.isBlank()) {
                return;
            }
            Object next = cursor.get(part);
            if (!(next instanceof Map<?, ?>)) {
                next = new LinkedHashMap<String, Object>();
                cursor.put(part, next);
            }
            cursor = (Map<String, Object>) next;
        }
        String leaf = parts[parts.length - 1].trim();
        if (!leaf.isBlank()) {
            cursor.put(leaf, value);
        }
    }

    private void putOutputAlias(Map<String, Object> update, GraphSpec.Node node, Object value) {
        String alias = asString(safeMap(node.getConfig()).get(CONFIG_OUTPUT_ALIAS));
        if (alias.isBlank()) {
            return;
        }
        update.put(VAR_PREFIX + alias, value);
        update.put(alias, value);
    }

    private Number tryParseNumber(String value) {
        try {
            if (value.matches("-?\\d+")) {
                return Long.parseLong(value);
            }
            if (value.matches("-?\\d+\\.\\d+")) {
                return Double.parseDouble(value);
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }

    private Integer tryParseIndex(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String finalAnswer(LangGraphState state) {
        String answer = state.value(ANSWER, "");
        if (!answer.isBlank()) {
            return answer;
        }
        Object lastOutput = state.value(LAST_OUTPUT).orElse("");
        Map<String, Object> pageAction = safeMap(asMap(lastOutput));
        if ("page.action.requested".equals(asString(pageAction.get("type")))) {
            return firstNonBlank(asString(pageAction.get("message")), asString(pageAction.get("title")), "已为你准备页面查询。");
        }
        return stringify(lastOutput);
    }

    private List<String> steps(List<GraphSpec.Node> nodes) {
        List<String> steps = new ArrayList<>();
        steps.add("Runtime: " + runtimeType());
        nodes.forEach(node -> steps.add("Graph node: " + node.getId() + " (" + node.getType() + ")"));
        return steps;
    }

    private List<String> toolCalls(List<GraphSpec.Node> nodes) {
        return nodes.stream()
                .filter(this::isToolNode)
                .map(this::resolveToolName)
                .toList();
    }

    private static Map<String, Object> tokenUsage(ModelServiceClient.ModelUsage usage) {
        if (usage == null) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("promptTokens", usage.getPromptTokens());
        result.put("completionTokens", usage.getCompletionTokens());
        result.put("totalTokens", usage.getTotalTokens());
        return result;
    }

    private static Object redactSensitive(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> redacted = new LinkedHashMap<>();
            map.forEach((key, child) -> {
                String name = asString(key);
                if (isSensitiveKey(name)) {
                    redacted.put(name, "***");
                } else {
                    redacted.put(name, redactSensitive(child));
                }
            });
            return redacted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(LangGraph4jRuntimeAdapter::redactSensitive).toList();
        }
        return value;
    }

    private static boolean isSensitiveKey(String key) {
        String normalized = key == null ? "" : key.toLowerCase();
        return normalized.contains("credential")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("apikey")
                || normalized.contains("api_key")
                || normalized.contains("password")
                || normalized.contains("authorization");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private static Map<String, Object> safeMap(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    private static void putIfText(Map<String, Object> target, String key, Object value) {
        String text = asString(value);
        if (!text.isBlank()) {
            target.put(key, text);
        }
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = asString(value);
        if (text.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String text = asString(value);
        if (text.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean isBlankValue(Object value) {
        return value == null || stringify(value).isBlank();
    }

    private static Integer tokenCostFrom(Object result) {
        Map<String, Object> map = asMap(result);
        Object usage = map.get(TOKEN_USAGE);
        if (usage instanceof Map<?, ?> usageMap) {
            Object total = usageMap.get("totalTokens");
            if (total instanceof Number number) {
                return number.intValue();
            }
        }
        return null;
    }

    private static List<String> configuredGraphNodes(List<GraphSpec.Node> nodes) {
        return nodes.stream()
                .map(node -> asString(node.getId()))
                .filter(id -> !id.isBlank())
                .toList();
    }

    private static Object configuredGraphEdges(GraphSpec spec) {
        if (spec == null || spec.getEdges() == null) {
            return List.of();
        }
        return spec.getEdges().stream()
                .map(edge -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("from", nullToEmpty(edge.getFrom()));
                    item.put("to", nullToEmpty(edge.getTo()));
                    item.put("condition", nullToEmpty(edge.getCondition()));
                    return item;
                })
                .toList();
    }

    private static Map<String, Object> nodeMetadata(GraphSpec.Node node) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("nodeId", node.getId());
        metadata.put("nodeType", node.getType());
        metadata.put("nodeName", node.getName());
        if (node.getRef() != null) {
            metadata.put("refKind", node.getRef().getKind());
            metadata.put("refName", node.getRef().getName());
            metadata.put("refQualifiedName", node.getRef().getQualifiedName());
            metadata.put("refDefinitionId", node.getRef().getDefinitionId());
        }
        return metadata;
    }

    private static Map<String, Object> nodeMetadata(GraphSpec.Node node, Map<String, Object> update) {
        Map<String, Object> metadata = nodeMetadata(node);
        if (update != null) {
            Object route = update.get(LAST_ROUTE);
            if (route != null) {
                metadata.put("lastRoute", route);
            }
            Object success = update.get(LAST_SUCCESS);
            if (success != null) {
                metadata.put("lastSuccess", success);
            }
            Object error = update.get(LAST_ERROR);
            if (error != null) {
                metadata.put("lastError", error);
            }
        }
        return metadata;
    }

    private static String nodeOutputKey(String nodeId) {
        return "nodeOutput." + nodeId;
    }

    private static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof CharSequence s) {
            return s.toString();
        }
        return String.valueOf(value);
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @Data
    @Builder
    public static class WorkflowDebugRunResult {
        private String runId;
        private String traceId;
        private boolean success;
        private String status;
        private String answer;
        private List<WorkflowDebugStepResult> steps;
        private Map<String, Object> finalState;
        private String currentNodeId;
        private UiRequestPayload uiRequest;
        private String errorCode;
        private String errorMessage;
    }

    @Data
    @Builder
    public static class WorkflowDebugStepResult {
        private int index;
        private String nodeId;
        private String nodeType;
        private String nodeName;
        private String status;
        private LocalDateTime startedAt;
        private LocalDateTime endedAt;
        private long elapsedMs;
        private Map<String, Object> input;
        private Map<String, Object> resolvedInput;
        private Object output;
        private Object rawOutput;
        private Map<String, Object> publishedVariables;
        private Map<String, Object> statePatch;
        private String eventType;
        private UiRequestPayload uiRequest;
        private Map<String, Object> artifact;
        private String route;
        private String condition;
        private String nextNodeId;
        private String errorCode;
        private String errorMessage;
    }

    @Data
    @Builder
    public static class NodeDebugResult {
        private String nodeId;
        private String nodeType;
        private boolean success;
        private long elapsedMs;
        private Map<String, Object> inputState;
        private Map<String, Object> outputState;
        private Object nodeOutput;
        private String lastRoute;
        private String errorCode;
        private String errorMessage;
        private String traceId;
    }

    public static class LangGraphState extends AgentState {
        public LangGraphState(Map<String, Object> initData) {
            super(initData);
        }
    }

    private record GraphEdgeRoute(String key, String target, String condition) {
    }

    private record RouteDecision(String target, String condition, String route) {
    }
}
