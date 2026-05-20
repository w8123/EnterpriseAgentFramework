package com.enterprise.ai.agent.runtime;

import com.enterprise.ai.agent.agent.AgentDefinition;
import com.enterprise.ai.agent.client.ModelServiceClient;
import com.enterprise.ai.agent.client.SkillsServiceClient;
import com.enterprise.ai.agent.credential.WorkflowCredentialRuntime;
import com.enterprise.ai.agent.credential.WorkflowCredentialService;
import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.AgentGraphSpec;
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
import com.enterprise.ai.agent.trace.AgentTraceSpanService;
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
import java.util.UUID;
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
    static final String CONFIG_ARGS = "args";
    static final String CONFIG_TOOL_ARGS = "toolArgs";
    static final String CONFIG_TOOL_NAME = "toolName";
    static final String CONFIG_QUALIFIED_NAME = "qualifiedName";
    static final String CONFIG_INPUT_MAPPING = "inputMapping";
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
        AgentDefinition definition = request == null ? null : request.getAgentDefinition();
        if (definition == null || "pipeline".equalsIgnoreCase(definition.getType())) {
            return false;
        }
        List<AgentGraphSpec.Node> nodes = orderedExecutableNodes(definition);
        return nodes.stream().anyMatch(this::isLlmNode) && nodes.stream().allMatch(this::isSupportedNode);
    }

    @Override
    public String unsupportedReason(AgentRuntimeRequest request) {
        AgentDefinition definition = request == null ? null : request.getAgentDefinition();
        if (definition == null) {
            return "LangGraph4j requires a valid Agent definition.";
        }
        if ("pipeline".equalsIgnoreCase(definition.getType())) {
            return "LangGraph4j currently executes a single GraphSpec workflow, not legacy pipeline sub-agents.";
        }
        AgentGraphSpec spec = graphSpec(definition);
        if (spec == null || spec.getNodes() == null || spec.getNodes().isEmpty()) {
            return "LangGraph4j requires graphSpec.nodes.";
        }
        if (orderedExecutableNodes(definition).stream().noneMatch(this::isLlmNode)) {
            return "LangGraph4j graphSpec must contain at least one LLM node.";
        }
        return "LangGraph4j currently supports LLM, TOOL, CAPABILITY, IF_ELSE, VARIABLE_ASSIGN, TEMPLATE, ANSWER, CODE, INTENT_CLASSIFIER, VARIABLE_AGGREGATOR, HUMAN_APPROVAL, LOOP, KNOWLEDGE_WRITE, DOCUMENT_EXTRACT, MCP_CALL, PARAMETER_EXTRACT, HTTP_REQUEST, KNOWLEDGE_RETRIEVAL nodes and simple conditional edges.";
    }

    @Override
    public AgentRuntimeResult execute(AgentRuntimeRequest request) {
        AgentDefinition definition = request.getAgentDefinition();
        String traceId = request.getTraceId();
        long start = System.currentTimeMillis();
        LocalDateTime startedAt = LocalDateTime.now();
        ToolExecutionContext context = buildExecutionContext(request, definition);
        List<AgentGraphSpec.Node> nodes = orderedExecutableNodes(definition);
        Map<String, Object> initialState = initialState(request, definition, nodes);
        try {
            StateGraph<LangGraphState> graph = buildGraph(context, definition, nodes);
            LangGraphState finalState = graph.compile()
                    .invoke(initialState,
                            RunnableConfig.builder()
                                    .threadId(request.getSessionId())
                                    .graphId("agent:" + definition.getId())
                                    .build())
                    .orElseThrow(() -> new IllegalStateException("LangGraph4j execution returned no final state"));

            String answer = finalAnswer(finalState);
            long elapsed = System.currentTimeMillis() - start;
            Map<String, Object> metadata = metadata(request, definition, elapsed, finalState, nodes);
            logRuntimeRun(context, request, definition, metadata, true, null, elapsed);
            recordSpan(context, definition, AgentTraceSpanService.SpanRecord.builder()
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
                    .agentName(definition.getName())
                    .steps(steps(nodes))
                    .toolCalls(toolCalls(nodes))
                    .tokenUsage(asMap(finalState.value(TOKEN_USAGE).orElse(null)))
                    .metadata(metadata)
                    .build();
        } catch (InteractionSuspendedException suspended) {
            return suspendedRuntimeResult(request, definition, context, nodes, start, suspended);
        } catch (Exception ex) {
            InteractionSuspendedException suspended = findSuspended(ex);
            if (suspended != null) {
                return suspendedRuntimeResult(request, definition, context, nodes, start, suspended);
            }
            long elapsed = System.currentTimeMillis() - start;
            log.error("[LangGraph4jRuntime] execution failed: agent={}, traceId={}", definition.getName(), traceId, ex);
            logRuntimeRun(context, request, definition, Map.of("error", ex.getMessage()), false, ex, elapsed);
            recordSpan(context, definition, AgentTraceSpanService.SpanRecord.builder()
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
                    .metadata(Map.of("agentName", definition.getName(), "runtimeType", runtimeType()))
                    .build());
            return AgentRuntimeResult.builder()
                    .success(false)
                    .answer("LangGraph4j Runtime execution failed: " + ex.getMessage())
                    .runtimeType(runtimeType())
                    .traceId(traceId)
                    .agentName(definition.getName())
                    .errorCode(ex.getClass().getSimpleName())
                    .errorMessage(ex.getMessage())
                    .metadata(Map.of(
                            "traceId", traceId,
                            "agentName", definition.getName(),
                            "runtimeType", runtimeType(),
                            "elapsedMs", elapsed))
                    .build();
        }
    }

    private AgentRuntimeResult suspendedRuntimeResult(AgentRuntimeRequest request,
                                                      AgentDefinition definition,
                                                      ToolExecutionContext context,
                                                      List<AgentGraphSpec.Node> nodes,
                                                      long start,
                                                      InteractionSuspendedException suspended) {
        long elapsed = System.currentTimeMillis() - start;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("traceId", request.getTraceId());
        metadata.put("agentName", definition.getName());
        metadata.put("runtimeType", runtimeType());
        metadata.put("elapsedMs", elapsed);
        metadata.put("uiRequest", suspended.getPayload());
        logRuntimeRun(context, request, definition, metadata, true, null, elapsed);
        return AgentRuntimeResult.builder()
                .success(true)
                .answer(suspended.getMessage())
                .runtimeType(runtimeType())
                .traceId(request.getTraceId())
                .agentName(definition.getName())
                .uiRequest(suspended.getPayload())
                .steps(steps(nodes))
                .toolCalls(toolCalls(nodes))
                .metadata(metadata)
                .build();
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
                                                      AgentDefinition definition,
                                                      Map<String, Object> suspendedState,
                                                      String approvalNodeId,
                                                      String route) {
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

        List<AgentGraphSpec.Node> allNodes = orderedExecutableNodes(definition);
        Map<String, AgentGraphSpec.Node> nodeById = new LinkedHashMap<>();
        for (AgentGraphSpec.Node node : allNodes) {
            nodeById.put(node.getId(), node);
        }
        List<GraphEdgeRoute> outgoing = outgoingRoutes(graphSpec(definition), approvalNodeId, nodeById);
        String next = nextTargetAfterApproval(new LangGraphState(resumeState), outgoing);
        if (next == null || END.equals(next)) {
            return resumedResult(request, definition, new LangGraphState(resumeState), allNodes, "Human approval completed");
        }

        AgentGraphSpec resumedSpec = graphSpecWithEntry(graphSpec(definition), next);
        AgentDefinition resumedDefinition = copyDefinitionForGraph(definition, resumedSpec);
        List<AgentGraphSpec.Node> resumedNodes = orderedExecutableNodes(resumedDefinition);
        ToolExecutionContext context = buildExecutionContext(request, resumedDefinition);
        long start = System.currentTimeMillis();
        try {
            StateGraph<LangGraphState> graph = buildGraph(context, resumedDefinition, resumedNodes);
            LangGraphState finalState = graph.compile()
                    .invoke(resumeState,
                            RunnableConfig.builder()
                                    .threadId(request.getSessionId())
                                    .graphId("agent:" + definition.getId() + ":resume:" + approvalNodeId)
                                    .build())
                    .orElseThrow(() -> new IllegalStateException("LangGraph4j resume returned no final state"));
            long elapsed = System.currentTimeMillis() - start;
            Map<String, Object> metadata = metadata(request, resumedDefinition, elapsed, finalState, resumedNodes);
            metadata.put("resumedFrom", approvalNodeId);
            metadata.put("approvalRoute", normalizedRoute);
            logRuntimeRun(context, request, resumedDefinition, metadata, true, null, elapsed);
            return AgentRuntimeResult.builder()
                    .success(true)
                    .answer(finalAnswer(finalState))
                    .runtimeType(runtimeType())
                    .traceId(request.getTraceId())
                    .agentName(definition.getName())
                    .steps(steps(resumedNodes))
                    .toolCalls(toolCalls(resumedNodes))
                    .tokenUsage(asMap(finalState.value(TOKEN_USAGE).orElse(null)))
                    .metadata(metadata)
                    .build();
        } catch (Exception ex) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[LangGraph4jRuntime] resume failed: agent={}, node={}, traceId={}",
                    definition.getName(), approvalNodeId, request.getTraceId(), ex);
            logRuntimeRun(context, request, resumedDefinition, Map.of("error", ex.getMessage()), false, ex, elapsed);
            return AgentRuntimeResult.builder()
                    .success(false)
                    .answer("LangGraph4j Runtime resume failed: " + ex.getMessage())
                    .runtimeType(runtimeType())
                    .traceId(request.getTraceId())
                    .agentName(definition.getName())
                    .errorCode(ex.getClass().getSimpleName())
                    .errorMessage(ex.getMessage())
                    .metadata(Map.of(
                            "traceId", request.getTraceId(),
                            "agentName", definition.getName(),
                            "runtimeType", runtimeType(),
                            "elapsedMs", elapsed,
                            "resumedFrom", approvalNodeId,
                            "approvalRoute", normalizedRoute))
                    .build();
        }
    }

    private AgentRuntimeResult resumedResult(AgentRuntimeRequest request,
                                             AgentDefinition definition,
                                             LangGraphState state,
                                             List<AgentGraphSpec.Node> nodes,
                                             String defaultAnswer) {
        String answer = firstNonBlank(finalAnswer(state), defaultAnswer);
        Map<String, Object> metadata = metadata(request, definition, 0L, state, nodes);
        return AgentRuntimeResult.builder()
                .success(true)
                .answer(answer)
                .runtimeType(runtimeType())
                .traceId(request.getTraceId())
                .agentName(definition.getName())
                .steps(steps(nodes))
                .toolCalls(toolCalls(nodes))
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

    private AgentGraphSpec graphSpecWithEntry(AgentGraphSpec source, String entry) {
        return AgentGraphSpec.builder()
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

    public NodeDebugResult debugNode(AgentDefinition definition,
                                     String nodeId,
                                     String message,
                                     Map<String, Object> stateOverrides) {
        if (definition == null || graphSpec(definition) == null) {
            throw new IllegalArgumentException("Agent definition must include graphSpec");
        }
        AgentGraphSpec.Node node = graphSpec(definition).getNodes() == null ? null : graphSpec(definition).getNodes().stream()
                .filter(item -> item != null && nodeId != null && nodeId.equals(item.getId()))
                .findFirst()
                .orElse(null);
        if (node == null || !isSupportedNode(node)) {
            throw new IllegalArgumentException("Executable node not found: " + nodeId);
        }
        AgentGraphSpec singleNodeSpec = AgentGraphSpec.builder()
                .code(firstNonBlank(graphSpec(definition).getCode(), "studio_node_debug"))
                .name(firstNonBlank(graphSpec(definition).getName(), definition.getName()))
                .runtimeHint(LANGGRAPH4J_RUNTIME_TYPE)
                .entry(node.getId())
                .finishNode(node.getId())
                .node(node)
                .edge(AgentGraphSpec.Edge.builder().from(START).to(node.getId()).condition("always").build())
                .edge(AgentGraphSpec.Edge.builder().from(node.getId()).to(END).condition("always").build())
                .build();
        AgentDefinition debugDefinition = copyDefinitionForGraph(definition, singleNodeSpec);
        AgentRuntimeRequest request = AgentRuntimeRequest.builder()
                .traceId("studio-node-debug-" + UUID.randomUUID())
                .sessionId("studio-node-debug")
                .userId("studio-debug")
                .message(nullToEmpty(message))
                .intentType(definition.getIntentType())
                .agentDefinition(debugDefinition)
                .build();
        List<AgentGraphSpec.Node> executableNodes = List.of(node);
        Map<String, Object> initial = initialState(request, debugDefinition, executableNodes);
        if (stateOverrides != null) {
            initial.putAll(stateOverrides);
        }
        long start = System.currentTimeMillis();
        ToolExecutionContext context = buildExecutionContext(request, debugDefinition);
        try {
            StateGraph<LangGraphState> graph = buildGraph(context, debugDefinition, executableNodes);
            LangGraphState finalState = graph.compile()
                    .invoke(initial, RunnableConfig.builder()
                            .threadId(request.getSessionId())
                            .graphId("studio-node-debug:" + definition.getId() + ":" + node.getId())
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

    private AgentDefinition copyDefinitionForGraph(AgentDefinition source, AgentGraphSpec graphSpec) {
        return AgentDefinition.builder()
                .id(source.getId())
                .keySlug(source.getKeySlug())
                .name(source.getName())
                .description(source.getDescription())
                .agentMode(source.getAgentMode())
                .projectId(source.getProjectId())
                .projectCode(source.getProjectCode())
                .visibility(source.getVisibility())
                .intentType(source.getIntentType())
                .systemPrompt(source.getSystemPrompt())
                .tools(source.getTools())
                .toolRefs(source.getToolRefs())
                .skills(source.getSkills())
                .skillRefs(source.getSkillRefs())
                .modelInstanceId(source.getModelInstanceId())
                .runtimeType(source.getRuntimeType())
                .runtimePlacement(source.getRuntimePlacement())
                .runtimeConfig(source.getRuntimeConfig())
                .defaultResourceConfig(source.getDefaultResourceConfig())
                .graphSpec(graphSpec)
                .maxSteps(source.getMaxSteps())
                .enabled(source.isEnabled())
                .type(source.getType())
                .pipelineAgentIds(source.getPipelineAgentIds())
                .knowledgeBaseGroupId(source.getKnowledgeBaseGroupId())
                .promptTemplateId(source.getPromptTemplateId())
                .outputSchemaType(source.getOutputSchemaType())
                .triggerMode(source.getTriggerMode())
                .useMultiAgentModel(source.isUseMultiAgentModel())
                .extra(source.getExtra())
                .canvasJson(source.getCanvasJson())
                .allowIrreversible(source.isAllowIrreversible())
                .createdAt(source.getCreatedAt())
                .updatedAt(source.getUpdatedAt())
                .build();
    }

    private StateGraph<LangGraphState> buildGraph(ToolExecutionContext context,
                                                  AgentDefinition definition,
                                                  List<AgentGraphSpec.Node> nodes) throws Exception {
        StateGraph<LangGraphState> graph = new StateGraph<>(LangGraphState::new);
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("LangGraph4j GraphSpec has no executable nodes");
        }
        AgentGraphSpec spec = graphSpec(definition);
        Map<String, AgentGraphSpec.Node> nodeById = new LinkedHashMap<>();
        for (AgentGraphSpec.Node node : nodes) {
            nodeById.put(node.getId(), node);
        }
        for (AgentGraphSpec.Node graphNode : nodes) {
            AgentGraphSpec.Node node = graphNode;
            boolean allowErrorRoute = hasErrorRoute(outgoingRoutes(spec, node.getId(), nodeById));
            if (isLlmNode(node)) {
                graph = graph.addNode(node.getId(), node_async(state -> callModelNode(state, context, definition, node, allowErrorRoute)));
            } else if (isToolNode(node)) {
                graph = graph.addNode(node.getId(), node_async(state -> callToolNode(state, context, definition, node, allowErrorRoute)));
            } else if (isFlowNode(node)) {
                graph = graph.addNode(node.getId(), node_async(state -> callFlowNode(state, context, definition, node, allowErrorRoute)));
            }
        }
        String entry = resolveEntry(spec, nodeById);
        graph = graph.addEdge(START, entry);
        for (AgentGraphSpec.Node node : nodes) {
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
                                              AgentDefinition definition,
                                              AgentGraphSpec.Node node,
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
            recordSpan(context, definition, AgentTraceSpanService.SpanRecord.builder()
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
            recordSpan(context, definition, AgentTraceSpanService.SpanRecord.builder()
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
                                             AgentDefinition definition,
                                             AgentGraphSpec.Node node,
                                             boolean allowErrorRoute) {
        long start = System.currentTimeMillis();
        LocalDateTime startedAt = LocalDateTime.now();
        String toolName = resolveExecutableToolName(node);
        Map<String, Object> args = resolveToolArgs(state, node);
        applyToolCredentialArgs(args, node, definition);
        ToolExecutionContext prev = ToolExecutionContextHolder.get();
        try {
            ToolExecutionContextHolder.set(context);
            Object result = toolDefinitionService.executeTool(toolName, args);
            Map<String, Object> update = new LinkedHashMap<>();
            update.put(LAST_OUTPUT, result);
            update.put(LAST_SUCCESS, true);
            update.put(LAST_ERROR, "");
            update.put(nodeOutputKey(node.getId()), result);
            putOutputAlias(update, node, result);
            long elapsed = System.currentTimeMillis() - start;
            logNode(context, node, args, result, true, null, elapsed);
            recordSpan(context, definition, AgentTraceSpanService.SpanRecord.builder()
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
            recordSpan(context, definition, AgentTraceSpanService.SpanRecord.builder()
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
        Object configured = config.get(CONFIG_MESSAGES);
        if (configured instanceof List<?> list && !list.isEmpty()) {
            for (Object item : list) {
                Map<String, Object> raw = asMap(item);
                if (Boolean.FALSE.equals(raw.get("enabled"))) {
                    continue;
                }
                String role = normalizeMessageRole(asString(raw.get("role")));
                String content = renderTemplate(state, asString(raw.get("content")));
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
        }
        appendStructuredOutputInstruction(messages, config);
        appendVisionInputs(messages, state, config);
        return messages;
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
                                             AgentDefinition definition,
                                             AgentGraphSpec.Node node,
                                             boolean allowErrorRoute) {
        long start = System.currentTimeMillis();
        LocalDateTime startedAt = LocalDateTime.now();
        try {
            Map<String, Object> update = switch (normalizeNodeType(node)) {
                case "VARIABLE_ASSIGN" -> executeVariableAssign(state, node);
                case "TEMPLATE" -> executeTemplate(state, node);
                case "ANSWER" -> executeAnswer(state, node);
                case "CODE" -> executeCode(state, node);
                case "INTENT_CLASSIFIER" -> executeIntentClassifier(state, node);
                case "VARIABLE_AGGREGATOR" -> executeVariableAggregator(state, node);
                case "HUMAN_APPROVAL" -> executeHumanApproval(state, definition, node);
                case "LOOP" -> executeLoop(state, node);
                case "KNOWLEDGE_WRITE" -> executeKnowledgeWritePersisted(state, node);
                case "DOCUMENT_EXTRACT" -> executeDocumentExtract(state, node);
                case "MCP_CALL" -> executeMcpCall(state, node);
                case "IF_ELSE" -> executeIfElse(state, node);
                case "PARAMETER_EXTRACT" -> executeParameterExtract(state, node);
                case "HTTP_REQUEST" -> executeHttpRequest(state, definition, node);
                case "KNOWLEDGE_RETRIEVAL" -> executeKnowledgeRetrieval(state, node);
                default -> throw new IllegalArgumentException("Unsupported flow node: " + node.getType());
            };
            long elapsed = System.currentTimeMillis() - start;
            logNode(context, node, node.getConfig(), update, true, null, elapsed);
            recordSpan(context, definition, AgentTraceSpanService.SpanRecord.builder()
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
            recordSpan(context, definition, AgentTraceSpanService.SpanRecord.builder()
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
            recordSpan(context, definition, AgentTraceSpanService.SpanRecord.builder()
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

    private Map<String, Object> executeVariableAssign(LangGraphState state, AgentGraphSpec.Node node) {
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

    private Map<String, Object> executeTemplate(LangGraphState state, AgentGraphSpec.Node node) {
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

    private Map<String, Object> executeAnswer(LangGraphState state, AgentGraphSpec.Node node) {
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

    private Map<String, Object> executeCode(LangGraphState state, AgentGraphSpec.Node node) {
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

    private Map<String, Object> executeIntentClassifier(LangGraphState state, AgentGraphSpec.Node node) {
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

    private Map<String, Object> executeVariableAggregator(LangGraphState state, AgentGraphSpec.Node node) {
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
                                                     AgentDefinition definition,
                                                     AgentGraphSpec.Node node) {
        Map<String, Object> config = safeMap(node.getConfig());
        String prompt = renderTemplate(state, firstNonBlank(asString(config.get("prompt")), "{{ lastOutput }}"));
        String route = firstNonBlank(asString(config.get(CONFIG_DEFAULT_ROUTE)), "approved");
        if (skillInteractionMapper != null && !Boolean.TRUE.equals(config.get("autoApprove"))) {
            suspendHumanApproval(state, definition, node, config, prompt);
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
                                      AgentDefinition definition,
                                      AgentGraphSpec.Node node,
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
        row.setSlotState(writeJson(Map.of(
                "phase", "approval",
                "nodeId", node.getId(),
                "prompt", prompt,
                "definition", definition,
                "state", state.data())));
        row.setPendingKeys(writeJson(List.of("decision")));
        row.setUiPayload(writeJson(payload));
        row.setSpecSnapshot(writeJson(Map.of("nodeId", node.getId(), "config", config)));
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setExpiresAt(now.plusSeconds(ttlSeconds));
        skillInteractionMapper.insert(row);
        throw new InteractionSuspendedException(payload, "流程已暂停，等待人工审批");
    }

    private Map<String, Object> executeLoop(LangGraphState state, AgentGraphSpec.Node node) {
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

    private Map<String, Object> executeKnowledgeWrite(LangGraphState state, AgentGraphSpec.Node node) {
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

    private Map<String, Object> executeKnowledgeWritePersisted(LangGraphState state, AgentGraphSpec.Node node) {
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

    private String knowledgeWriteFileId(AgentGraphSpec.Node node, String title, String content) {
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

    private Map<String, Object> executeDocumentExtract(LangGraphState state, AgentGraphSpec.Node node) {
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

    private Map<String, Object> executeMcpCall(LangGraphState state, AgentGraphSpec.Node node) {
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

    private Map<String, Object> executeIfElse(LangGraphState state, AgentGraphSpec.Node node) {
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

    private Map<String, Object> executeParameterExtract(LangGraphState state, AgentGraphSpec.Node node) {
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

    private Map<String, Object> executeKnowledgeRetrieval(LangGraphState state, AgentGraphSpec.Node node) {
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
                                                   AgentDefinition definition,
                                                   AgentGraphSpec.Node node) throws Exception {
        Map<String, Object> config = safeMap(node.getConfig());
        String method = firstNonBlank(asString(config.get(CONFIG_METHOD)), "GET").toUpperCase();
        String url = appendQueryParams(renderTemplate(state, asString(config.get(CONFIG_URL))), config.get(CONFIG_QUERY_PARAMS), state);
        url = appendQueryParams(url, credentialQueryParams(config, definition), state);
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
        applyCredential(request, config, definition, state);
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
                    .filter(field -> !asString(field.get("name")).isBlank())
                    .toList();
        }
        return List.of();
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
                                 AgentDefinition definition,
                                 LangGraphState state) {
        String credentialRef = asString(config.get(CONFIG_CREDENTIAL_REF));
        if (credentialRef.isBlank() || workflowCredentialService == null) {
            return;
        }
        WorkflowCredentialRuntime credential = workflowCredentialService
                .resolve(credentialRef, definition == null ? null : definition.getProjectId(),
                        definition == null ? null : definition.getProjectCode())
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

    private Map<String, Object> credentialQueryParams(Map<String, Object> config, AgentDefinition definition) {
        String credentialRef = asString(config.get(CONFIG_CREDENTIAL_REF));
        if (credentialRef.isBlank() || workflowCredentialService == null) {
            return Map.of();
        }
        WorkflowCredentialRuntime credential = workflowCredentialService
                .resolve(credentialRef, definition == null ? null : definition.getProjectId(),
                        definition == null ? null : definition.getProjectCode())
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
                                         AgentDefinition definition,
                                         long elapsed,
                                         LangGraphState state,
                                         List<AgentGraphSpec.Node> nodes) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (request.getMetadata() != null) {
            metadata.putAll(request.getMetadata());
        }
        metadata.put("traceId", request.getTraceId());
        metadata.put("runtimeType", runtimeType());
        metadata.put("runtimePlacement", definition.getRuntimePlacement());
        metadata.put("agentName", definition.getName());
        if (definition.getExtra() != null) {
            metadata.putIfAbsent("version", definition.getExtra().get("__version"));
            metadata.putIfAbsent("versionId", definition.getExtra().get("__versionId"));
        }
        metadata.put("intentType", request.getIntentType());
        metadata.put("graphCode", graphSpec(definition) == null ? null : graphSpec(definition).getCode());
        metadata.put("graphNodes", configuredGraphNodes(nodes));
        metadata.put("graphEdges", configuredGraphEdges(definition));
        metadata.put("elapsedMs", elapsed);
        metadata.put("model", state.value(MODEL).orElse(null));
        metadata.put("provider", state.value(PROVIDER).orElse(null));
        metadata.put("finishReason", state.value(FINISH_REASON).orElse(null));
        return metadata;
    }

    private ToolExecutionContext buildExecutionContext(AgentRuntimeRequest request, AgentDefinition definition) {
        return ToolExecutionContext.builder()
                .traceId(request.getTraceId())
                .sessionId(request.getSessionId())
                .userId(request.getUserId())
                .agentName(definition.getName())
                .intentType(request.getIntentType())
                .projectCode(definition.getProjectCode())
                .allowIrreversible(definition.isAllowIrreversible())
                .roles(request.getRoles())
                .currentTurnMessage(request.getMessage())
                .build();
    }

    private Map<String, Object> initialState(AgentRuntimeRequest request,
                                             AgentDefinition definition,
                                             List<AgentGraphSpec.Node> nodes) {
        AgentGraphSpec.Node firstLlm = nodes.stream().filter(this::isLlmNode).findFirst().orElse(null);
        Map<String, Object> config = firstLlm == null ? Map.of() : safeMap(firstLlm.getConfig());
        Map<String, Object> state = new LinkedHashMap<>();
        state.put(INPUT, nullToEmpty(request.getMessage()));
        state.put("traceId", nullToEmpty(request.getTraceId()));
        state.put("sessionId", nullToEmpty(request.getSessionId()));
        state.put("userId", nullToEmpty(request.getUserId()));
        state.put("agentId", longValue(definition.getId(), 0L));
        state.put(SYSTEM_PROMPT, firstNonBlank(asString(config.get(SYSTEM_PROMPT)), nullToEmpty(definition.getSystemPrompt())));
        state.put(MODEL_INSTANCE_ID, firstNonBlank(asString(config.get(MODEL_INSTANCE_ID)), nullToEmpty(definition.getModelInstanceId())));
        return state;
    }

    private void logNode(ToolExecutionContext context,
                         AgentGraphSpec.Node node,
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
                               AgentDefinition definition,
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
                        "graph", graphSpec(definition) == null ? "" : nullToEmpty(graphSpec(definition).getCode()),
                        "agentName", definition.getName(),
                        "userInput", nullToEmpty(request.getMessage())),
                result,
                success,
                error == null ? null : error.getClass().getSimpleName(),
                elapsedMs,
                null);
    }

    private void recordSpan(ToolExecutionContext context,
                            AgentDefinition definition,
                            AgentTraceSpanService.SpanRecord record) {
        if (traceSpanService != null) {
            traceSpanService.record(context, definition, record);
        }
    }

    private List<AgentGraphSpec.Node> orderedExecutableNodes(AgentDefinition definition) {
        AgentGraphSpec spec = graphSpec(definition);
        if (spec == null || spec.getNodes() == null) {
            return List.of();
        }
        Map<String, AgentGraphSpec.Node> byId = new LinkedHashMap<>();
        for (AgentGraphSpec.Node node : spec.getNodes()) {
            if (node != null && !asString(node.getId()).isBlank() && isSupportedNode(node)) {
                byId.put(node.getId(), node);
            }
        }
        if (byId.isEmpty()) {
            return List.of();
        }
        List<AgentGraphSpec.Node> walked = walkReachableGraph(spec, byId);
        if (!walked.isEmpty()) {
            return walked;
        }
        return byId.values().stream().toList();
    }

    private List<AgentGraphSpec.Node> walkReachableGraph(AgentGraphSpec spec, Map<String, AgentGraphSpec.Node> byId) {
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        if (spec.getEdges() != null) {
            for (AgentGraphSpec.Edge edge : spec.getEdges()) {
                String from = asString(edge.getFrom());
                String to = asString(edge.getTo());
                if (!from.isBlank() && !to.isBlank()) {
                    outgoing.computeIfAbsent(from, key -> new ArrayList<>()).add(to);
                }
            }
        }
        List<AgentGraphSpec.Node> ordered = new ArrayList<>();
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

    private String resolveEntry(AgentGraphSpec spec, Map<String, AgentGraphSpec.Node> byId) {
        String entry = firstNonBlank(asString(spec == null ? null : spec.getEntry()), null);
        if (entry != null && byId.containsKey(entry)) {
            return entry;
        }
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        if (spec != null && spec.getEdges() != null) {
            for (AgentGraphSpec.Edge edge : spec.getEdges()) {
                outgoing.computeIfAbsent(asString(edge.getFrom()), key -> new ArrayList<>()).add(asString(edge.getTo()));
            }
        }
        String startTarget = firstExecutableTarget(outgoing.get(START), byId);
        if (startTarget != null) {
            return startTarget;
        }
        return byId.keySet().iterator().next();
    }

    private List<GraphEdgeRoute> outgoingRoutes(AgentGraphSpec spec,
                                                String nodeId,
                                                Map<String, AgentGraphSpec.Node> nodeById) {
        if (spec == null || spec.getEdges() == null) {
            return List.of();
        }
        List<GraphEdgeRoute> routes = new ArrayList<>();
        int index = 0;
        for (AgentGraphSpec.Edge edge : spec.getEdges()) {
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

    private String normalizeTarget(String target, Map<String, AgentGraphSpec.Node> nodeById) {
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

    private static String firstExecutableTarget(List<String> targets, Map<String, AgentGraphSpec.Node> byId) {
        if (targets == null) {
            return null;
        }
        return targets.stream()
                .map(LangGraph4jRuntimeAdapter::asString)
                .filter(byId::containsKey)
                .findFirst()
                .orElse(null);
    }

    private boolean isSupportedNode(AgentGraphSpec.Node node) {
        return AgentGraphNodeType.supports(node == null ? null : node.getType());
    }

    private boolean isLlmNode(AgentGraphSpec.Node node) {
        return AgentGraphNodeType.find(node == null ? null : node.getType())
                .map(AgentGraphNodeType::isLlm)
                .orElse(false);
    }

    private boolean isToolNode(AgentGraphSpec.Node node) {
        return AgentGraphNodeType.find(node == null ? null : node.getType())
                .map(AgentGraphNodeType::isToolLike)
                .orElse(false);
    }

    private boolean isFlowNode(AgentGraphSpec.Node node) {
        return AgentGraphNodeType.find(node == null ? null : node.getType())
                .map(AgentGraphNodeType::isFlow)
                .orElse(false);
    }

    private String normalizeNodeType(AgentGraphSpec.Node node) {
        return AgentGraphNodeType.normalize(node == null ? null : node.getType());
    }

    private String resolveToolName(AgentGraphSpec.Node node) {
        Map<String, Object> config = safeMap(node.getConfig());
        AgentGraphSpec.CapabilityRef ref = node.getRef();
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

    private String resolveExecutableToolName(AgentGraphSpec.Node node) {
        String name = resolveToolName(node);
        if (toolDefinitionService.findByName(name).isPresent()) {
            return name;
        }
        return toolDefinitionService.findByQualifiedName(name)
                .map(entity -> firstNonBlank(entity.getName(), name))
                .orElse(name);
    }

    private void applyToolCredentialArgs(Map<String, Object> args, AgentGraphSpec.Node node, AgentDefinition definition) {
        Map<String, Object> config = safeMap(node.getConfig());
        String credentialRef = asString(config.get(CONFIG_CREDENTIAL_REF));
        if (credentialRef.isBlank() || workflowCredentialService == null) {
            return;
        }
        WorkflowCredentialRuntime credential = workflowCredentialService
                .resolve(credentialRef, definition == null ? null : definition.getProjectId(),
                        definition == null ? null : definition.getProjectCode())
                .orElseThrow(() -> new IllegalArgumentException("Credential not found: " + credentialRef));
        args.put("credentialRef", credentialRef);
        args.put("__credential", Map.of(
                "type", nullToEmpty(credential.getType()),
                "secret", credential.getSecret() == null ? Map.of() : credential.getSecret()));
    }

    private Map<String, Object> resolveToolArgs(LangGraphState state, AgentGraphSpec.Node node) {
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
        Object exact = stateValue(state, path);
        if (exact != null) {
            return exact;
        }
        String[] parts = path.split("\\.");
        if (parts.length == 0) {
            return expr;
        }
        Object root = stateValue(state, parts[0]);
        if (root == null && parts.length > 1 && "nodeOutput".equals(parts[0])) {
            root = stateValue(state, nodeOutputKey(parts[1]));
            return traverse(root, parts, 2, expr);
        }
        if (root == null) {
            root = stateValue(state, VAR_PREFIX + parts[0]);
        }
        return traverse(root, parts, 1, expr);
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
    private Object traverse(Object value, String[] parts, int start, String fallback) {
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

    private void putOutputAlias(Map<String, Object> update, AgentGraphSpec.Node node, Object value) {
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
        return stringify(state.value(LAST_OUTPUT).orElse(""));
    }

    private List<String> steps(List<AgentGraphSpec.Node> nodes) {
        List<String> steps = new ArrayList<>();
        steps.add("Runtime: " + runtimeType());
        nodes.forEach(node -> steps.add("Graph node: " + node.getId() + " (" + node.getType() + ")"));
        return steps;
    }

    private List<String> toolCalls(List<AgentGraphSpec.Node> nodes) {
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

    private static List<String> configuredGraphNodes(List<AgentGraphSpec.Node> nodes) {
        return nodes.stream()
                .map(node -> asString(node.getId()))
                .filter(id -> !id.isBlank())
                .toList();
    }

    private static Object configuredGraphEdges(AgentDefinition definition) {
        AgentGraphSpec spec = graphSpec(definition);
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

    private static Map<String, Object> nodeMetadata(AgentGraphSpec.Node node) {
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

    private static Map<String, Object> nodeMetadata(AgentGraphSpec.Node node, Map<String, Object> update) {
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

    private static AgentGraphSpec graphSpec(AgentDefinition definition) {
        return definition == null ? null : definition.getGraphSpec();
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
}
