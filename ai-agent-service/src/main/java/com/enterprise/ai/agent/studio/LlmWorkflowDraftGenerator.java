package com.enterprise.ai.agent.studio;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.AgentGraphSpec;
import com.enterprise.ai.agent.llm.LlmService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class LlmWorkflowDraftGenerator implements WorkflowDraftGenerator {

    private static final String PROVIDER = "LLM_DRAFT";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final LlmService llmService;

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public boolean supports(WorkflowDraftGenerationRequest request) {
        return request != null
                && StringUtils.hasText(request.getRequirement())
                && StringUtils.hasText(request.getModelInstanceId());
    }

    @Override
    public WorkflowDraftGenerationResult generate(WorkflowDraftGenerationRequest request) {
        Map<String, WorkflowDraftResource> resources = resourceIndex(request);
        List<String> warnings = new ArrayList<>();
        List<String> validationErrors = new ArrayList<>();
        List<WorkflowDraftPlaceholder> placeholders = new ArrayList<>();

        if (!supports(request)) {
            validationErrors.add("requirement and modelInstanceId are required");
            return result(request, List.of(), List.of(), warnings, placeholders, validationErrors);
        }

        DraftResponse draft;
        try {
            String raw = llmService.chat(systemPrompt(), userPrompt(request), request.getModelInstanceId());
            JsonNode root = objectMapper.readTree(extractJsonObject(raw));
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
        List<DraftEdge> normalizedEdges = normalizeEdges(draft.edges(), normalizedNodes, validationErrors);
        if (normalizedEdges.isEmpty() && !normalizedNodes.isEmpty()) {
            normalizedEdges = defaultEdges(normalizedNodes);
        }
        return result(request, normalizedNodes, normalizedEdges, warnings, placeholders, validationErrors);
    }

    private WorkflowDraftGenerationResult result(WorkflowDraftGenerationRequest request,
                                                 List<DraftNode> nodes,
                                                 List<DraftEdge> edges,
                                                 List<String> warnings,
                                                 List<WorkflowDraftPlaceholder> placeholders,
                                                 List<String> validationErrors) {
        AgentGraphSpec graphSpec = graphSpec(request, nodes, edges);
        return new WorkflowDraftGenerationResult(
                PROVIDER,
                canvasSnapshot(graphSpec, nodes, edges),
                graphSpec,
                warnings,
                placeholders,
                validationErrors);
    }

    private List<DraftNode> normalizeNodes(List<DraftNode> rawNodes,
                                           WorkflowDraftGenerationRequest request,
                                           Map<String, WorkflowDraftResource> resources,
                                           List<String> warnings,
                                           List<String> validationErrors,
                                           List<WorkflowDraftPlaceholder> placeholders) {
        if (rawNodes == null || rawNodes.isEmpty()) {
            validationErrors.add("workflow draft must contain at least one node");
            return List.of();
        }
        List<DraftNode> nodes = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
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

    private AgentGraphSpec graphSpec(WorkflowDraftGenerationRequest request, List<DraftNode> nodes, List<DraftEdge> edges) {
        AgentGraphSpec.AgentGraphSpecBuilder builder = AgentGraphSpec.builder()
                .code(slug(firstText(request == null ? null : request.getAgentId(),
                        request == null ? null : request.getAgentName(), "ai_generated_graph")))
                .name(firstText(request == null ? null : request.getAgentName(), "AI 生成流程草稿"))
                .mode("WORKFLOW")
                .runtimeHint("LANGGRAPH4J")
                .layout(AgentGraphSpec.Layout.builder().engine("vue-flow").direction("LR").build());

        for (DraftNode node : nodes) {
            builder.node(toGraphNode(node));
        }
        for (DraftEdge edge : edges) {
            builder.edge(AgentGraphSpec.Edge.builder()
                    .id(edge.id())
                    .from(edge.from())
                    .to(edge.to())
                    .condition(firstText(edge.condition(), "always"))
                    .sourceHandle(blankToNull(edge.sourceHandle()))
                    .targetHandle(blankToNull(edge.targetHandle()))
                    .layout(AgentGraphSpec.Layout.EdgeLayout.builder()
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

    private AgentGraphSpec.Node toGraphNode(DraftNode node) {
        Map<String, Object> config = mutableMap(node.config());
        AgentGraphSpec.Node.NodeBuilder builder = AgentGraphSpec.Node.builder()
                .id(node.id())
                .type(node.type())
                .name(node.label())
                .description(node.description())
                .config(config)
                .layout(AgentGraphSpec.Layout.NodeLayout.builder()
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
        builder.retry(AgentGraphSpec.RetryPolicy.builder()
                .enabled(AgentGraphNodeType.find(node.type()).map(AgentGraphNodeType::retryable).orElse(false))
                .maxAttempts(1)
                .backoffMs(800L)
                .build());
        builder.errorPolicy(AgentGraphSpec.ErrorPolicy.builder().strategy("TERMINATE").build());
        return builder.build();
    }

    private Map<String, Object> canvasSnapshot(AgentGraphSpec graph, List<DraftNode> nodes, List<DraftEdge> edges) {
        List<Map<String, Object>> canvasNodes = new ArrayList<>();
        canvasNodes.add(canvasNode("start", "start", 60, 220, data("开始", "start", Map.of())));
        int index = 0;
        for (DraftNode node : nodes) {
            Map<String, Object> data = data(node.label(), node.kind(), canvasData(node));
            int x = 280 + index * 260;
            int y = 220 + (index % 2 == 0 ? 0 : 80);
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
                    blankToNull(edge.sourceHandle()),
                    blankToNull(edge.targetHandle())));
        }
        return Map.of("version", 2, "nodes", canvasNodes, "edges", canvasEdges, "graphCode", graph.getCode());
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
            case "classifier" -> data.put("classifierConfig", Map.of(
                    "inputExpression", firstText(text(config.get("inputExpression")), "input"),
                    "classes", classes(config),
                    "defaultRoute", firstText(text(config.get("defaultRoute")), "else")));
            case "approval" -> data.put("approvalConfig", Map.of(
                    "title", firstText(text(config.get("title")), node.label()),
                    "prompt", firstText(text(config.get("prompt")), "{{ lastOutput }}"),
                    "approvers", arrayValue(config.get("approvers")),
                    "timeoutSeconds", integer(config.get("timeoutSeconds"), 3600),
                    "defaultRoute", firstText(text(config.get("defaultRoute")), "approved")));
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

    private void normalizeLlmConfig(Map<String, Object> config, WorkflowDraftGenerationRequest request) {
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
        config.put("classes", classes(config).isEmpty()
                ? List.of(Map.of("id", "matched", "label", "匹配", "keywords", List.of()))
                : classes(config));
        config.put("defaultRoute", firstText(text(config.get("defaultRoute")), "else"));
    }

    private void normalizeApprovalConfig(Map<String, Object> config) {
        config.put("title", firstText(text(config.get("title")), "人工审批"));
        config.put("prompt", firstText(text(config.get("prompt")), "{{ lastOutput }}"));
        config.put("approvers", arrayValue(config.get("approvers")));
        config.put("timeoutSeconds", integer(config.get("timeoutSeconds"), 3600));
        config.put("defaultRoute", firstText(text(config.get("defaultRoute")), "approved"));
    }

    private void normalizeKnowledgeConfig(Map<String, Object> config,
                                          WorkflowDraftGenerationRequest request,
                                          Map<String, WorkflowDraftResource> resources,
                                          List<String> warnings,
                                          List<WorkflowDraftPlaceholder> placeholders,
                                          String nodeId,
                                          String label) {
        String ref = firstText(text(config.get("ref")), text(config.get("name")), firstString(arrayValue(config.get("knowledgeBaseCodes"))));
        WorkflowDraftResource resource = resource(ref, resources);
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
                                           WorkflowDraftGenerationRequest request,
                                           Map<String, WorkflowDraftResource> resources,
                                           List<String> warnings,
                                           List<WorkflowDraftPlaceholder> placeholders,
                                           String nodeId,
                                           String label) {
        String ref = firstText(text(config.get("ref")), text(config.get("name")), text(config.get("qualifiedName")));
        WorkflowDraftResource resource = resource(ref, resources);
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

    private void markPlaceholder(Map<String, Object> config,
                                 List<String> warnings,
                                 List<WorkflowDraftPlaceholder> placeholders,
                                 String nodeId,
                                 String kind,
                                 String label,
                                 String reason) {
        config.put("needsConfiguration", true);
        config.put("placeholderReason", reason);
        warnings.add(label + "：" + reason);
        placeholders.add(new WorkflowDraftPlaceholder(nodeId, kind, label, reason));
    }

    private java.util.Optional<AgentGraphSpec.CapabilityRef> capabilityRef(DraftNode node) {
        if (!"TOOL".equals(node.type()) && !"CAPABILITY".equals(node.type()) && !"MCP_CALL".equals(node.type()) && !"HTTP_REQUEST".equals(node.type())) {
            return java.util.Optional.empty();
        }
        Map<String, Object> config = mutableMap(node.config());
        String name = firstText(text(config.get("ref")), text(config.get("name")));
        if (!StringUtils.hasText(name) || bool(config.get("needsConfiguration"))) {
            return java.util.Optional.empty();
        }
        String kind = "CAPABILITY".equals(node.type()) ? "CAPABILITY" : "TOOL";
        return java.util.Optional.of(AgentGraphSpec.CapabilityRef.builder()
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

    private AgentGraphSpec.Port toPort(Map<String, Object> map) {
        return AgentGraphSpec.Port.builder()
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

    private Map<String, WorkflowDraftResource> resourceIndex(WorkflowDraftGenerationRequest request) {
        Map<String, WorkflowDraftResource> out = new LinkedHashMap<>();
        for (WorkflowDraftResource resource : resources(request)) {
            if (resource == null) continue;
            indexResource(out, resource.getName(), resource);
            indexResource(out, resource.getQualifiedName(), resource);
        }
        return out;
    }

    private List<WorkflowDraftResource> resources(WorkflowDraftGenerationRequest request) {
        List<WorkflowDraftResource> resources = new ArrayList<>();
        if (request.getTools() != null) resources.addAll(request.getTools());
        if (request.getCapabilities() != null) resources.addAll(request.getCapabilities());
        if (request.getKnowledgeBases() != null) resources.addAll(request.getKnowledgeBases());
        return resources;
    }

    private void indexResource(Map<String, WorkflowDraftResource> out, String key, WorkflowDraftResource resource) {
        if (StringUtils.hasText(key)) {
            out.put(key(key), resource);
        }
    }

    private WorkflowDraftResource resource(String ref, Map<String, WorkflowDraftResource> resources) {
        if (!StringUtils.hasText(ref)) return null;
        return resources.get(key(ref));
    }

    private String systemPrompt() {
        return """
                You are Agent Studio's workflow draft generator. Return only strict JSON.
                Generate a complete workflow draft from the user's requirement.
                Use only node kinds listed in nodeTypes. Use only supplied tools/capabilities/knowledgeBases when binding real resources.
                If a business step has no matching resource, still generate the node and mark it as a placeholder by setting config.needsConfiguration=true and config.placeholderReason.
                Do not output start/end nodes. Use START and END only as edge endpoints.
                Required JSON shape:
                {"summary":"short summary","nodes":[{"id":"stable_snake_case","kind":"userInput|llm|tool|skill|knowledge|classifier|condition|answer|approval|parameter|http|code|aggregate|loop|template|variable|mcp","label":"display name","description":"what it does","config":{},"inputs":[],"outputs":[]}],"edges":[{"id":"optional","from":"START or node id","to":"node id or END","condition":"always|approved|rejected|route:key|success|error","sourceHandle":"optional","targetHandle":"optional"}],"warnings":[]}
                """;
    }

    private String userPrompt(WorkflowDraftGenerationRequest request) throws JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agentId", request.getAgentId());
        payload.put("agentName", request.getAgentName());
        payload.put("projectCode", request.getProjectCode());
        payload.put("requirement", request.getRequirement());
        payload.put("modelInstanceId", request.getModelInstanceId());
        payload.put("nodeTypes", AgentGraphNodeType.catalog());
        payload.put("tools", request.getTools() == null ? List.of() : request.getTools());
        payload.put("capabilities", request.getCapabilities() == null ? List.of() : request.getCapabilities());
        payload.put("knowledgeBases", request.getKnowledgeBases() == null ? List.of() : request.getKnowledgeBases());
        payload.put("currentCanvas", request.getCurrentCanvas() == null ? Map.of() : request.getCurrentCanvas());
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
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
