package com.enterprise.ai.runtime.workflow.draft;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest.ChatMessage;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RuntimeWorkflowDraftGenerationService {

    private static final String PROVIDER = "LLM_DRAFT";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final RuntimeModelServiceClient modelServiceClient;

    public String provider() {
        return PROVIDER;
    }

    public boolean supports(RuntimeWorkflowDraftGenerationRequest request) {
        return request != null
                && StringUtils.hasText(request.getRequirement())
                && StringUtils.hasText(request.getModelInstanceId());
    }

    public RuntimeWorkflowDraftGenerationView generate(RuntimeWorkflowDraftGenerationRequest request) {
        Map<String, RuntimeWorkflowDraftResourceView> resources = resourceIndex(request);
        List<String> warnings = new ArrayList<>();
        List<String> validationErrors = new ArrayList<>();
        List<RuntimeWorkflowDraftPlaceholderView> placeholders = new ArrayList<>();

        if (!supports(request)) {
            validationErrors.add("requirement and modelInstanceId are required");
            return result(request, List.of(), List.of(), warnings, placeholders, validationErrors);
        }

        DraftResponse draft;
        try {
            String raw = callModel(request);
            JsonNode root = normalizeDraftJson(objectMapper.readTree(extractJsonObject(raw)));
            draft = objectMapper.treeToValue(root, DraftResponse.class);
        } catch (Exception ex) {
            validationErrors.add("AI did not return valid JSON workflow draft: " + ex.getMessage());
            return result(request, List.of(), List.of(), warnings, placeholders, validationErrors);
        }
        if (draft.warnings() != null) {
            draft.warnings().stream()
                    .filter(StringUtils::hasText)
                    .forEach(warnings::add);
        }

        List<DraftNode> normalizedNodes = normalizeNodes(draft.nodes(), request, resources, warnings, validationErrors, placeholders);
        boolean backfilledPageActions = false;
        if (isPageAssistantDraft(request)) {
            backfilledPageActions = backfillPageAssistantActionsWhenMissing(request, normalizedNodes, warnings);
            normalizePageAssistantDraft(request, normalizedNodes, warnings);
        }
        List<DraftEdge> normalizedEdges = normalizeEdges(draft.edges(), normalizedNodes, validationErrors);
        if (backfilledPageActions) {
            normalizedEdges = defaultEdges(normalizedNodes);
        } else if (normalizedEdges.isEmpty() && !normalizedNodes.isEmpty()) {
            normalizedEdges = defaultEdges(normalizedNodes);
        }
        return result(request, normalizedNodes, normalizedEdges, warnings, placeholders, validationErrors);
    }

    private RuntimeWorkflowDraftGenerationView result(RuntimeWorkflowDraftGenerationRequest request,
                                                 List<DraftNode> nodes,
                                                 List<DraftEdge> edges,
                                                 List<String> warnings,
                                                 List<RuntimeWorkflowDraftPlaceholderView> placeholders,
                                                 List<String> validationErrors) {
        GraphSpec graphSpec = graphSpec(request, nodes, edges);
        return new RuntimeWorkflowDraftGenerationView(
                PROVIDER,
                canvasSnapshot(graphSpec, nodes, edges),
                graphSpec,
                warnings,
                placeholders,
                validationErrors);
    }

    private List<DraftNode> normalizeNodes(List<DraftNode> rawNodes,
                                           RuntimeWorkflowDraftGenerationRequest request,
                                           Map<String, RuntimeWorkflowDraftResourceView> resources,
                                           List<String> warnings,
                                           List<String> validationErrors,
                                           List<RuntimeWorkflowDraftPlaceholderView> placeholders) {
        if (rawNodes == null || rawNodes.isEmpty()) {
            validationErrors.add("workflow draft must contain at least one node");
            return List.of();
        }
        List<DraftNode> nodes = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        Set<String> usedPageActions = new LinkedHashSet<>();
        int pageActionNodeIndex = 0;
        for (DraftNode raw : rawNodes) {
            if (raw == null) continue;
            String id = slug(firstText(raw.id(), raw.label(), raw.kind(), "node_" + (nodes.size() + 1)));
            String rawKind = firstText(raw.kind(), raw.type());
            if ("start".equalsIgnoreCase(id) || "end".equalsIgnoreCase(id)
                    || "START".equalsIgnoreCase(rawKind) || "END".equalsIgnoreCase(rawKind)) {
                warnings.add("模型输出了 start/end 节点，后端已忽略并统一管理边界节点");
                continue;
            }
            if (!ids.add(id)) {
                validationErrors.add("duplicate node id: " + id);
                continue;
            }
            AgentGraphNodeType type = AgentGraphNodeType.find(rawKind).orElse(null);
            if (type == null) {
                validationErrors.add("unsupported node kind: " + rawKind + " (" + id + ")");
                continue;
            }

            Map<String, Object> config = mutableMap(raw.config());
            config.put("configVersion", 2);
            config.putIfAbsent("source", "AI_DRAFT");
            if (type == AgentGraphNodeType.LLM) {
                normalizeLlmConfig(config, request);
            } else if (type == AgentGraphNodeType.USER_INPUT) {
                normalizeUserInputConfig(config);
            } else if (type == AgentGraphNodeType.ANSWER) {
                normalizeAnswerConfig(config);
            } else if (type == AgentGraphNodeType.INTENT_CLASSIFIER) {
                normalizeClassifierConfig(config);
            } else if (type == AgentGraphNodeType.HUMAN_APPROVAL) {
                normalizeApprovalConfig(config);
            } else if (type == AgentGraphNodeType.KNOWLEDGE_RETRIEVAL) {
                normalizeKnowledgeConfig(config, request, resources, warnings, placeholders, id, firstText(raw.label(), id));
            } else if (type == AgentGraphNodeType.PAGE_ACTION) {
                normalizePageActionConfig(config, request, resources, warnings, placeholders, id, firstText(raw.label(), id),
                        usedPageActions, pageActionNodeIndex++, validationErrors);
            } else if (type.isToolLike() || type == AgentGraphNodeType.HTTP_REQUEST || type == AgentGraphNodeType.MCP_CALL) {
                normalizeCapabilityConfig(config, type, request, resources, warnings, placeholders, id, firstText(raw.label(), id));
            }

            nodes.add(new DraftNode(
                    id,
                    type.canvasKind(),
                    type.type(),
                    firstText(raw.label(), id),
                    firstText(raw.description(), ""),
                    config,
                    raw.inputs(),
                    raw.outputs()));
        }
        if (nodes.isEmpty()) {
            validationErrors.add("workflow draft did not contain any usable nodes");
        }
        normalizeReferencedOutputAliases(nodes);
        return nodes;
    }

    private List<DraftEdge> normalizeEdges(List<DraftEdge> rawEdges,
                                           List<DraftNode> nodes,
                                           List<String> validationErrors) {
        if (rawEdges == null) {
            return List.of();
        }
        Set<String> nodeIds = new LinkedHashSet<>(nodes.stream().map(DraftNode::id).toList());
        List<DraftEdge> edges = new ArrayList<>();
        int index = 1;
        for (DraftEdge raw : rawEdges) {
            if (raw == null) continue;
            String from = endpoint(firstText(raw.from(), raw.source()));
            String to = endpoint(firstText(raw.to(), raw.target()));
            if (!isEndpointAllowed(from, nodeIds) || !isEndpointAllowed(to, nodeIds)) {
                validationErrors.add("edge references missing node: " + from + " -> " + to);
                continue;
            }
            if ("END".equals(from) || "START".equals(to)) {
                validationErrors.add("edge direction is invalid: " + from + " -> " + to);
                continue;
            }
            String condition = firstText(raw.condition(), raw.label(), "always");
            edges.add(new DraftEdge(
                    firstText(raw.id(), "e-" + endpointId(from) + "-" + endpointId(to) + "-" + index++),
                    from,
                    to,
                    null,
                    null,
                    condition,
                    firstText(raw.sourceHandle(), ""),
                    firstText(raw.targetHandle(), ""),
                    raw.label()));
        }
        ensureBoundaryEdges(edges, nodes);
        return edges;
    }

    private List<DraftEdge> defaultEdges(List<DraftNode> nodes) {
        List<DraftEdge> edges = new ArrayList<>();
        if (nodes.isEmpty()) return edges;
        edges.add(new DraftEdge("e-start-" + nodes.get(0).id(), "START", nodes.get(0).id(), null, null, "always", "", "", ""));
        for (int i = 0; i < nodes.size() - 1; i++) {
            DraftNode from = nodes.get(i);
            DraftNode to = nodes.get(i + 1);
            edges.add(new DraftEdge("e-" + from.id() + "-" + to.id(), from.id(), to.id(), null, null, "always", "", "", ""));
        }
        DraftNode last = nodes.get(nodes.size() - 1);
        edges.add(new DraftEdge("e-" + last.id() + "-end", last.id(), "END", null, null, "always", "", "", ""));
        return edges;
    }

    private void ensureBoundaryEdges(List<DraftEdge> edges, List<DraftNode> nodes) {
        if (nodes.isEmpty()) return;
        if (edges.stream().noneMatch(edge -> "START".equals(edge.from()))) {
            DraftNode first = nodes.get(0);
            edges.add(0, new DraftEdge("e-start-" + first.id(), "START", first.id(), null, null, "always", "", "", ""));
        }
        if (edges.stream().noneMatch(edge -> "END".equals(edge.to()))) {
            DraftNode last = nodes.get(nodes.size() - 1);
            edges.add(new DraftEdge("e-" + last.id() + "-end", last.id(), "END", null, null, "always", "", "", ""));
        }
    }

    private GraphSpec graphSpec(RuntimeWorkflowDraftGenerationRequest request, List<DraftNode> nodes, List<DraftEdge> edges) {
        GraphSpec.GraphSpecBuilder builder = GraphSpec.builder()
                .code(slug(firstText(request == null ? null : request.getAgentId(),
                        request == null ? null : request.getAgentName(), "ai_generated_graph")))
                .name(firstText(request == null ? null : request.getAgentName(), "AI 生成流程草稿"))
                .mode("WORKFLOW")
                .runtimeHint("LANGGRAPH4J")
                .layout(GraphSpec.Layout.builder().engine("vue-flow").direction("LR").build());

        for (DraftNode node : nodes) {
            builder.node(toGraphNode(node));
        }
        for (DraftEdge edge : edges) {
            builder.edge(GraphSpec.Edge.builder()
                    .id(edge.id())
                    .from(edge.from())
                    .to(edge.to())
                    .condition(firstText(edge.condition(), "always"))
                    .sourceHandle(blankToNull(edge.sourceHandle()))
                    .targetHandle(blankToNull(edge.targetHandle()))
                    .layout(GraphSpec.Layout.EdgeLayout.builder()
                            .label(firstText(edge.label(), edge.condition(), "always"))
                            .style("smoothstep")
                            .build())
                    .build());
        }

        String entry = edges.stream()
                .filter(edge -> "START".equals(edge.from()) && !"END".equals(edge.to()))
                .map(DraftEdge::to)
                .findFirst()
                .orElse(nodes.isEmpty() ? "" : nodes.get(0).id());
        builder.entry(entry);
        edges.stream()
                .filter(edge -> "END".equals(edge.to()) && !"START".equals(edge.from()))
                .map(DraftEdge::from)
                .distinct()
                .forEach(builder::finishNode);
        return builder.build();
    }

    private GraphSpec.Node toGraphNode(DraftNode node) {
        Map<String, Object> config = mutableMap(node.config());
        GraphSpec.Node.NodeBuilder builder = GraphSpec.Node.builder()
                .id(node.id())
                .type(node.type())
                .name(node.label())
                .description(node.description())
                .config(config)
                .layout(GraphSpec.Layout.NodeLayout.builder()
                        .x(doubleValue(config.get("x")))
                        .y(doubleValue(config.get("y")))
                        .collapsed(false)
                        .build());
        capabilityRef(node).ifPresent(builder::ref);
        for (Map<String, Object> input : graphPorts(node, "input")) {
            builder.input(toPort(input));
        }
        for (Map<String, Object> output : graphPorts(node, "output")) {
            builder.output(toPort(output));
        }
        builder.retry(GraphSpec.RetryPolicy.builder()
                .enabled(AgentGraphNodeType.find(node.type()).map(AgentGraphNodeType::retryable).orElse(false))
                .maxAttempts(1)
                .backoffMs(800L)
                .build());
        builder.errorPolicy(GraphSpec.ErrorPolicy.builder().strategy("TERMINATE").build());
        return builder.build();
    }

    private Map<String, Object> canvasSnapshot(GraphSpec graph, List<DraftNode> nodes, List<DraftEdge> edges) {
        List<Map<String, Object>> canvasNodes = new ArrayList<>();
        canvasNodes.add(canvasNode("start", "start", 60, 220, data("开始", "start", Map.of())));
        int index = 0;
        for (DraftNode node : nodes) {
            Map<String, Object> data = data(node.label(), node.kind(), canvasData(node));
            int x = 280 + index * 260;
            int y = 220;
            canvasNodes.add(canvasNode(node.id(), node.kind(), x, y, data));
            index++;
        }
        canvasNodes.add(canvasNode("end", "end", 300 + Math.max(nodes.size(), 1) * 260, 220, data("结束", "end", Map.of())));

        List<Map<String, Object>> canvasEdges = new ArrayList<>();
        int edgeIndex = 1;
        for (DraftEdge edge : edges) {
            canvasEdges.add(canvasEdge(
                    firstText(edge.id(), "e-" + edgeIndex++),
                    canvasEndpoint(edge.from()),
                    canvasEndpoint(edge.to()),
                    firstText(edge.condition(), "always"),
                    canvasRenderableSourceHandle(edge, nodes),
                    null));
        }
        return Map.of("version", 2, "nodes", canvasNodes, "edges", canvasEdges, "graphCode", graph.getCode());
    }

    private String canvasRenderableSourceHandle(DraftEdge edge, List<DraftNode> nodes) {
        String handle = blankToNull(edge.sourceHandle());
        if (!StringUtils.hasText(handle)) {
            handle = canvasSourceHandleFromCondition(edge);
        }
        if (!StringUtils.hasText(handle)) {
            return null;
        }
        String sourceId = canvasEndpoint(edge.from());
        if ("start".equals(sourceId)) {
            return null;
        }
        DraftNode source = nodes.stream()
                .filter(node -> node.id().equals(sourceId))
                .findFirst()
                .orElse(null);
        if (source == null) {
            return null;
        }
        return switch (canvasNodeKind(source)) {
            case "classifier", "condition", "approval", "loop" -> handle;
            default -> null;
        };
    }

    private String canvasSourceHandleFromCondition(DraftEdge edge) {
        String condition = firstText(edge.condition(), "");
        if (!StringUtils.hasText(condition) || "always".equalsIgnoreCase(condition)) {
            return null;
        }
        if ("else".equalsIgnoreCase(condition) || "default".equalsIgnoreCase(condition)) {
            return "else";
        }
        String lower = condition.toLowerCase(Locale.ROOT);
        if (lower.startsWith("route:")) {
            return condition.substring("route:".length()).trim();
        }
        return condition;
    }

    private String canvasNodeKind(DraftNode node) {
        return firstText(node.kind(), node.type(), "").trim().toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> canvasData(DraftNode node) {
        Map<String, Object> config = mutableMap(node.config());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("description", firstText(node.description(), ""));
        data.put("source", "CANVAS");
        data.put("category", AgentGraphNodeType.find(node.type()).map(AgentGraphNodeType::canvasCategory).orElse("flow"));
        data.put("inputs", graphPorts(node, "input"));
        data.put("outputs", graphPorts(node, "output"));
        data.put("inputSchema", mutableMap(config.get("inputSchema")));
        data.put("outputSchema", mutableMap(config.get("outputSchema")));
        data.put("inputMapping", mutableMap(config.get("inputMapping")));
        data.put("retry", Map.of("enabled", AgentGraphNodeType.find(node.type()).map(AgentGraphNodeType::retryable).orElse(false), "maxAttempts", 1, "backoffMs", 800));
        data.put("errorPolicy", Map.of("strategy", "TERMINATE"));
        data.put("collapsed", false);
        data.put("outputAlias", firstText(text(config.get("outputAlias")), defaultOutputAlias(node.kind())));
        data.put("needsConfiguration", bool(config.get("needsConfiguration")));
        data.put("placeholderReason", firstText(text(config.get("placeholderReason")), ""));

        switch (node.kind()) {
            case "userInput" -> data.put("userInputConfig", Map.of(
                    "fields", fields(config),
                    "outputAlias", firstText(text(config.get("outputAlias")), "params")));
            case "llm" -> data.put("llmConfig", llmConfig(config));
            case "tool", "skill" -> data.put("toolConfig", toolConfig(config));
            case "knowledge" -> data.put("knowledgeConfig", knowledgeConfig(config));
            case "answer" -> {
                data.put("answerConfig", Map.of("template", firstText(text(config.get("template")), "{{ lastOutput }}")));
                data.put("template", firstText(text(config.get("template")), "{{ lastOutput }}"));
                data.put("writeToAnswer", true);
            }
            case "classifier" -> {
                Map<String, Object> classifierConfig = new LinkedHashMap<>();
                classifierConfig.put("inputExpression", firstText(text(config.get("inputExpression")), "input"));
                classifierConfig.put("strategy", normalizeClassifierStrategy(text(config.get("strategy"))));
                classifierConfig.put("classes", classes(config));
                classifierConfig.put("defaultRoute", firstText(text(config.get("defaultRoute")), "else"));
                classifierConfig.put("modelInstanceId", firstText(text(config.get("modelInstanceId")), ""));
                classifierConfig.put("confidenceThreshold", doubleOr(config.get("confidenceThreshold"), 0.7D));
                classifierConfig.put("llmPrompt", firstText(text(config.get("llmPrompt")), ""));
                data.put("classifierConfig", classifierConfig);
            }
            case "approval" -> data.put("approvalConfig", Map.of(
                    "title", firstText(text(config.get("title")), node.label()),
                    "prompt", firstText(text(config.get("prompt")), "{{ lastOutput }}"),
                    "approvers", arrayValue(config.get("approvers")),
                    "timeoutSeconds", integer(config.get("timeoutSeconds"), 3600),
                    "defaultRoute", firstText(text(config.get("defaultRoute")), "approved")));
            case "pageAction" -> data.put("pageActionConfig", pageActionConfig(config, node.label()));
            case "parameter" -> data.put("parameterConfig", Map.of(
                    "mode", firstText(text(config.get("extractMode")), text(config.get("mode")), "expression"),
                    "modelInstanceId", firstText(text(config.get("modelInstanceId")), ""),
                    "fields", fields(config)));
            case "http" -> data.put("httpConfig", Map.of(
                    "method", firstText(text(config.get("method")), "GET"),
                    "url", firstText(text(config.get("url")), ""),
                    "queryParams", mutableMap(config.get("queryParams")),
                    "headers", mutableMap(config.get("headers")),
                    "bodyType", firstText(text(config.get("bodyType")), "none"),
                    "body", firstText(text(config.get("body")), ""),
                    "timeoutMs", integer(config.get("timeoutMs"), 30000),
                    "credentialRef", firstText(text(config.get("credentialRef")), "")));
            default -> data.putAll(genericConfig(node.kind(), config));
        }
        return data;
    }

    private void normalizeLlmConfig(Map<String, Object> config, RuntimeWorkflowDraftGenerationRequest request) {
        config.put("modelInstanceId", firstText(text(config.get("modelInstanceId")), request.getModelInstanceId()));
        config.put("systemPrompt", firstText(text(config.get("systemPrompt")), "你是企业流程中的专业任务处理节点，请根据输入完成当前节点职责。"));
        config.put("userPrompt", firstText(text(config.get("userPrompt")), "{{ input }}"));
        config.put("outputFormat", firstText(text(config.get("outputFormat")), "text"));
        config.put("structuredOutput", bool(config.get("structuredOutput")));
        config.put("strictJsonSchema", config.get("strictJsonSchema") == null || bool(config.get("strictJsonSchema")));
        config.put("contextVariables", arrayOrDefault(config.get("contextVariables"), List.of("input", "lastOutput")));
        config.putIfAbsent("modelParams", Map.of());
        config.put("messages", messages(config));
        config.put("promptTemplateMode", firstText(text(config.get("promptTemplateMode")), "messages"));
    }

    private void normalizeUserInputConfig(Map<String, Object> config) {
        config.put("outputAlias", firstText(text(config.get("outputAlias")), "params"));
        config.put("fields", fields(config).isEmpty()
                ? List.of(Map.of("name", "question", "type", "string", "required", true, "description", "用户问题", "source", "input.message"))
                : fields(config));
    }

    private void normalizeAnswerConfig(Map<String, Object> config) {
        config.put("template", firstText(text(config.get("template")), "{{ lastOutput }}"));
        config.put("writeToAnswer", true);
    }

    private void normalizeClassifierConfig(Map<String, Object> config) {
        config.put("inputExpression", firstText(text(config.get("inputExpression")), "input"));
        config.put("strategy", normalizeClassifierStrategy(text(config.get("strategy"))));
        config.put("classes", classes(config).isEmpty()
                ? List.of(Map.of("id", "matched", "label", "匹配", "keywords", List.of()))
                : classes(config));
        config.put("defaultRoute", firstText(text(config.get("defaultRoute")), "else"));
        config.put("modelInstanceId", firstText(text(config.get("modelInstanceId")), ""));
        config.put("confidenceThreshold", doubleOr(config.get("confidenceThreshold"), 0.7D));
        config.put("llmPrompt", firstText(text(config.get("llmPrompt")), ""));
    }

    private String normalizeClassifierStrategy(String raw) {
        String strategy = firstText(raw).toUpperCase();
        if ("LLM".equals(strategy) || "HYBRID".equals(strategy)) {
            return strategy;
        }
        return "KEYWORD";
    }

    private void normalizeApprovalConfig(Map<String, Object> config) {
        config.put("title", firstText(text(config.get("title")), "人工审批"));
        config.put("prompt", firstText(text(config.get("prompt")), "{{ lastOutput }}"));
        config.put("approvers", arrayValue(config.get("approvers")));
        config.put("timeoutSeconds", integer(config.get("timeoutSeconds"), 3600));
        config.put("defaultRoute", firstText(text(config.get("defaultRoute")), "approved"));
    }

    private void normalizeKnowledgeConfig(Map<String, Object> config,
                                          RuntimeWorkflowDraftGenerationRequest request,
                                          Map<String, RuntimeWorkflowDraftResourceView> resources,
                                          List<String> warnings,
                                          List<RuntimeWorkflowDraftPlaceholderView> placeholders,
                                          String nodeId,
                                          String label) {
        String ref = firstText(text(config.get("ref")), text(config.get("name")), firstString(arrayValue(config.get("knowledgeBaseCodes"))));
        RuntimeWorkflowDraftResourceView resource = resource(ref, resources);
        if (resource == null && StringUtils.hasText(ref)) {
            markPlaceholder(config, warnings, placeholders, nodeId, "knowledge", label, "未找到知识库：" + ref);
        }
        config.put("knowledgeBaseCodes", resource == null ? List.of(ref) : List.of(resource.getName()));
        config.put("query", firstText(text(config.get("query")), "input"));
        config.put("topK", integer(config.get("topK"), 5));
        config.put("similarityThreshold", doubleOr(config.get("similarityThreshold"), 0.5D));
        config.put("searchMode", firstText(text(config.get("searchMode")), "hybrid"));
        config.put("rerankEnabled", config.get("rerankEnabled") == null || bool(config.get("rerankEnabled")));
        config.put("directReturnEnabled", bool(config.get("directReturnEnabled")));
        config.put("directReturnThreshold", doubleOr(config.get("directReturnThreshold"), 0.85D));
    }

    private void normalizeCapabilityConfig(Map<String, Object> config,
                                           AgentGraphNodeType type,
                                           RuntimeWorkflowDraftGenerationRequest request,
                                           Map<String, RuntimeWorkflowDraftResourceView> resources,
                                           List<String> warnings,
                                           List<RuntimeWorkflowDraftPlaceholderView> placeholders,
                                           String nodeId,
                                           String label) {
        String ref = firstText(text(config.get("ref")), text(config.get("name")), text(config.get("qualifiedName")));
        RuntimeWorkflowDraftResourceView resource = resource(ref, resources);
        if (resource == null) {
            markPlaceholder(config, warnings, placeholders, nodeId, type.canvasKind(), label,
                    StringUtils.hasText(ref) ? "未找到可绑定能力：" + ref : "模型生成了需要绑定的能力节点，请选择 Tool/Capability");
        } else {
            config.put("ref", resource.getName());
            config.put("qualifiedName", firstText(resource.getQualifiedName(), resource.getName()));
            config.put("definitionId", resource.getDefinitionId());
            config.put("projectCode", firstText(resource.getProjectCode(), request.getProjectCode()));
        }
        config.putIfAbsent("inputMapping", Map.of("input", "$lastOutput"));
    }

    private void normalizePageActionConfig(Map<String, Object> config,
                                           RuntimeWorkflowDraftGenerationRequest request,
                                           Map<String, RuntimeWorkflowDraftResourceView> resources,
                                           List<String> warnings,
                                           List<RuntimeWorkflowDraftPlaceholderView> placeholders,
                                           String nodeId,
                                           String label,
                                           Set<String> usedPageActions,
                                           int pageActionNodeIndex,
                                           List<String> validationErrors) {
        String ref = firstText(text(config.get("ref")), text(config.get("name")), text(config.get("qualifiedName")),
                text(config.get("actionKey")), text(config.get("action")));
        RuntimeWorkflowDraftResourceView resource = resource(ref, resources);
        if (resource == null && StringUtils.hasText(ref) && isPageAssistantDraft(request)) {
            markPlaceholder(config, warnings, placeholders, nodeId, "pageAction", label,
                    "页面动作未在本次选择/注册的 actionKeys 中：" + ref);
            validationErrors.add("PAGE_ASSISTANT pageAction is not supplied by selected pageActions: " + ref);
            config.put("args", mutableMap(config.get("args")));
            config.put("outputAlias", firstText(text(config.get("outputAlias")), "page_action_result"));
            return;
        }
        if (resource == null) {
            resource = singlePageActionResource(request);
        }
        if (resource == null) {
            resource = matchPageActionResource(request, nodeId, label, text(config.get("description")), usedPageActions);
        }
        if (resource == null) {
            resource = fallbackPageActionByOrder(request, usedPageActions, pageActionNodeIndex);
        }
        if (resource == null) {
            markPlaceholder(config, warnings, placeholders, nodeId, "pageAction", label,
                    StringUtils.hasText(ref) ? "未找到页面动作：" + ref : "模型生成了页面动作节点，请选择页面和 actionKey");
        } else {
            Map<String, Object> metadata = mutableMap(resource.getMetadata());
            config.put("projectCode", firstText(text(metadata.get("projectCode")), resource.getProjectCode(), request.getProjectCode()));
            config.put("pageKey", firstText(text(metadata.get("pageKey")), text(config.get("pageKey"))));
            config.put("routePattern", firstText(text(metadata.get("routePattern")), text(config.get("routePattern"))));
            config.put("actionKey", firstText(text(metadata.get("actionKey")), resource.getName(), ref));
            config.put("title", firstText(text(config.get("title")), label, resource.getDescription(), resource.getName()));
            config.put("confirm", boolOr(metadata.get("confirmRequired"), bool(config.get("confirm"))));
            config.putIfAbsent("metadata", Map.of(
                    "source", "AI_DRAFT",
                    "inputSchema", mutableMap(metadata.get("inputSchema")),
                    "outputSchema", mutableMap(metadata.get("outputSchema")),
                    "sampleArgs", mutableMap(metadata.get("sampleArgs"))));
            usedPageActions.add(firstText(text(metadata.get("actionKey")), resource.getName()));
            if (!StringUtils.hasText(ref)) {
                warnings.add(label + "：已根据节点语义自动绑定页面动作 " + resource.getName());
            }
        }
        config.put("args", mutableMap(config.get("args")));
        config.put("outputAlias", firstText(text(config.get("outputAlias")), "page_action_result"));
    }

    private RuntimeWorkflowDraftResourceView matchPageActionResource(RuntimeWorkflowDraftGenerationRequest request,
                                                          String nodeId,
                                                          String label,
                                                          String description,
                                                          Set<String> usedPageActions) {
        if (request == null || request.getPageActions() == null || request.getPageActions().isEmpty()) {
            return null;
        }
        String compactId = compactToken(nodeId);
        String compactLabel = compactToken(firstText(label, description, nodeId));
        RuntimeWorkflowDraftResourceView best = null;
        int bestScore = 0;
        for (RuntimeWorkflowDraftResourceView candidate : request.getPageActions()) {
            if (candidate == null || usedPageActions.contains(candidate.getName())) {
                continue;
            }
            int score = scorePageActionCandidate(candidate, compactId, compactLabel, label, description);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return bestScore >= 4 ? best : null;
    }

    private RuntimeWorkflowDraftResourceView fallbackPageActionByOrder(RuntimeWorkflowDraftGenerationRequest request,
                                                            Set<String> usedPageActions,
                                                            int pageActionNodeIndex) {
        if (request == null || request.getPageActions() == null) {
            return null;
        }
        List<RuntimeWorkflowDraftResourceView> available = request.getPageActions().stream()
                .filter(Objects::nonNull)
                .filter(resource -> !usedPageActions.contains(resource.getName()))
                .toList();
        if (available.isEmpty()) {
            return null;
        }
        List<String> preferredOrder = List.of("setFilters", "search", "reset", "readTable", "getPageState", "openRowAction");
        for (String preferred : preferredOrder) {
            for (RuntimeWorkflowDraftResourceView resource : available) {
                if (preferred.equalsIgnoreCase(resource.getName())) {
                    return resource;
                }
            }
        }
        return pageActionNodeIndex < available.size() ? available.get(pageActionNodeIndex) : available.get(0);
    }

    private int scorePageActionCandidate(RuntimeWorkflowDraftResourceView candidate,
                                         String compactId,
                                         String compactLabel,
                                         String label,
                                         String description) {
        String actionKey = firstText(candidate.getName());
        String compactKey = compactToken(actionKey);
        int score = 0;
        if (StringUtils.hasText(compactKey)) {
            if (compactId.contains(compactKey) || compactKey.contains(compactId)) {
                score += 12;
            }
            if (compactLabel.contains(compactKey) || compactKey.contains(compactLabel)) {
                score += 8;
            }
        }
        score += semanticPageActionScore(label, description, actionKey);
        String title = firstText(candidate.getDescription(), text(mutableMap(candidate.getMetadata()).get("title")));
        if (StringUtils.hasText(title) && StringUtils.hasText(label)) {
            if (label.contains(title) || title.contains(label)) {
                score += 6;
            }
        }
        return score;
    }

    private int semanticPageActionScore(String label, String description, String actionKey) {
        String haystack = (firstText(label, "") + " " + firstText(description, "")).toLowerCase(Locale.ROOT);
        String compactKey = compactToken(actionKey);
        int score = 0;
        if ("setfilters".equals(compactKey) || actionKey.toLowerCase(Locale.ROOT).contains("filter")) {
            if (haystack.contains("筛选") || haystack.contains("filter")) {
                score += 10;
            }
        }
        if ("search".equals(compactKey) || actionKey.toLowerCase(Locale.ROOT).contains("search")) {
            if (haystack.contains("查询") || haystack.contains("搜索") || haystack.contains("search")) {
                score += 10;
            }
        }
        if (actionKey.toLowerCase(Locale.ROOT).contains("reset")) {
            if (haystack.contains("重置") || haystack.contains("reset")) {
                score += 10;
            }
        }
        if ("readtable".equals(compactKey) || actionKey.toLowerCase(Locale.ROOT).contains("read")) {
            if (haystack.contains("表格") || haystack.contains("读取") || haystack.contains("table") || haystack.contains("read")) {
                score += 10;
            }
        }
        if (actionKey.toLowerCase(Locale.ROOT).contains("open") || actionKey.toLowerCase(Locale.ROOT).contains("row")) {
            if (haystack.contains("打开") || haystack.contains("行操作") || haystack.contains("open")) {
                score += 10;
            }
        }
        return score;
    }

    private String compactToken(String value) {
        return slug(value).replace("_", "").toLowerCase(Locale.ROOT);
    }

    private RuntimeWorkflowDraftResourceView singlePageActionResource(RuntimeWorkflowDraftGenerationRequest request) {
        if (request == null || request.getPageActions() == null || request.getPageActions().size() != 1) {
            return null;
        }
        return request.getPageActions().get(0);
    }

    private void normalizeReferencedOutputAliases(List<DraftNode> nodes) {
        Set<String> knownAliases = new LinkedHashSet<>();
        for (DraftNode node : nodes) {
            knownAliases.add(node.id());
            String outputAlias = text(node.config() == null ? null : node.config().get("outputAlias"));
            if (StringUtils.hasText(outputAlias)) {
                knownAliases.add(outputAlias);
            }
        }

        for (int index = 0; index < nodes.size(); index++) {
            DraftNode node = nodes.get(index);
            if (!"pageAction".equals(node.kind())) {
                continue;
            }
            Set<String> referencedAliases = new LinkedHashSet<>();
            collectReferenceAliases(node.config() == null ? null : node.config().get("args"), referencedAliases);
            if (node.inputs() != null) {
                for (Map<String, Object> input : node.inputs()) {
                    collectReferenceAliases(input == null ? null : input.get("source"), referencedAliases);
                }
            }

            for (String alias : referencedAliases) {
                if (knownAliases.contains(alias)) {
                    continue;
                }
                DraftNode upstream = nearestExtractLikeNode(nodes, index);
                if (upstream == null) {
                    continue;
                }
                Map<String, Object> upstreamConfig = upstream.config();
                if (upstreamConfig == null) {
                    continue;
                }
                if (!StringUtils.hasText(text(upstreamConfig.get("outputAlias")))) {
                    upstreamConfig.put("outputAlias", alias);
                    knownAliases.add(alias);
                }
            }
        }
    }

    private DraftNode nearestExtractLikeNode(List<DraftNode> nodes, int beforeIndex) {
        for (int i = beforeIndex - 1; i >= 0; i--) {
            DraftNode candidate = nodes.get(i);
            if ("llm".equals(candidate.kind()) || "parameter".equals(candidate.kind())) {
                return candidate;
            }
        }
        return null;
    }

    private void collectReferenceAliases(Object value, Set<String> aliases) {
        if (value == null) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                collectReferenceAliases(item, aliases);
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                collectReferenceAliases(item, aliases);
            }
            return;
        }
        String raw = text(value);
        for (String token : raw.split("[^A-Za-z0-9_.$-]+")) {
            String normalized = token.replaceFirst("^\\$+", "");
            if (!normalized.contains(".")) {
                continue;
            }
            String alias = normalized.split("\\.", 2)[0];
            if (StringUtils.hasText(alias) && !isBuiltinVariableAlias(alias)) {
                aliases.add(alias);
            }
        }
    }

    private boolean isBuiltinVariableAlias(String alias) {
        return Set.of("input", "answer", "lastOutput", "previousOutput", "lastRoute", "lastSuccess", "lastError", "params", "sys", "var", "nodeOutput")
                .contains(alias);
    }

    private void markPlaceholder(Map<String, Object> config,
                                 List<String> warnings,
                                 List<RuntimeWorkflowDraftPlaceholderView> placeholders,
                                 String nodeId,
                                 String kind,
                                 String label,
                                 String reason) {
        config.put("needsConfiguration", true);
        config.put("placeholderReason", reason);
        warnings.add(label + "：" + reason);
        placeholders.add(new RuntimeWorkflowDraftPlaceholderView(nodeId, kind, label, reason));
    }

    private java.util.Optional<GraphSpec.CapabilityRef> capabilityRef(DraftNode node) {
        if (!"TOOL".equals(node.type()) && !"CAPABILITY".equals(node.type()) && !"MCP_CALL".equals(node.type()) && !"HTTP_REQUEST".equals(node.type())) {
            return java.util.Optional.empty();
        }
        Map<String, Object> config = mutableMap(node.config());
        String name = firstText(text(config.get("ref")), text(config.get("name")));
        if (!StringUtils.hasText(name) || bool(config.get("needsConfiguration"))) {
            return java.util.Optional.empty();
        }
        String kind = "CAPABILITY".equals(node.type()) ? "CAPABILITY" : "TOOL";
        return java.util.Optional.of(GraphSpec.CapabilityRef.builder()
                .kind(kind)
                .name(name)
                .qualifiedName(text(config.get("qualifiedName")))
                .definitionId(longValue(config.get("definitionId")))
                .projectCode(text(config.get("projectCode")))
                .build());
    }

    private List<Map<String, Object>> graphPorts(DraftNode node, String direction) {
        List<Map<String, Object>> explicit = "input".equals(direction) ? node.inputs() : node.outputs();
        if (explicit != null && !explicit.isEmpty()) {
            return explicit;
        }
        Map<String, Object> config = mutableMap(node.config());
        if ("userInput".equals(node.kind()) && "output".equals(direction)) {
            return userInputOutputPorts(fields(config), firstText(text(config.get("outputAlias")), "params"));
        }
        if ("classifier".equals(node.kind()) && "output".equals(direction)) {
            return classifierOutputPorts(config);
        }
        if ("answer".equals(node.kind())) {
            return "input".equals(direction)
                    ? List.of(Map.of("id", "input", "name", "input", "type", "message", "required", false, "source", "$lastOutput"))
                    : List.of(Map.of("id", "answer", "name", "answer", "type", "message"));
        }
        if ("approval".equals(node.kind()) && "output".equals(direction)) {
            return List.of(
                    Map.of("id", "approved", "name", "approved", "type", "boolean"),
                    Map.of("id", "rejected", "name", "rejected", "type", "boolean"),
                    Map.of("id", "timeout", "name", "timeout", "type", "boolean"));
        }
        if ("input".equals(direction)) {
            return List.of(Map.of("id", "input", "name", "input", "type", "message", "required", false, "source", "$input"));
        }
        String output = firstText(text(config.get("outputAlias")), defaultOutputAlias(node.kind()), "output");
        return List.of(Map.of("id", output, "name", output, "type", "any"));
    }

    private List<Map<String, Object>> userInputOutputPorts(List<Map<String, Object>> fields, String alias) {
        List<Map<String, Object>> ports = new ArrayList<>();
        String outputAlias = firstText(alias, "params");
        ports.add(Map.of("id", outputAlias, "name", outputAlias, "type", "object", "required", false));
        for (Map<String, Object> field : fields) {
            String name = text(field.get("name"));
            if (!StringUtils.hasText(name)) continue;
            ports.add(Map.of(
                    "id", outputAlias + "." + name,
                    "name", outputAlias + "." + name,
                    "type", firstText(text(field.get("type")), "string"),
                    "required", bool(field.get("required")),
                    "source", outputAlias));
        }
        return ports;
    }

    private List<Map<String, Object>> classifierOutputPorts(Map<String, Object> config) {
        List<Map<String, Object>> ports = new ArrayList<>();
        for (Map<String, Object> item : classes(config)) {
            String id = text(item.get("id"));
            if (StringUtils.hasText(id)) {
                ports.add(Map.of("id", id, "name", firstText(text(item.get("label")), id), "type", "boolean", "required", false));
            }
        }
        String defaultRoute = firstText(text(config.get("defaultRoute")), "else");
        if (ports.stream().noneMatch(port -> defaultRoute.equals(port.get("id")))) {
            ports.add(Map.of("id", defaultRoute, "name", defaultRoute, "type", "boolean", "required", false));
        }
        return ports;
    }

    private GraphSpec.Port toPort(Map<String, Object> map) {
        return GraphSpec.Port.builder()
                .id(text(map.get("id")))
                .name(text(map.get("name")))
                .type(text(map.get("type")))
                .required(bool(map.get("required")))
                .schema(text(map.get("schema")))
                .source(text(map.get("source")))
                .build();
    }

    private Map<String, Object> llmConfig(Map<String, Object> config) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modelInstanceId", firstText(text(config.get("modelInstanceId")), ""));
        out.put("systemPrompt", firstText(text(config.get("systemPrompt")), ""));
        out.put("userPrompt", firstText(text(config.get("userPrompt")), "{{ input }}"));
        out.put("outputFormat", firstText(text(config.get("outputFormat")), "text"));
        out.put("structuredOutput", bool(config.get("structuredOutput")));
        out.put("strictJsonSchema", config.get("strictJsonSchema") == null || bool(config.get("strictJsonSchema")));
        out.put("messages", messages(config));
        out.put("contextVariables", arrayOrDefault(config.get("contextVariables"), List.of("input", "lastOutput")));
        out.put("modelParams", mutableMap(config.get("modelParams")));
        out.put("visionEnabled", bool(config.get("visionEnabled")));
        out.put("visionInputs", arrayValue(config.get("visionInputs")));
        out.put("promptTemplateMode", firstText(text(config.get("promptTemplateMode")), "messages"));
        return out;
    }

    private List<Map<String, Object>> messages(Map<String, Object> config) {
        Object raw = config.get("messages");
        if (raw instanceof List<?> list && !list.isEmpty()) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(this::mutableMap)
                    .toList();
        }
        return List.of(
                Map.of("id", "system", "role", "system", "content", firstText(text(config.get("systemPrompt")), ""), "templateEngine", "mustache", "enabled", true),
                Map.of("id", "user", "role", "user", "content", firstText(text(config.get("userPrompt")), "{{ input }}"), "templateEngine", "mustache", "enabled", true));
    }

    private Map<String, Object> toolConfig(Map<String, Object> config) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ref", firstText(text(config.get("ref")), ""));
        out.put("qualifiedName", firstText(text(config.get("qualifiedName")), ""));
        out.put("projectCode", firstText(text(config.get("projectCode")), ""));
        out.put("definitionId", config.get("definitionId"));
        out.put("credentialRef", firstText(text(config.get("credentialRef")), ""));
        out.put("inputMapping", mutableMap(config.get("inputMapping")));
        out.put("mappingNote", firstText(text(config.get("mappingNote")), ""));
        return out;
    }

    private Map<String, Object> knowledgeConfig(Map<String, Object> config) {
        return new LinkedHashMap<>(Map.of(
                "knowledgeBaseCodes", arrayValue(config.get("knowledgeBaseCodes")),
                "query", firstText(text(config.get("query")), "input"),
                "topK", integer(config.get("topK"), 5),
                "similarityThreshold", doubleOr(config.get("similarityThreshold"), 0.5D),
                "searchMode", firstText(text(config.get("searchMode")), "hybrid"),
                "rerankEnabled", config.get("rerankEnabled") == null || bool(config.get("rerankEnabled")),
                "directReturnEnabled", bool(config.get("directReturnEnabled")),
                "directReturnThreshold", doubleOr(config.get("directReturnThreshold"), 0.85D)));
    }

    private Map<String, Object> pageActionConfig(Map<String, Object> config, String label) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectCode", firstText(text(config.get("projectCode")), ""));
        out.put("pageKey", firstText(text(config.get("pageKey")), ""));
        out.put("routePattern", firstText(text(config.get("routePattern")), ""));
        out.put("actionKey", firstText(text(config.get("actionKey")), ""));
        out.put("title", firstText(text(config.get("title")), label, text(config.get("actionKey"))));
        out.put("confirm", bool(config.get("confirm")));
        out.put("args", mutableMap(config.get("args")));
        out.put("metadata", mutableMap(config.get("metadata")));
        out.put("outputAlias", firstText(text(config.get("outputAlias")), "page_action_result"));
        return out;
    }

    private Map<String, Object> genericConfig(String kind, Map<String, Object> config) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (kind) {
            case "condition" -> out.put("conditionConfig", Map.of(
                    "groups", config.getOrDefault("conditionGroups", List.of()),
                    "defaultRoute", firstText(text(config.get("defaultRoute")), "else")));
            case "aggregate" -> out.put("aggregateConfig", Map.of(
                    "mode", firstText(text(config.get("aggregateMode")), "object"),
                    "items", config.getOrDefault("items", List.of()),
                    "template", firstText(text(config.get("template")), "")));
            case "loop" -> out.put("loopConfig", Map.of(
                    "loopKey", firstText(text(config.get("loopKey")), "loop"),
                    "maxIterations", integer(config.get("maxIterations"), 3),
                    "itemExpression", firstText(text(config.get("itemExpression")), ""),
                    "breakCondition", firstText(text(config.get("breakCondition")), "")));
            case "code" -> out.put("codeConfig", Map.of(
                    "language", "expression",
                    "code", firstText(text(config.get("code")), ""),
                    "outputs", mutableMap(config.get("outputs"))));
            case "variable" -> out.put("assignments", mutableMap(config.get("assignments")));
            case "template" -> {
                out.put("template", firstText(text(config.get("template")), "{{ lastOutput }}"));
                out.put("writeToAnswer", config.get("writeToAnswer") == null || bool(config.get("writeToAnswer")));
            }
            default -> {
            }
        }
        return out;
    }

    private Map<String, RuntimeWorkflowDraftResourceView> resourceIndex(RuntimeWorkflowDraftGenerationRequest request) {
        Map<String, RuntimeWorkflowDraftResourceView> out = new LinkedHashMap<>();
        for (RuntimeWorkflowDraftResourceView resource : resources(request)) {
            if (resource == null) continue;
            indexResource(out, resource.getName(), resource);
            indexResource(out, resource.getQualifiedName(), resource);
            indexResource(out, resource.getDescription(), resource);
        }
        return out;
    }

    private List<RuntimeWorkflowDraftResourceView> resources(RuntimeWorkflowDraftGenerationRequest request) {
        List<RuntimeWorkflowDraftResourceView> resources = new ArrayList<>();
        if (request.getTools() != null) resources.addAll(request.getTools());
        if (request.getCapabilities() != null) resources.addAll(request.getCapabilities());
        if (request.getKnowledgeBases() != null) resources.addAll(request.getKnowledgeBases());
        if (request.getPageActions() != null) resources.addAll(request.getPageActions());
        return resources;
    }

    private void indexResource(Map<String, RuntimeWorkflowDraftResourceView> out, String key, RuntimeWorkflowDraftResourceView resource) {
        if (StringUtils.hasText(key)) {
            out.put(key(key), resource);
            String compact = compactToken(key);
            if (StringUtils.hasText(compact)) {
                out.putIfAbsent(compact, resource);
            }
        }
    }

    private RuntimeWorkflowDraftResourceView resource(String ref, Map<String, RuntimeWorkflowDraftResourceView> resources) {
        if (!StringUtils.hasText(ref)) return null;
        RuntimeWorkflowDraftResourceView hit = resources.get(key(ref));
        if (hit != null) {
            return hit;
        }
        return resources.get(compactToken(ref));
    }

    private boolean backfillPageAssistantActionsWhenMissing(RuntimeWorkflowDraftGenerationRequest request,
                                                            List<DraftNode> nodes,
                                                            List<String> warnings) {
        if (request == null || request.getPageActions() == null || request.getPageActions().isEmpty()) {
            return false;
        }
        boolean hasPageAction = nodes.stream().anyMatch(node -> "pageAction".equals(node.kind()));
        if (hasPageAction) {
            return false;
        }
        if ("INTENT_ROUTER".equals(pageAssistantFlowMode(request))) {
            warnings.add("页面助手存在多个互斥意图，模型未生成 INTENT_CLASSIFIER 分支；请按 requirement 中的 intentClasses 手动补充分类节点");
            return false;
        }

        List<DraftNode> actionNodes = pageAssistantActionNodes(request);
        if (actionNodes.isEmpty()) {
            return false;
        }

        int insertAt = firstNodeIndex(nodes, "answer");
        if (insertAt < 0) {
            insertAt = nodes.size();
        }
        nodes.addAll(insertAt, actionNodes);
        warnings.add("页面助手草稿未包含页面动作节点，已按已选 actionKeys 自动补齐执行链路");
        return true;
    }

    private List<DraftNode> pageAssistantActionNodes(RuntimeWorkflowDraftGenerationRequest request) {
        List<DraftNode> nodes = new ArrayList<>();
        if (hasPageAction(request, "setFilters")) {
            nodes.add(new DraftNode(
                    "extract_filters",
                    "llm",
                    "LLM",
                    "提取筛选条件",
                    "从用户问题提取页面筛选条件",
                    new LinkedHashMap<>(Map.of(
                            "configVersion", 2,
                            "source", "AI_DRAFT",
                            "outputAlias", "extracted_filters")),
                    List.of(),
                    List.of()));
        }
        Set<String> added = new LinkedHashSet<>();
        List<String> preferred = List.of("setFilters", "search", "readTable", "getPageState", "reset", "openRowAction");
        for (String actionKey : preferred) {
            RuntimeWorkflowDraftResourceView resource = findPageActionResource(request, actionKey);
            if (resource != null) {
                nodes.add(pageAssistantActionNode(request, resource));
                added.add(compactToken(actionResourceKey(resource)));
            }
        }
        for (RuntimeWorkflowDraftResourceView resource : request.getPageActions()) {
            if (resource == null) {
                continue;
            }
            String compact = compactToken(actionResourceKey(resource));
            if (added.contains(compact)) {
                continue;
            }
            nodes.add(pageAssistantActionNode(request, resource));
            added.add(compact);
        }
        return nodes;
    }

    private DraftNode pageAssistantActionNode(RuntimeWorkflowDraftGenerationRequest request, RuntimeWorkflowDraftResourceView resource) {
        Map<String, Object> metadata = mutableMap(resource.getMetadata());
        String actionKey = actionResourceKey(resource);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("configVersion", 2);
        config.put("source", "AI_DRAFT");
        config.put("projectCode", firstText(text(metadata.get("projectCode")), resource.getProjectCode(), request.getProjectCode()));
        config.put("pageKey", text(metadata.get("pageKey")));
        config.put("routePattern", text(metadata.get("routePattern")));
        config.put("actionKey", actionKey);
        config.put("title", firstText(resource.getDescription(), actionKey));
        config.put("confirm", bool(metadata.get("confirmRequired")));
        config.put("args", Map.of());
        config.put("outputAlias", "page_action_result");
        config.put("metadata", Map.of(
                "source", "AI_DRAFT",
                "inputSchema", mutableMap(metadata.get("inputSchema")),
                "outputSchema", mutableMap(metadata.get("outputSchema")),
                "sampleArgs", mutableMap(metadata.get("sampleArgs"))));
        return new DraftNode(
                pageAssistantActionNodeId(actionKey),
                "pageAction",
                "PAGE_ACTION",
                firstText(resource.getDescription(), actionKey),
                firstText(resource.getDescription(), actionKey),
                config,
                List.of(),
                List.of());
    }

    private String actionResourceKey(RuntimeWorkflowDraftResourceView resource) {
        if (resource == null) {
            return "";
        }
        Map<String, Object> metadata = mutableMap(resource.getMetadata());
        return firstText(text(metadata.get("actionKey")), resource.getName());
    }

    private String pageAssistantActionNodeId(String actionKey) {
        String compact = compactToken(actionKey);
        if ("setfilters".equals(compact)) {
            return "set_filters";
        }
        if ("readtable".equals(compact)) {
            return "read_table";
        }
        if ("getpagestate".equals(compact)) {
            return "get_page_state";
        }
        if ("openrowaction".equals(compact)) {
            return "open_row_action";
        }
        return slug(actionKey);
    }

    private int firstNodeIndex(List<DraftNode> nodes, String kind) {
        for (int i = 0; i < nodes.size(); i++) {
            if (kind.equals(nodes.get(i).kind())) {
                return i;
            }
        }
        return -1;
    }

    private void normalizePageAssistantDraft(RuntimeWorkflowDraftGenerationRequest request,
                                           List<DraftNode> nodes,
                                           List<String> warnings) {
        if (nodes.isEmpty()) {
            return;
        }
        DraftNode extractNode = findPageAssistantExtractNode(nodes);
        String extractAlias = extractNode == null
                ? "extracted_filters"
                : normalizePageAssistantExtractNode(extractNode, request, warnings);
        String extractNodeId = extractNode == null ? "extract_filters" : extractNode.id();
        for (DraftNode node : nodes) {
            if (!"pageAction".equals(node.kind())) {
                continue;
            }
            String actionKey = firstText(text(node.config().get("actionKey")), text(node.config().get("action")));
            if (isSetFiltersAction(actionKey)) {
                ensureSetFiltersArgs(node, extractNodeId, extractAlias, request, warnings);
            }
        }
        normalizePageAssistantAnswerNodes(nodes);
    }

    private DraftNode findPageAssistantExtractNode(List<DraftNode> nodes) {
        int firstPageActionIndex = -1;
        for (int i = 0; i < nodes.size(); i++) {
            if ("pageAction".equals(nodes.get(i).kind())) {
                firstPageActionIndex = i;
                break;
            }
        }
        if (firstPageActionIndex <= 0) {
            return null;
        }
        for (int i = firstPageActionIndex - 1; i >= 0; i--) {
            DraftNode candidate = nodes.get(i);
            if ("userInput".equals(candidate.kind())) {
                continue;
            }
            if ("llm".equals(candidate.kind()) || "parameter".equals(candidate.kind())) {
                return candidate;
            }
        }
        return null;
    }

    private String normalizePageAssistantExtractNode(DraftNode node,
                                                     RuntimeWorkflowDraftGenerationRequest request,
                                                     List<String> warnings) {
        Map<String, Object> config = node.config();
        String alias = firstText(text(config.get("outputAlias")), "extracted_filters");
        config.put("outputAlias", alias);
        List<Map<String, Object>> filterFields = filterFieldDefinitions(request);
        String fieldGuide = buildFilterFieldGuide(filterFields);
        String systemPrompt = buildPageAssistantExtractSystemPrompt(fieldGuide);
        config.put("systemPrompt", systemPrompt);
        if (!filterFields.isEmpty()) {
            config.put("fields", filterFields);
        }
        config.put("userPrompt", "{{ params.question }}");
        config.put("outputFormat", "json");
        config.put("structuredOutput", true);
        config.put("strictJsonSchema", true);
        config.put("contextVariables", List.of("input", "lastOutput", "params"));
        config.put("messages", List.of(
                Map.of("id", "system", "role", "system", "content", systemPrompt, "templateEngine", "mustache", "enabled", true),
                Map.of("id", "user", "role", "user", "content", "{{ params.question }}", "templateEngine", "mustache", "enabled", true)));
        config.put("promptTemplateMode", "messages");
        warnings.add(node.label() + "：已按 setFilters 的 inputSchema 自动补强筛选提取 prompt");
        return alias;
    }

    private void ensureSetFiltersArgs(DraftNode node,
                                      String extractNodeId,
                                      String extractAlias,
                                      RuntimeWorkflowDraftGenerationRequest request,
                                      List<String> warnings) {
        Map<String, Object> config = node.config();
        Map<String, Object> args = mutableMap(config.get("args"));
        if (!args.isEmpty() && args.values().stream().anyMatch(value -> StringUtils.hasText(text(value)))) {
            rewriteSetFiltersArgAliases(args, extractNodeId, extractAlias);
            config.put("args", args);
            return;
        }
        List<String> fields = filterFieldNames(request);
        if (fields.isEmpty()) {
            warnings.add(node.label() + "：setFilters 未提供 inputSchema/sampleArgs，无法自动生成 args");
            return;
        }
        Map<String, Object> wired = new LinkedHashMap<>();
        for (String field : fields) {
            wired.put(field, "nodeOutput." + extractNodeId + "." + field);
        }
        config.put("args", wired);
        warnings.add(node.label() + "：已根据 setFilters inputSchema 自动生成 args 映射");
    }

    private void rewriteSetFiltersArgAliases(Map<String, Object> args, String extractNodeId, String extractAlias) {
        args.replaceAll((key, value) -> {
            String raw = text(value);
            if (!StringUtils.hasText(raw)) {
                return "nodeOutput." + extractNodeId + "." + key;
            }
            String normalized = raw.replace("{{", "").replace("}}", "").trim();
            if (normalized.startsWith("nodeOutput.")) {
                return normalized;
            }
            int dot = normalized.indexOf('.');
            if (dot <= 0) {
                if (normalized.equals(extractAlias) || normalized.equals(extractNodeId)) {
                    return "nodeOutput." + extractNodeId + "." + key;
                }
                return raw.contains("{{") ? "nodeOutput." + extractNodeId + "." + key : raw;
            }
            String suffix = normalized.substring(dot + 1);
            if (!StringUtils.hasText(suffix)) {
                return "nodeOutput." + extractNodeId + "." + key;
            }
            return "nodeOutput." + extractNodeId + "." + suffix;
        });
    }

    private void normalizePageAssistantAnswerNodes(List<DraftNode> nodes) {
        boolean hasPageAction = nodes.stream().anyMatch(node -> "pageAction".equals(node.kind()));
        if (!hasPageAction) {
            return;
        }
        for (DraftNode node : nodes) {
            if (!"answer".equals(node.kind())) {
                continue;
            }
            Map<String, Object> config = node.config();
            String template = text(config.get("template"));
            if (!StringUtils.hasText(template) || template.contains("lastOutput")) {
                config.put("template", "正在按你的条件查询页面数据，请稍候…");
            }
        }
    }

    private List<String> filterFieldNames(RuntimeWorkflowDraftGenerationRequest request) {
        List<Map<String, Object>> definitions = filterFieldDefinitions(request);
        if (!definitions.isEmpty()) {
            return definitions.stream()
                    .map(field -> text(field.get("name")))
                    .filter(StringUtils::hasText)
                    .sorted()
                    .toList();
        }
        return List.of();
    }

    private List<Map<String, Object>> filterFieldDefinitions(RuntimeWorkflowDraftGenerationRequest request) {
        RuntimeWorkflowDraftResourceView setFilters = findPageActionResource(request, "setFilters");
        if (setFilters == null) {
            return List.of();
        }
        Map<String, Object> metadata = mutableMap(setFilters.getMetadata());
        Map<String, Object> inputSchema = mutableMap(metadata.get("inputSchema"));
        Map<String, Object> properties = mutableMap(inputSchema.get("properties"));
        Map<String, Object> sampleArgs = mutableMap(metadata.get("sampleArgs"));
        Set<String> names = new java.util.TreeSet<>();
        if (!properties.isEmpty()) {
            names.addAll(properties.keySet());
        }
        names.addAll(sampleArgs.keySet());
        List<Map<String, Object>> fields = new ArrayList<>();
        for (String name : names) {
            if (!StringUtils.hasText(name)) {
                continue;
            }
            Map<String, Object> property = mutableMap(properties.get(name));
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", name);
            field.put("key", name);
            field.put("type", firstText(text(property.get("type")), typeName(sampleArgs.get(name)), "string"));
            putIfText(field, "label", firstText(text(property.get("label")), text(property.get("title"))));
            putIfText(field, "title", text(property.get("title")));
            putIfText(field, "description", text(property.get("description")));
            Object aliases = firstPresent(property, "aliases", "synonyms");
            if (aliases != null) {
                field.put("aliases", aliases);
            }
            fields.add(field);
        }
        return fields;
    }

    private RuntimeWorkflowDraftResourceView findPageActionResource(RuntimeWorkflowDraftGenerationRequest request, String actionKey) {
        if (request == null || request.getPageActions() == null || !StringUtils.hasText(actionKey)) {
            return null;
        }
        String targetCompact = compactToken(actionKey);
        for (RuntimeWorkflowDraftResourceView resource : request.getPageActions()) {
            if (resource == null) {
                continue;
            }
            if (actionKey.equalsIgnoreCase(resource.getName())) {
                return resource;
            }
            Map<String, Object> metadata = mutableMap(resource.getMetadata());
            String metaActionKey = text(metadata.get("actionKey"));
            if (actionKey.equalsIgnoreCase(metaActionKey)) {
                return resource;
            }
            if (StringUtils.hasText(targetCompact)
                    && (targetCompact.equals(compactToken(resource.getName()))
                    || targetCompact.equals(compactToken(metaActionKey)))) {
                return resource;
            }
        }
        return null;
    }

    private boolean isSetFiltersAction(String actionKey) {
        if (!StringUtils.hasText(actionKey)) {
            return false;
        }
        String compact = compactToken(actionKey);
        return "setfilters".equals(compact)
                || actionKey.toLowerCase(Locale.ROOT).contains("setfilter")
                || actionKey.toLowerCase(Locale.ROOT).contains("set_filter");
    }

    private String buildFilterFieldGuide(List<Map<String, Object>> fields) {
        if (fields.isEmpty()) {
            return "按页面 setFilters 动作可接受的筛选字段输出 JSON；字段名与页面动作 inputSchema 保持一致。";
        }
        StringBuilder guide = new StringBuilder("仅输出以下字段组成的 JSON 对象（键名必须完全一致）。")
                .append("字段语义只能来自当前 setFilters inputSchema 的 title/label/description/aliases，不要套用其他页面的字段名：");
        for (Map<String, Object> field : fields) {
            String name = text(field.get("name"));
            if (!StringUtils.hasText(name)) {
                continue;
            }
            guide.append("\n- ").append(name);
            String label = firstText(text(field.get("label")), text(field.get("title")));
            if (StringUtils.hasText(label)) {
                guide.append("：").append(label);
            }
            String description = text(field.get("description"));
            if (StringUtils.hasText(description)) {
                guide.append("；").append(description);
            }
            String aliases = aliasText(field.get("aliases"));
            if (StringUtils.hasText(aliases)) {
                guide.append("；别名：").append(aliases);
            }
        }
        guide.append("。用户未提及的字段不要编造，可省略该键。");
        return guide.toString();
    }

    private String buildPageAssistantExtractSystemPrompt(String fieldGuide) {
        return "你是页面助手工作流中的筛选条件提取节点。"
                + fieldGuide
                + " 只能根据当前页面字段定义做同义理解：如果某个页面把“负责人”声明为 owner，就输出 owner；如果声明为 principalUserName，就输出 principalUserName。"
                + " 只输出 JSON 对象，不要输出解释、Markdown 或代码块。";
    }

    private boolean isPageAssistantDraft(RuntimeWorkflowDraftGenerationRequest request) {
        if (request == null) {
            return false;
        }
        if ("PAGE_ASSISTANT".equalsIgnoreCase(text(request.getDraftScenario()))) {
            return true;
        }
        return request.getPageActions() != null && !request.getPageActions().isEmpty();
    }

    private String callModel(RuntimeWorkflowDraftGenerationRequest request) throws JsonProcessingException {
        ModelChatResult result = modelServiceClient.chat(ModelChatRequest.builder()
                .modelInstanceId(request.getModelInstanceId())
                .messages(List.of(
                        ChatMessage.builder().role("system").content(systemPrompt(request)).build(),
                        ChatMessage.builder().role("user").content(userPrompt(request)).build()))
                .options(Map.of("temperature", 0.2))
                .build());
        if (result == null || result.getData() == null || !StringUtils.hasText(result.getData().getContent())) {
            throw new IllegalArgumentException("empty model response");
        }
        return result.getData().getContent();
    }

    private String systemPrompt(RuntimeWorkflowDraftGenerationRequest request) {
        StringBuilder prompt = new StringBuilder("""
                You are Workflow Studio's workflow draft generator. Return only strict JSON.
                Generate a complete workflow draft from the user's requirement.
                Use only node kinds listed in nodeTypes. Use only supplied tools/capabilities/knowledgeBases/pageActions when binding real resources.
                For pageAction nodes, always set config.ref to the exact actionKey from pageActions.
                If a business step has no matching resource, still generate the node and mark it as a placeholder by setting config.needsConfiguration=true and config.placeholderReason.
                Do not output start/end nodes. Use START and END only as edge endpoints.
                Required JSON shape:
                {"summary":"short summary","nodes":[{"id":"stable_snake_case","kind":"userInput|llm|tool|skill|knowledge|pageAction|classifier|condition|answer|approval|parameter|http|code|aggregate|loop|template|variable|mcp","label":"display name","description":"what it does","config":{},"inputs":[],"outputs":[]}],"edges":[{"id":"optional","from":"START or node id","to":"node id or END","condition":"always|approved|rejected|route:key|success|error","sourceHandle":"optional","targetHandle":"optional"}],"warnings":[]}
                inputs and outputs must be arrays of port objects like {"id":"portId","name":"portName","type":"any"}, never bare strings.
                """);
        if (isPageAssistantDraft(request)) {
            prompt.append("""

                    PAGE_ASSISTANT draft rules (mandatory when pageActions are supplied):
                    - Never invent or assume pageAction actionKeys. Use only actionKeys present in pageActions/allowedActionKeys.
                    - Do not create setFilters unless pageActions contains setFilters. If setFilters is absent, skip the filter-setting step and use the supplied actions only.
                    - First decide whether selected pageActions represent a single linear query flow or multiple mutually exclusive user intents.
                    - flowMode=LINEAR_QUERY: build one linear flow using recommendedFlow order; do NOT create INTENT_CLASSIFIER.
                    - flowMode=INTENT_ROUTER: create INTENT_CLASSIFIER with strategy=HYBRID, inputExpression=input, defaultRoute=else; route each intent branch with condition route:<classId>; connect route:else to ANSWER asking the user to clarify query/reset/page-state/operation intent.
                    - Standard linear query chains (no classifier): setFilters->search->readTable, setFilters->search, search->readTable, or any subset containing only setFilters/search/readTable.
                    - Use INTENT_CLASSIFIER when multiple terminal intents exist, such as search+reset, search+getPageState, query chain plus reset/getPageState/openRowAction/confirmRequired actions, or when uncertain.
                    - Do not linearly chain mutually exclusive actions such as search then reset then getPageState.
                    - When setFilters is available in a query branch, add one LLM extract node before it with outputAlias=extracted_filters, outputFormat=json, structuredOutput=true.
                    - setFilters pageAction config.args must map each inputSchema field to extracted_filters.<fieldName>; never leave args empty when setFilters is selected.
                    - search/readTable/reset/getPageState pageAction nodes usually use empty args unless the action schema requires parameters.
                    - For confirmRequired or operational actions (openRowAction/delete/submit/approve), route branch should use INTERACTION confirm_action before PAGE_ACTION when requirement/safetyNotes says so.
                    - answer node must use a fixed Chinese status sentence, not {{ lastOutput }}, when the flow ends after page actions.
                    - Bind each pageAction config.ref to the exact actionKey from pageActions.

                    Few-shot A (LINEAR_QUERY, no classifier):
                    selectedActionKeys: setFilters, search, readTable
                    USER_INPUT -> LLM(extracted_filters) -> PAGE_ACTION(setFilters) -> PAGE_ACTION(search) -> PAGE_ACTION(readTable) -> ANSWER

                    Few-shot B (INTENT_ROUTER with HYBRID classifier):
                    selectedActionKeys: search, reset, getPageState
                    USER_INPUT -> INTENT_CLASSIFIER(strategy=HYBRID)
                    route:search_intent -> PAGE_ACTION(search) -> ANSWER
                    route:reset_intent -> PAGE_ACTION(reset) -> ANSWER
                    route:page_state_intent -> PAGE_ACTION(getPageState) -> ANSWER
                    route:else -> ANSWER(请说明要查询、重置还是读取页面状态)

                    Few-shot C (INTENT_ROUTER with confirm before operational action):
                    selectedActionKeys: readTable, openRowAction(confirmRequired=true)
                    USER_INPUT -> INTENT_CLASSIFIER(strategy=HYBRID)
                    route:read_table_intent -> PAGE_ACTION(readTable) -> ANSWER
                    route:row_action_intent -> INTERACTION(confirm_action) -> PAGE_ACTION(openRowAction) -> ANSWER
                    route:else -> ANSWER
                    """);
        }
        return prompt.toString();
    }

    private String userPrompt(RuntimeWorkflowDraftGenerationRequest request) throws JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agentId", request.getAgentId());
        payload.put("agentName", request.getAgentName());
        payload.put("projectCode", request.getProjectCode());
        payload.put("draftScenario", request.getDraftScenario());
        payload.put("requirement", request.getRequirement());
        payload.put("modelInstanceId", request.getModelInstanceId());
        payload.put("nodeTypes", AgentGraphNodeType.catalog());
        payload.put("tools", request.getTools() == null ? List.of() : request.getTools());
        payload.put("capabilities", request.getCapabilities() == null ? List.of() : request.getCapabilities());
        payload.put("knowledgeBases", request.getKnowledgeBases() == null ? List.of() : request.getKnowledgeBases());
        payload.put("pageActions", request.getPageActions() == null ? List.of() : request.getPageActions());
        if (isPageAssistantDraft(request)) {
            Map<String, Object> template = new LinkedHashMap<>();
            template.put("entry", "user_input");
            template.put("flowMode", pageAssistantFlowMode(request));
            template.put("allowedActionKeys", pageAssistantAllowedActionKeys(request));
            template.put("recommendedFlow", pageAssistantRecommendedFlow(request));
            template.put("intentClasses", pageAssistantIntentClasses(request));
            template.put("safetyNotes", pageAssistantSafetyNotes(request));
            if (hasPageAction(request, "setFilters")) {
                template.put("extractNode", Map.of(
                        "id", "extract_filters",
                        "outputAlias", "extracted_filters",
                        "outputFormat", "json",
                        "structuredOutput", true));
                template.put("setFiltersArgsPattern", "nodeOutput.extract_filters.<fieldName>");
            }
            template.put("answerTemplate", "正在按你的条件查询页面数据，请稍候…");
            payload.put("pageAssistantTemplate", template);
        }
        payload.put("currentCanvas", request.getCurrentCanvas() == null ? Map.of() : request.getCurrentCanvas());
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
    }

    private List<String> pageAssistantAllowedActionKeys(RuntimeWorkflowDraftGenerationRequest request) {
        if (request == null || request.getPageActions() == null) {
            return List.of();
        }
        return request.getPageActions().stream()
                .filter(Objects::nonNull)
                .map(resource -> {
                    Map<String, Object> metadata = mutableMap(resource.getMetadata());
                    return firstText(text(metadata.get("actionKey")), resource.getName());
                })
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private List<String> pageAssistantRecommendedFlow(RuntimeWorkflowDraftGenerationRequest request) {
        if ("INTENT_ROUTER".equals(pageAssistantFlowMode(request))) {
            List<String> flow = new ArrayList<>();
            flow.add("user_input");
            flow.add("intent_router");
            for (Map<String, Object> intentClass : pageAssistantIntentClasses(request)) {
                flow.add("route:" + text(intentClass.get("id")));
            }
            flow.add("route:else");
            flow.add("answer_else");
            return flow;
        }
        List<String> flow = new ArrayList<>();
        flow.add("user_input");
        if (hasPageAction(request, "setFilters")) {
            flow.add("extract_filters");
            flow.add("set_filters");
        }
        List<String> preferred = List.of("search", "readTable", "getPageState", "reset", "openRowAction");
        Set<String> added = new LinkedHashSet<>();
        for (String actionKey : preferred) {
            if (hasPageAction(request, actionKey)) {
                flow.add(slug(actionKey));
                added.add(compactToken(actionKey));
            }
        }
        for (String actionKey : pageAssistantAllowedActionKeys(request)) {
            String compact = compactToken(actionKey);
            if (!added.contains(compact) && !isSetFiltersAction(actionKey)) {
                flow.add(slug(actionKey));
            }
        }
        flow.add("answer");
        return flow;
    }

    private String pageAssistantFlowMode(RuntimeWorkflowDraftGenerationRequest request) {
        List<String> actionKeys = pageAssistantAllowedActionKeys(request);
        if (actionKeys.size() <= 1) {
            return "LINEAR_QUERY";
        }
        Set<String> categories = new LinkedHashSet<>();
        for (String actionKey : actionKeys) {
            categories.add(pageAssistantActionCategory(request, actionKey));
        }
        if (categories.stream().allMatch("query_chain"::equals)) {
            return "LINEAR_QUERY";
        }
        boolean hasQueryChain = categories.contains("query_chain");
        boolean hasReset = categories.contains("reset");
        boolean hasPageState = categories.contains("page_state");
        boolean hasOperational = categories.contains("operational");
        boolean hasOther = categories.contains("other");
        if (hasQueryChain && (hasReset || hasPageState || hasOperational || hasOther)) {
            return "INTENT_ROUTER";
        }
        int exclusiveGroups = 0;
        if (hasReset) exclusiveGroups++;
        if (hasPageState) exclusiveGroups++;
        if (hasOperational) exclusiveGroups++;
        if (hasOther) exclusiveGroups++;
        if (exclusiveGroups >= 2) {
            return "INTENT_ROUTER";
        }
        if (exclusiveGroups >= 1 && hasQueryChain) {
            return "INTENT_ROUTER";
        }
        return "INTENT_ROUTER";
    }

    private String pageAssistantActionCategory(RuntimeWorkflowDraftGenerationRequest request, String actionKey) {
        String compact = compactToken(actionKey);
        if (isSetFiltersAction(actionKey)) {
            return "query_chain";
        }
        if ("search".equals(compact) || actionKey.toLowerCase(Locale.ROOT).contains("search")
                || actionKey.toLowerCase(Locale.ROOT).contains("query")) {
            return "query_chain";
        }
        if ("readtable".equals(compact) || compact.contains("readtable")) {
            return "query_chain";
        }
        if ("reset".equals(compact) || compact.contains("reset")) {
            return "reset";
        }
        if ("getpagestate".equals(compact) || compact.contains("pagestate")) {
            return "page_state";
        }
        RuntimeWorkflowDraftResourceView resource = findPageActionResource(request, actionKey);
        if (resource != null && bool(mutableMap(resource.getMetadata()).get("confirmRequired"))) {
            return "operational";
        }
        if (compact.contains("openrow") || compact.contains("delete") || compact.contains("submit")
                || compact.contains("approve") || compact.contains("export")) {
            return "operational";
        }
        return "other";
    }

    private List<Map<String, Object>> pageAssistantIntentClasses(RuntimeWorkflowDraftGenerationRequest request) {
        if (!"INTENT_ROUTER".equals(pageAssistantFlowMode(request))) {
            return List.of();
        }
        List<Map<String, Object>> classes = new ArrayList<>();
        List<String> queryActionKeys = pageAssistantAllowedActionKeys(request).stream()
                .filter(actionKey -> "query_chain".equals(pageAssistantActionCategory(request, actionKey)))
                .toList();
        if (!queryActionKeys.isEmpty()) {
            classes.add(pageAssistantIntentClass(
                    "query_intent",
                    "查询数据",
                    "用户想按条件查询、搜索或读取表格结果",
                    List.of("查询", "搜索", "筛选", "查找", "表格", "列表", "查一下"),
                    queryActionKeys,
                    false));
        }
        List<String> resetActionKeys = pageAssistantAllowedActionKeys(request).stream()
                .filter(actionKey -> "reset".equals(pageAssistantActionCategory(request, actionKey)))
                .toList();
        if (!resetActionKeys.isEmpty()) {
            classes.add(pageAssistantIntentClass(
                    "reset_intent",
                    resetActionKeys.size() == 1 ? pageAssistantActionLabel(request, resetActionKeys.get(0), "重置筛选") : "重置筛选",
                    "用户想清空或重置页面筛选条件",
                    List.of("重置", "清空", "恢复默认", "清除筛选"),
                    resetActionKeys,
                    false));
        }
        List<String> pageStateActionKeys = pageAssistantAllowedActionKeys(request).stream()
                .filter(actionKey -> "page_state".equals(pageAssistantActionCategory(request, actionKey)))
                .toList();
        if (!pageStateActionKeys.isEmpty()) {
            classes.add(pageAssistantIntentClass(
                    "page_state_intent",
                    pageStateActionKeys.size() == 1
                            ? pageAssistantActionLabel(request, pageStateActionKeys.get(0), "读取页面状态")
                            : "读取页面状态",
                    "用户想查看当前筛选、分页或表格状态",
                    List.of("当前状态", "页面状态", "现在筛选", "当前条件", "分页"),
                    pageStateActionKeys,
                    false));
        }
        List<String> openRowActionKeys = pageAssistantAllowedActionKeys(request).stream()
                .filter(actionKey -> "operational".equals(pageAssistantActionCategory(request, actionKey)))
                .filter(actionKey -> compactToken(actionKey).contains("openrow"))
                .toList();
        if (!openRowActionKeys.isEmpty()) {
            classes.add(pageAssistantIntentClass(
                    "row_action_intent",
                    openRowActionKeys.size() == 1
                            ? pageAssistantActionLabel(request, openRowActionKeys.get(0), "行内操作")
                            : "行内操作",
                    "用户想执行表格行内操作",
                    List.of("打开", "行操作", "周期", "详情", "操作"),
                    openRowActionKeys,
                    openRowActionKeys.stream().anyMatch(actionKey -> pageAssistantActionConfirmRequired(request, actionKey))));
        }
        for (String actionKey : pageAssistantAllowedActionKeys(request)) {
            String category = pageAssistantActionCategory(request, actionKey);
            if (!"operational".equals(category) || compactToken(actionKey).contains("openrow")) {
                continue;
            }
            String label = pageAssistantActionLabel(request, actionKey, actionKey);
            classes.add(pageAssistantIntentClass(
                    slug(actionKey) + "_intent",
                    label,
                    "用户想执行页面操作：" + label,
                    List.of(label, "操作", "执行"),
                    List.of(actionKey),
                    pageAssistantActionConfirmRequired(request, actionKey)));
        }
        List<String> otherActionKeys = pageAssistantAllowedActionKeys(request).stream()
                .filter(actionKey -> "other".equals(pageAssistantActionCategory(request, actionKey)))
                .toList();
        if (otherActionKeys.size() == 1) {
            String actionKey = otherActionKeys.get(0);
            String label = pageAssistantActionLabel(request, actionKey, actionKey);
            classes.add(pageAssistantIntentClass(
                    slug(actionKey) + "_intent",
                    label,
                    "用户想执行页面操作：" + label,
                    List.of(label, "操作", "执行"),
                    List.of(actionKey),
                    false));
        } else if (otherActionKeys.size() > 1) {
            classes.add(pageAssistantIntentClass(
                    "other_intent",
                    "其他页面操作",
                    "用户想执行其他已注册的页面动作",
                    List.of("操作", "执行", "处理"),
                    otherActionKeys,
                    false));
        }
        return classes;
    }

    private String pageAssistantActionLabel(RuntimeWorkflowDraftGenerationRequest request, String actionKey, String fallback) {
        RuntimeWorkflowDraftResourceView resource = findPageActionResource(request, actionKey);
        return resource == null ? fallback : firstText(resource.getDescription(), actionKey, fallback);
    }

    private boolean pageAssistantActionConfirmRequired(RuntimeWorkflowDraftGenerationRequest request, String actionKey) {
        RuntimeWorkflowDraftResourceView resource = findPageActionResource(request, actionKey);
        return resource != null && bool(mutableMap(resource.getMetadata()).get("confirmRequired"));
    }

    private Map<String, Object> pageAssistantIntentClass(String id,
                                                         String label,
                                                         String description,
                                                         List<String> keywords,
                                                         List<String> actionKeys,
                                                         boolean requiresConfirm) {
        Map<String, Object> intentClass = new LinkedHashMap<>();
        intentClass.put("id", id);
        intentClass.put("label", label);
        intentClass.put("description", description);
        intentClass.put("keywords", keywords);
        intentClass.put("actionKeys", actionKeys);
        intentClass.put("requiresConfirm", requiresConfirm);
        return intentClass;
    }

    private List<String> pageAssistantSafetyNotes(RuntimeWorkflowDraftGenerationRequest request) {
        if (request == null || request.getPageActions() == null) {
            return List.of();
        }
        List<String> notes = new ArrayList<>();
        for (RuntimeWorkflowDraftResourceView resource : request.getPageActions()) {
            if (resource == null) {
                continue;
            }
            Map<String, Object> metadata = mutableMap(resource.getMetadata());
            String actionKey = actionResourceKey(resource);
            if (bool(metadata.get("confirmRequired"))) {
                notes.add(actionKey + " 为需确认动作：route 分支中先 INTERACTION(confirm_action)，再 PAGE_ACTION(" + actionKey + ")。");
            }
        }
        return notes;
    }

    private boolean hasPageAction(RuntimeWorkflowDraftGenerationRequest request, String actionKey) {
        return findPageActionResource(request, actionKey) != null;
    }

    private JsonNode normalizeDraftJson(JsonNode root) {
        if (root == null || !root.isObject()) {
            return root;
        }
        ObjectNode copy = root.deepCopy();
        JsonNode nodes = copy.get("nodes");
        if (nodes == null || !nodes.isArray()) {
            return copy;
        }
        ArrayNode normalizedNodes = objectMapper.createArrayNode();
        for (JsonNode node : nodes) {
            if (!node.isObject()) {
                normalizedNodes.add(node);
                continue;
            }
            ObjectNode nodeCopy = node.deepCopy();
            normalizePortArray(nodeCopy, "inputs");
            normalizePortArray(nodeCopy, "outputs");
            normalizedNodes.add(nodeCopy);
        }
        copy.set("nodes", normalizedNodes);
        return copy;
    }

    private void normalizePortArray(ObjectNode node, String field) {
        JsonNode ports = node.get(field);
        if (ports == null || !ports.isArray() || ports.isEmpty()) {
            return;
        }
        boolean needsNormalization = false;
        for (JsonNode port : ports) {
            if (port.isTextual()) {
                needsNormalization = true;
                break;
            }
        }
        if (!needsNormalization) {
            return;
        }
        ArrayNode normalized = objectMapper.createArrayNode();
        for (JsonNode port : ports) {
            if (port.isTextual()) {
                String name = port.asText();
                if (StringUtils.hasText(name)) {
                    ObjectNode portObj = objectMapper.createObjectNode();
                    portObj.put("id", name);
                    portObj.put("name", name);
                    portObj.put("type", "any");
                    normalized.add(portObj);
                }
            } else if (port.isObject()) {
                normalized.add(port);
            }
        }
        node.set(field, normalized);
    }

    private String extractJsonObject(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("empty model response");
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("missing JSON object");
        }
        return text.substring(start, end + 1);
    }

    private Map<String, Object> canvasNode(String id, String type, int x, int y, Map<String, Object> data) {
        return Map.of("id", id, "type", type, "position", Map.of("x", x, "y", y), "data", data);
    }

    private Map<String, Object> canvasEdge(String id, String source, String target, String condition, String sourceHandle, String targetHandle) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", id);
        edge.put("source", source);
        edge.put("target", target);
        edge.put("condition", condition);
        edge.put("label", condition);
        if (StringUtils.hasText(sourceHandle)) edge.put("sourceHandle", sourceHandle);
        if (StringUtils.hasText(targetHandle)) edge.put("targetHandle", targetHandle);
        edge.put("type", "smoothstep");
        edge.put("markerEnd", "arrowclosed");
        edge.put("interactionWidth", 18);
        edge.put("animated", !List.of("always", "default").contains(condition));
        return edge;
    }

    private Map<String, Object> data(String label, String kind, Map<String, Object> extra) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("label", label);
        data.put("kind", kind);
        data.put("configVersion", 2);
        data.putAll(extra);
        return data;
    }

    private boolean isEndpointAllowed(String endpoint, Set<String> nodeIds) {
        return "START".equals(endpoint) || "END".equals(endpoint) || nodeIds.contains(endpoint);
    }

    private String endpoint(String value) {
        if ("start".equalsIgnoreCase(value)) return "START";
        if ("end".equalsIgnoreCase(value)) return "END";
        return value;
    }

    private String canvasEndpoint(String value) {
        if ("START".equals(value)) return "start";
        if ("END".equals(value)) return "end";
        return value;
    }

    private String endpointId(String value) {
        return "START".equals(value) ? "start" : "END".equals(value) ? "end" : value;
    }

    private String defaultOutputAlias(String kind) {
        if ("userInput".equals(kind)) return "params";
        if (!List.of("start", "end", "llm", "condition", "answer").contains(kind)) return kind + "_output";
        return "";
    }

    private List<Map<String, Object>> fields(Map<String, Object> config) {
        Object raw = config.get("fields");
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(Map.class::isInstance)
                .map(this::mutableMap)
                .toList();
    }

    private List<Map<String, Object>> classes(Map<String, Object> config) {
        Object raw = config.get("classes");
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(Map.class::isInstance)
                .map(this::mutableMap)
                .toList();
    }

    private List<String> arrayValue(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).filter(StringUtils::hasText).toList();
    }

    private List<String> arrayOrDefault(Object value, List<String> fallback) {
        List<String> items = arrayValue(value);
        return items.isEmpty() ? fallback : items;
    }

    private String firstString(List<String> values) {
        return values == null || values.isEmpty() ? "" : values.get(0);
    }

    private void putIfText(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value.trim());
        }
    }

    private Object firstPresent(Map<String, Object> map, String... keys) {
        if (map == null) {
            return null;
        }
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private String typeName(Object value) {
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof List<?>) {
            return "array";
        }
        if (value instanceof Map<?, ?>) {
            return "object";
        }
        return "";
    }

    private String aliasText(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(this::text)
                    .filter(StringUtils::hasText)
                    .toList()
                    .stream()
                    .reduce((left, right) -> left + "/" + right)
                    .orElse("");
        }
        return text(value);
    }

    private Map<String, Object> mutableMap(Object value) {
        if (value == null) return new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) return objectMapper.convertValue(map, MAP_TYPE);
        return objectMapper.convertValue(value, MAP_TYPE);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return "";
    }

    private boolean bool(Object value) {
        if (value instanceof Boolean b) return b;
        return "true".equalsIgnoreCase(text(value));
    }

    private boolean boolOr(Object value, boolean fallback) {
        if (value == null) return fallback;
        return bool(value);
    }

    private int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return StringUtils.hasText(text(value)) ? Integer.parseInt(text(value)) : fallback;
        } catch (Exception ex) {
            return fallback;
        }
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return StringUtils.hasText(text(value)) ? Long.parseLong(text(value)) : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private Double doubleValue(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return StringUtils.hasText(text(value)) ? Double.parseDouble(text(value)) : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private double doubleOr(Object value, double fallback) {
        Double number = doubleValue(value);
        return number == null ? fallback : number;
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private String slug(String value) {
        String raw = firstText(value, "node").trim();
        String normalized = raw.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}_-]+", "_")
                .replace('-', '_')
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "")
                .toLowerCase(Locale.ROOT);
        return StringUtils.hasText(normalized) ? normalized : "node";
    }

    private String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DraftResponse(String summary, List<DraftNode> nodes, List<DraftEdge> edges, List<String> warnings) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DraftNode(String id,
                             String kind,
                             String type,
                             String label,
                             String description,
                             Map<String, Object> config,
                             List<Map<String, Object>> inputs,
                             List<Map<String, Object>> outputs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DraftEdge(String id,
                             String from,
                             String to,
                             String source,
                             String target,
                             String condition,
                             String sourceHandle,
                             String targetHandle,
                             String label) {
    }
}

