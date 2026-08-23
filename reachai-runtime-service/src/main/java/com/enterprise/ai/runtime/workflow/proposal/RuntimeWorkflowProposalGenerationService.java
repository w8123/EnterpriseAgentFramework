package com.enterprise.ai.runtime.workflow.proposal;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest.ChatMessage;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import com.enterprise.ai.runtime.workflow.layout.RuntimeWorkflowCanvasLayoutService;
import com.enterprise.ai.runtime.workflow.node.RuntimeWorkflowNodeCapabilityDescriptor;
import com.enterprise.ai.runtime.workflow.node.RuntimeWorkflowNodeCapabilityRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.stream.Collectors;

@Service
public class RuntimeWorkflowProposalGenerationService {

    private static final String PROVIDER = "LLM_PROPOSAL";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Set<String> PROPOSAL_FIELDS = Set.of(
            "summary", "entryNodeId", "exitNodeIds", "nodes", "edges", "warnings");
    private static final Set<String> PROPOSAL_NODE_FIELDS = Set.of(
            "id", "type", "label", "description", "config", "inputs", "outputs");
    private static final Set<String> PROPOSAL_EDGE_FIELDS = Set.of(
            "id", "from", "to", "condition", "sourceHandle", "targetHandle");
    private static final Set<String> PROPOSAL_PORT_FIELDS = Set.of(
            "id", "name", "type", "required", "schema", "source");

    private final ObjectMapper objectMapper;
    private final RuntimeModelServiceClient modelServiceClient;
    private final RuntimeWorkflowCanvasLayoutService canvasLayoutService;
    private final RuntimeWorkflowProposalValidationService candidateValidationService;
    private final RuntimeWorkflowProposalRepairService repairService;
    private final RuntimeWorkflowNodeCapabilityRegistry nodeCapabilityRegistry;

    public RuntimeWorkflowProposalGenerationService(ObjectMapper objectMapper,
                                                 RuntimeModelServiceClient modelServiceClient,
                                                 RuntimeWorkflowCanvasLayoutService canvasLayoutService,
                                                 RuntimeWorkflowProposalValidationService candidateValidationService,
                                                 RuntimeWorkflowProposalRepairService repairService) {
        this(objectMapper, modelServiceClient, canvasLayoutService, candidateValidationService, repairService,
                new RuntimeWorkflowNodeCapabilityRegistry());
    }

    @Autowired
    public RuntimeWorkflowProposalGenerationService(ObjectMapper objectMapper,
                                                 RuntimeModelServiceClient modelServiceClient,
                                                 RuntimeWorkflowCanvasLayoutService canvasLayoutService,
                                                 RuntimeWorkflowProposalValidationService candidateValidationService,
                                                 RuntimeWorkflowProposalRepairService repairService,
                                                 RuntimeWorkflowNodeCapabilityRegistry nodeCapabilityRegistry) {
        this.objectMapper = objectMapper;
        this.modelServiceClient = modelServiceClient;
        this.canvasLayoutService = canvasLayoutService;
        this.candidateValidationService = candidateValidationService;
        this.repairService = repairService;
        this.nodeCapabilityRegistry = nodeCapabilityRegistry == null
                ? new RuntimeWorkflowNodeCapabilityRegistry()
                : nodeCapabilityRegistry;
    }

    public String provider() {
        return PROVIDER;
    }

    public boolean supports(RuntimeWorkflowProposalGenerationRequest request) {
        return request != null
                && StringUtils.hasText(request.requirement())
                && StringUtils.hasText(request.modelInstanceId());
    }

    public RuntimeWorkflowProposalGenerationView generate(RuntimeWorkflowProposalGenerationRequest request) {
        Map<String, RuntimeWorkflowProposalResourceView> resources = resourceIndex(request);
        List<String> warnings = new ArrayList<>();
        List<String> validationErrors = new ArrayList<>();
        List<RuntimeWorkflowProposalPlaceholderView> placeholders = new ArrayList<>();

        if (!supports(request)) {
            validationErrors.add("requirement and modelInstanceId are required");
            return result(request, List.of(), List.of(), "", List.of(), warnings, placeholders, validationErrors);
        }

        ProposalResponse proposal;
        try {
            String raw = callModel(request);
            JsonNode root = objectMapper.readTree(extractJsonObject(raw));
            validateProposalJson(root);
            proposal = objectMapper.treeToValue(root, ProposalResponse.class);
        } catch (Exception ex) {
            validationErrors.add("AI did not return valid JSON workflow proposal: " + ex.getMessage());
            return result(request, List.of(), List.of(), "", List.of(), warnings, placeholders, validationErrors);
        }
        if (proposal.warnings() != null) {
            proposal.warnings().stream()
                    .filter(StringUtils::hasText)
                    .forEach(warnings::add);
        }

        List<ProposalNode> normalizedNodes = normalizeNodes(proposal.nodes(), request, resources, warnings, validationErrors, placeholders);
        boolean backfilledPageActions = false;
        if (isPageAssistantProposal(request)) {
            backfilledPageActions = backfillPageAssistantActionsWhenMissing(request, normalizedNodes, warnings);
            normalizePageAssistantProposal(request, normalizedNodes, warnings);
        }
        List<ProposalEdge> normalizedEdges = normalizeEdges(proposal.edges(), normalizedNodes, validationErrors);
        ProposalBoundaries boundaries = normalizeBoundaries(
                proposal.entryNodeId(), proposal.exitNodeIds(), normalizedNodes, validationErrors);
        if (backfilledPageActions) {
            normalizedEdges = defaultEdges(normalizedNodes);
            boundaries = defaultBoundaries(normalizedNodes);
        }
        return result(request, normalizedNodes, normalizedEdges,
                boundaries.entryNodeId(), boundaries.exitNodeIds(),
                warnings, placeholders, validationErrors);
    }

    private RuntimeWorkflowProposalGenerationView result(RuntimeWorkflowProposalGenerationRequest request,
                                                 List<ProposalNode> nodes,
                                                 List<ProposalEdge> edges,
                                                 String entryNodeId,
                                                 List<String> exitNodeIds,
                                                 List<String> warnings,
                                                 List<RuntimeWorkflowProposalPlaceholderView> placeholders,
                                                 List<String> validationErrors) {
        GraphSpec graphSpec = graphSpec(nodes, edges, entryNodeId, exitNodeIds);
        var releaseValidation = candidateValidationService.validate(
                request == null ? null : request.workflowId(),
                request == null ? null : request.projectCode(),
                request == null ? null : request.workflowKind(),
                request == null ? null : request.modelInstanceId(),
                graphSpec);
        RuntimeWorkflowProposalRepairService.RepairResult repair = repairService.repair(
                new RuntimeWorkflowProposalRepairService.RepairRequest(
                        request == null ? null : request.workflowId(),
                        request == null ? null : request.projectCode(),
                        request == null ? null : request.workflowKind(),
                        request == null ? null : request.modelInstanceId(),
                        request == null ? null : request.requirement(),
                        List.of(),
                        List.of(),
                        proposalResources(request),
                        RuntimeWorkflowProposalRepairService.DEFAULT_MAX_REPAIR_ROUNDS),
                graphSpec,
                releaseValidation);
        graphSpec = repair.graphSpec();
        releaseValidation = repair.validation();
        warnings.addAll(repair.warnings());
        releaseValidation.errors().stream()
                .map(item -> item.code() + ": " + item.message())
                .filter(item -> !validationErrors.contains(item))
                .forEach(validationErrors::add);
        releaseValidation.warnings().stream()
                .map(item -> item.code() + ": " + item.message())
                .filter(item -> !warnings.contains(item))
                .forEach(warnings::add);
        Map<String, Object> canvas = canvasLayoutService.layoutCanvas(
                canvasSnapshot(nodes, graphSpec),
                RuntimeWorkflowCanvasLayoutService.Options.defaults());
        return new RuntimeWorkflowProposalGenerationView(
                PROVIDER,
                canvas,
                graphSpec,
                warnings,
                placeholders,
                validationErrors);
    }

    private Map<String, Object> proposalResources(RuntimeWorkflowProposalGenerationRequest request) {
        if (request == null) {
            return Map.of();
        }
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("tools", request.tools() == null ? List.of() : request.tools());
        resources.put("capabilities", request.capabilities() == null ? List.of() : request.capabilities());
        resources.put("knowledgeBases", request.knowledgeBases() == null ? List.of() : request.knowledgeBases());
        resources.put("pageActions", request.pageActions() == null ? List.of() : request.pageActions());
        return resources;
    }

    private List<ProposalNode> normalizeNodes(List<ProposalNodePayload> rawNodes,
                                           RuntimeWorkflowProposalGenerationRequest request,
                                           Map<String, RuntimeWorkflowProposalResourceView> resources,
                                           List<String> warnings,
                                           List<String> validationErrors,
                                           List<RuntimeWorkflowProposalPlaceholderView> placeholders) {
        if (rawNodes == null || rawNodes.isEmpty()) {
            validationErrors.add("workflow proposal must contain at least one node");
            return List.of();
        }
        List<ProposalNode> nodes = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        Set<String> usedPageActions = new LinkedHashSet<>();
        int pageActionNodeIndex = 0;
        for (ProposalNodePayload raw : rawNodes) {
            if (raw == null) continue;
            String id = slug(firstText(raw.id(), raw.label(), "node_" + (nodes.size() + 1)));
            String rawType = firstText(raw.type());
            if ("start".equalsIgnoreCase(id) || "end".equalsIgnoreCase(id)
                    || "START".equalsIgnoreCase(rawType) || "END".equalsIgnoreCase(rawType)) {
                validationErrors.add("START/END nodes are not part of GraphSpec: " + id);
                continue;
            }
            if (!ids.add(id)) {
                validationErrors.add("duplicate node id: " + id);
                continue;
            }
            AgentGraphNodeType type = AgentGraphNodeType.find(rawType).orElse(null);
            if (type == null || !type.type().equals(rawType)) {
                validationErrors.add("unsupported canonical node type: " + rawType + " (" + id + ")");
                continue;
            }
            if (!nodeCapabilityRegistry.isAiAuthoringEnabled(type.type())) {
                String reason = nodeCapabilityRegistry.find(type.type())
                        .map(item -> StringUtils.hasText(item.unavailableReason())
                                ? item.unavailableReason()
                                : "not enabled for AI authoring")
                        .orElse("not enabled for AI authoring");
                validationErrors.add("WORKFLOW_NODE_NOT_AUTHORABLE: unsupported authoring node kind: "
                        + type.type() + " (" + id + ") - " + reason);
                continue;
            }

            Map<String, Object> config = mutableMap(raw.config());
            if (!nodeCapabilityRegistry.isAiAuthoringEnabled(type.type(), config)) {
                validationErrors.add("WORKFLOW_NODE_VARIANT_NOT_AUTHORABLE: "
                        + nodeCapabilityRegistry.variantUnavailableReason(type.type(), config)
                        + " (" + id + ")");
                continue;
            }
            config.put("configVersion", 2);
            config.putIfAbsent("source", "AI_PROPOSAL");
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
            } else if (type == AgentGraphNodeType.HTTP_REQUEST) {
                normalizeHttpConfig(config, warnings, placeholders, id, firstText(raw.label(), id));
            } else if (type == AgentGraphNodeType.PAGE_ACTION) {
                normalizePageActionConfig(config, request, resources, warnings, placeholders, id, firstText(raw.label(), id),
                        usedPageActions, pageActionNodeIndex++, validationErrors);
            } else if (type == AgentGraphNodeType.INTERACTION) {
                normalizePresentOutputConfig(config, id);
            } else if (type.isToolLike() || type == AgentGraphNodeType.MCP_CALL) {
                normalizeCapabilityConfig(config, type, request, resources, warnings, placeholders, id, firstText(raw.label(), id));
            }

            nodes.add(new ProposalNode(
                    id,
                    type.type(),
                    type.canvasKind(),
                    firstText(raw.label(), id),
                    firstText(raw.description(), ""),
                    config,
                    raw.inputs(),
                    raw.outputs()));
        }
        if (nodes.isEmpty()) {
            validationErrors.add("workflow proposal did not contain any usable nodes");
        }
        normalizeReferencedOutputAliases(nodes);
        return nodes;
    }

    private ProposalBoundaries normalizeBoundaries(String rawEntryNodeId,
                                                    List<String> rawExitNodeIds,
                                                    List<ProposalNode> nodes,
                                                    List<String> validationErrors) {
        Set<String> nodeIds = nodes.stream()
                .map(ProposalNode::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        String entryNodeId = firstText(rawEntryNodeId);
        if (!StringUtils.hasText(entryNodeId)) {
            validationErrors.add("entryNodeId is required");
        } else if (!nodeIds.contains(entryNodeId)) {
            validationErrors.add("entryNodeId references missing node: " + entryNodeId);
        }

        LinkedHashSet<String> exitNodeIds = new LinkedHashSet<>();
        if (rawExitNodeIds == null || rawExitNodeIds.isEmpty()) {
            validationErrors.add("exitNodeIds must contain at least one node id");
        } else {
            for (String rawExitNodeId : rawExitNodeIds) {
                String exitNodeId = firstText(rawExitNodeId);
                if (!StringUtils.hasText(exitNodeId)) {
                    validationErrors.add("exitNodeIds contains a blank node id");
                } else if (!nodeIds.contains(exitNodeId)) {
                    validationErrors.add("exitNodeIds references missing node: " + exitNodeId);
                } else if (!exitNodeIds.add(exitNodeId)) {
                    validationErrors.add("exitNodeIds contains duplicate node id: " + exitNodeId);
                }
            }
        }
        return new ProposalBoundaries(entryNodeId, List.copyOf(exitNodeIds));
    }

    private ProposalBoundaries defaultBoundaries(List<ProposalNode> nodes) {
        if (nodes.isEmpty()) {
            return new ProposalBoundaries("", List.of());
        }
        return new ProposalBoundaries(nodes.get(0).id(), List.of(nodes.get(nodes.size() - 1).id()));
    }

    private List<ProposalEdge> normalizeEdges(List<ProposalEdge> rawEdges,
                                           List<ProposalNode> nodes,
                                           List<String> validationErrors) {
        if (rawEdges == null) {
            return List.of();
        }
        Set<String> nodeIds = new LinkedHashSet<>(nodes.stream().map(ProposalNode::id).toList());
        List<ProposalEdge> edges = new ArrayList<>();
        int index = 1;
        for (ProposalEdge raw : rawEdges) {
            if (raw == null) continue;
            String from = firstText(raw.from());
            String to = firstText(raw.to());
            if (!nodeIds.contains(from) || !nodeIds.contains(to)) {
                validationErrors.add("edge references missing node: " + from + " -> " + to);
                continue;
            }
            String condition = firstText(raw.condition(), "always");
            edges.add(new ProposalEdge(
                    firstText(raw.id(), "e-" + from + "-" + to + "-" + index++),
                    from,
                    to,
                    condition,
                    firstText(raw.sourceHandle(), ""),
                    firstText(raw.targetHandle(), "")));
        }
        return edges;
    }

    private List<ProposalEdge> defaultEdges(List<ProposalNode> nodes) {
        List<ProposalEdge> edges = new ArrayList<>();
        if (nodes.isEmpty()) return edges;
        for (int i = 0; i < nodes.size() - 1; i++) {
            ProposalNode from = nodes.get(i);
            ProposalNode to = nodes.get(i + 1);
            edges.add(new ProposalEdge("e-" + from.id() + "-" + to.id(), from.id(), to.id(), "always", "", ""));
        }
        return edges;
    }

    private GraphSpec graphSpec(List<ProposalNode> nodes,
                                List<ProposalEdge> edges,
                                String entryNodeId,
                                List<String> exitNodeIds) {
        GraphSpec.GraphSpecBuilder builder = GraphSpec.builder()
                .schemaVersion(2)
                .entryNodeId(entryNodeId)
                .exitNodeIds(exitNodeIds == null ? List.of() : List.copyOf(exitNodeIds));

        for (ProposalNode node : nodes) {
            builder.node(toGraphNode(node));
        }
        for (ProposalEdge edge : edges) {
            builder.edge(GraphSpec.Edge.builder()
                    .id(edge.id())
                    .from(edge.from())
                    .to(edge.to())
                    .condition(firstText(edge.condition(), "always"))
                    .sourceHandle(blankToNull(edge.sourceHandle()))
                    .targetHandle(blankToNull(edge.targetHandle()))
                    .build());
        }

        return builder.build();
    }

    private GraphSpec.Node toGraphNode(ProposalNode node) {
        Map<String, Object> config = mutableMap(node.config());
        config.keySet().removeAll(Set.of("x", "y", "position", "collapsed", "ui", "category"));
        GraphSpec.Node.NodeBuilder builder = GraphSpec.Node.builder()
                .id(node.id())
                .type(node.type())
                .name(node.label())
                .description(node.description())
                .config(config);
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

    private Map<String, Object> canvasSnapshot(List<ProposalNode> nodes, GraphSpec graphSpec) {
        List<Map<String, Object>> canvasNodes = new ArrayList<>();
        canvasNodes.add(canvasNode("start", "start", 60, 220, data("开始", "start", Map.of())));
        int index = 0;
        for (ProposalNode node : nodes) {
            Map<String, Object> data = data(node.label(), node.canvasKind(), canvasData(node));
            int x = 280 + index * 260;
            int y = 220;
            canvasNodes.add(canvasNode(node.id(), node.canvasKind(), x, y, data));
            index++;
        }
        canvasNodes.add(canvasNode("end", "end", 300 + Math.max(nodes.size(), 1) * 260, 220, data("结束", "end", Map.of())));

        List<Map<String, Object>> canvasEdges = new ArrayList<>();
        int edgeIndex = 1;
        String entryNodeId = graphSpec == null ? null : graphSpec.getEntryNodeId();
        if (StringUtils.hasText(entryNodeId)) {
            canvasEdges.add(canvasEdge(
                    "e-start-" + entryNodeId,
                    "start",
                    entryNodeId,
                    "always",
                    null,
                    null));
        }
        for (GraphSpec.Edge edge : graphSpec == null || graphSpec.getEdges() == null ? List.<GraphSpec.Edge>of() : graphSpec.getEdges()) {
            if (edge == null) {
                continue;
            }
            canvasEdges.add(canvasEdge(
                    firstText(edge.getId(), "e-" + edgeIndex++),
                    edge.getFrom(),
                    edge.getTo(),
                    firstText(edge.getCondition(), "always"),
                    canvasRenderableSourceHandle(edge, nodes),
                    blankToNull(edge.getTargetHandle())));
        }
        for (String exitNodeId : graphSpec == null ? List.<String>of() : graphSpec.getExitNodeIds()) {
            if (StringUtils.hasText(exitNodeId)) {
                canvasEdges.add(canvasEdge(
                        "e-" + exitNodeId + "-end",
                        exitNodeId,
                        "end",
                        "always",
                        null,
                        null));
            }
        }
        return Map.of("version", 2, "nodes", canvasNodes, "edges", canvasEdges);
    }

    private String canvasRenderableSourceHandle(GraphSpec.Edge edge, List<ProposalNode> nodes) {
        String handle = blankToNull(edge.getSourceHandle());
        if (!StringUtils.hasText(handle)) {
            handle = canvasSourceHandleFromCondition(edge);
        }
        if (!StringUtils.hasText(handle)) {
            return null;
        }
        String sourceId = edge.getFrom();
        ProposalNode source = nodes.stream()
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

    private String canvasSourceHandleFromCondition(GraphSpec.Edge edge) {
        String condition = firstText(edge.getCondition(), "");
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

    private String canvasNodeKind(ProposalNode node) {
        return firstText(node.canvasKind(), "").trim().toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> canvasData(ProposalNode node) {
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
        data.put("outputAlias", firstText(text(config.get("outputAlias")), defaultOutputAlias(node.canvasKind())));
        data.put("needsConfiguration", bool(config.get("needsConfiguration")));
        data.put("placeholderReason", firstText(text(config.get("placeholderReason")), ""));

        switch (node.canvasKind()) {
            case "userInput" -> data.put("userInputConfig", Map.of(
                    "fields", fields(config),
                    "outputAlias", firstText(text(config.get("outputAlias")), "params")));
            case "llm" -> data.put("llmConfig", llmConfig(config));
            case "tool" -> data.put("toolConfig", toolConfig(config));
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
            default -> data.putAll(genericConfig(node.canvasKind(), config));
        }
        return data;
    }

    private void normalizeLlmConfig(Map<String, Object> config, RuntimeWorkflowProposalGenerationRequest request) {
        config.put("modelInstanceId", firstText(text(config.get("modelInstanceId")), request.modelInstanceId()));
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

    private void normalizePresentOutputConfig(Map<String, Object> config, String nodeId) {
        config.put("interactionType", "PRESENT_OUTPUT");
        config.remove("mode");
        config.put("title", firstText(text(config.get("title")), "展示结构化结果"));
        config.put("component", firstText(text(config.get("component")), "detail"));
        config.put("dataExpression", firstText(text(config.get("dataExpression")), "lastOutput"));
        config.put("outputAlias", firstText(text(config.get("outputAlias")), nodeId + "_display"));

        Map<String, Object> behavior = mutableMap(config.get("behavior"));
        behavior.put("blocking", false);
        behavior.put("readonly", true);
        config.put("behavior", behavior);

        Map<String, Object> presentation = mutableMap(config.get("presentation"));
        presentation.put("mode", firstText(text(presentation.get("mode")), "card_only"));
        config.put("presentation", presentation);
        config.put("renderSchema", mutableMap(config.get("renderSchema")));
    }

    private void normalizeKnowledgeConfig(Map<String, Object> config,
                                          RuntimeWorkflowProposalGenerationRequest request,
                                          Map<String, RuntimeWorkflowProposalResourceView> resources,
                                          List<String> warnings,
                                          List<RuntimeWorkflowProposalPlaceholderView> placeholders,
                                          String nodeId,
                                          String label) {
        List<String> rawCodes = new ArrayList<>();
        Object codesValue = config.get("knowledgeBaseCodes");
        if (codesValue instanceof List<?> list) {
            for (Object item : list) {
                String code = text(item);
                if (StringUtils.hasText(code)) {
                    rawCodes.add(code.trim());
                }
            }
        }
        String ref = firstText(text(config.get("ref")), text(config.get("name")),
                rawCodes.isEmpty() ? null : rawCodes.get(0));
        if (rawCodes.isEmpty() && StringUtils.hasText(ref)) {
            rawCodes.add(ref.trim());
        }
        LinkedHashSet<String> resolvedCodes = new LinkedHashSet<>();
        for (String code : rawCodes) {
            RuntimeWorkflowProposalResourceView matched = resource(code, resources);
            if (matched != null) {
                String resolved = firstText(matched.name(), matched.qualifiedName(), code);
                if (StringUtils.hasText(resolved)) {
                    resolvedCodes.add(resolved.trim());
                }
            } else {
                resolvedCodes.add(code);
            }
        }
        if (resolvedCodes.isEmpty()) {
            markPlaceholder(config, warnings, placeholders, nodeId, "knowledge", label,
                    "请配置至少一个 knowledgeBaseCode");
        } else if (StringUtils.hasText(ref) && resource(ref, resources) == null) {
            markPlaceholder(config, warnings, placeholders, nodeId, "knowledge", label, "未找到知识库：" + ref);
        }
        config.put("knowledgeBaseCodes", new ArrayList<>(resolvedCodes));
        config.put("query", firstText(text(config.get("query")), "input"));
        config.put("topK", integer(config.get("topK"), 5));
        config.put("similarityThreshold", doubleOr(config.get("similarityThreshold"), 0.5D));
        String searchMode = firstText(text(config.get("searchMode")), "hybrid").toLowerCase(Locale.ROOT);
        if (!Set.of("vector", "keyword", "hybrid").contains(searchMode)) {
            searchMode = "hybrid";
        }
        config.put("searchMode", searchMode);
        config.put("rerankEnabled", config.get("rerankEnabled") == null || bool(config.get("rerankEnabled")));
        // Workflow node no longer exposes directReturn*; KnowledgeBase admin may still keep those fields.
        config.remove("directReturnEnabled");
        config.remove("directReturnThreshold");
    }

    private void normalizeHttpConfig(Map<String, Object> config,
                                     List<String> warnings,
                                     List<RuntimeWorkflowProposalPlaceholderView> placeholders,
                                     String nodeId,
                                     String label) {
        String method = firstText(text(config.get("method")), "GET").toUpperCase(Locale.ROOT);
        if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(method)) {
            method = "GET";
        }
        config.put("method", method);
        String url = text(config.get("url"));
        config.put("url", firstText(url, ""));
        config.put("headers", mutableStringMap(config.get("headers")));
        config.put("queryParams", mutableStringMap(config.get("queryParams")));
        String bodyType = firstText(text(config.get("bodyType")), "none").toLowerCase(Locale.ROOT);
        if (!Set.of("none", "json", "text").contains(bodyType)) {
            bodyType = "none";
        }
        config.put("bodyType", bodyType);
        config.put("body", firstText(text(config.get("body")), ""));
        config.put("timeoutMs", integer(config.get("timeoutMs"), 30_000));
        config.put("credentialRef", firstText(text(config.get("credentialRef")), ""));
        if (config.get("retryAllowNonIdempotent") != null) {
            config.put("retryAllowNonIdempotent", bool(config.get("retryAllowNonIdempotent")));
        }
        config.put("outputAlias", firstText(text(config.get("outputAlias")), "http_out"));
        // Never inherit Tool/Capability binding fields.
        config.remove("ref");
        config.remove("qualifiedName");
        config.remove("definitionId");
        config.remove("inputMapping");
        if (!StringUtils.hasText(url)) {
            markPlaceholder(config, warnings, placeholders, nodeId, "http", label, "请配置 HTTP URL");
        }
    }

    private Map<String, Object> mutableStringMap(Object raw) {
        Map<String, Object> source = mutableMap(raw);
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (StringUtils.hasText(key) && value != null) {
                out.put(key, String.valueOf(value));
            }
        });
        return out;
    }

    private void normalizeCapabilityConfig(Map<String, Object> config,
                                           AgentGraphNodeType type,
                                           RuntimeWorkflowProposalGenerationRequest request,
                                           Map<String, RuntimeWorkflowProposalResourceView> resources,
                                           List<String> warnings,
                                           List<RuntimeWorkflowProposalPlaceholderView> placeholders,
                                           String nodeId,
                                           String label) {
        String ref = firstText(text(config.get("ref")), text(config.get("name")), text(config.get("qualifiedName")));
        RuntimeWorkflowProposalResourceView resource = resource(ref, resources);
        if (resource == null) {
            markPlaceholder(config, warnings, placeholders, nodeId, type.canvasKind(), label,
                    StringUtils.hasText(ref) ? "未找到可绑定能力：" + ref : "模型生成了需要绑定的能力节点，请选择 Tool/Capability");
        } else {
            config.put("ref", resource.name());
            config.put("qualifiedName", firstText(resource.qualifiedName(), resource.name()));
            config.put("definitionId", resource.definitionId());
            config.put("projectCode", firstText(resource.projectCode(), request.projectCode()));
        }
        config.putIfAbsent("inputMapping", Map.of("input", "$lastOutput"));
    }

    private void normalizePageActionConfig(Map<String, Object> config,
                                           RuntimeWorkflowProposalGenerationRequest request,
                                           Map<String, RuntimeWorkflowProposalResourceView> resources,
                                           List<String> warnings,
                                           List<RuntimeWorkflowProposalPlaceholderView> placeholders,
                                           String nodeId,
                                           String label,
                                           Set<String> usedPageActions,
                                           int pageActionNodeIndex,
                                           List<String> validationErrors) {
        String ref = firstText(text(config.get("ref")), text(config.get("name")), text(config.get("qualifiedName")),
                text(config.get("actionKey")), text(config.get("action")));
        RuntimeWorkflowProposalResourceView resource = resource(ref, resources);
        if (resource == null && StringUtils.hasText(ref) && isPageAssistantProposal(request)) {
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
            Map<String, Object> metadata = mutableMap(resource.metadata());
            config.put("projectCode", firstText(text(metadata.get("projectCode")), resource.projectCode(), request.projectCode()));
            config.put("pageKey", firstText(text(metadata.get("pageKey")), text(config.get("pageKey"))));
            config.put("routePattern", firstText(text(metadata.get("routePattern")), text(config.get("routePattern"))));
            config.put("actionKey", firstText(text(metadata.get("actionKey")), resource.name(), ref));
            config.put("title", firstText(text(config.get("title")), label, resource.description(), resource.name()));
            config.put("confirm", boolOr(metadata.get("confirmRequired"), bool(config.get("confirm"))));
            config.putIfAbsent("metadata", Map.of(
                    "source", "AI_PROPOSAL",
                    "inputSchema", mutableMap(metadata.get("inputSchema")),
                    "outputSchema", mutableMap(metadata.get("outputSchema")),
                    "sampleArgs", mutableMap(metadata.get("sampleArgs"))));
            usedPageActions.add(firstText(text(metadata.get("actionKey")), resource.name()));
            if (!StringUtils.hasText(ref)) {
                warnings.add(label + "：已根据节点语义自动绑定页面动作 " + resource.name());
            }
        }
        config.put("args", mutableMap(config.get("args")));
        config.put("outputAlias", firstText(text(config.get("outputAlias")), "page_action_result"));
    }

    private RuntimeWorkflowProposalResourceView matchPageActionResource(RuntimeWorkflowProposalGenerationRequest request,
                                                          String nodeId,
                                                          String label,
                                                          String description,
                                                          Set<String> usedPageActions) {
        if (request == null || request.pageActions() == null || request.pageActions().isEmpty()) {
            return null;
        }
        String compactId = compactToken(nodeId);
        String compactLabel = compactToken(firstText(label, description, nodeId));
        RuntimeWorkflowProposalResourceView best = null;
        int bestScore = 0;
        for (RuntimeWorkflowProposalResourceView candidate : request.pageActions()) {
            if (candidate == null || usedPageActions.contains(candidate.name())) {
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

    private RuntimeWorkflowProposalResourceView fallbackPageActionByOrder(RuntimeWorkflowProposalGenerationRequest request,
                                                            Set<String> usedPageActions,
                                                            int pageActionNodeIndex) {
        if (request == null || request.pageActions() == null) {
            return null;
        }
        List<RuntimeWorkflowProposalResourceView> available = request.pageActions().stream()
                .filter(Objects::nonNull)
                .filter(resource -> !usedPageActions.contains(resource.name()))
                .toList();
        if (available.isEmpty()) {
            return null;
        }
        List<String> preferredOrder = List.of("setFilters", "search", "reset", "readTable", "getPageState", "openRowAction");
        for (String preferred : preferredOrder) {
            for (RuntimeWorkflowProposalResourceView resource : available) {
                if (preferred.equalsIgnoreCase(resource.name())) {
                    return resource;
                }
            }
        }
        return pageActionNodeIndex < available.size() ? available.get(pageActionNodeIndex) : available.get(0);
    }

    private int scorePageActionCandidate(RuntimeWorkflowProposalResourceView candidate,
                                         String compactId,
                                         String compactLabel,
                                         String label,
                                         String description) {
        String actionKey = firstText(candidate.name());
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
        String title = firstText(candidate.description(), text(mutableMap(candidate.metadata()).get("title")));
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

    private RuntimeWorkflowProposalResourceView singlePageActionResource(RuntimeWorkflowProposalGenerationRequest request) {
        if (request == null || request.pageActions() == null || request.pageActions().size() != 1) {
            return null;
        }
        return request.pageActions().get(0);
    }

    private void normalizeReferencedOutputAliases(List<ProposalNode> nodes) {
        Set<String> knownAliases = new LinkedHashSet<>();
        for (ProposalNode node : nodes) {
            knownAliases.add(node.id());
            String outputAlias = text(node.config() == null ? null : node.config().get("outputAlias"));
            if (StringUtils.hasText(outputAlias)) {
                knownAliases.add(outputAlias);
            }
        }

        for (int index = 0; index < nodes.size(); index++) {
            ProposalNode node = nodes.get(index);
            if (!"pageAction".equals(node.canvasKind())) {
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
                ProposalNode upstream = nearestExtractLikeNode(nodes, index);
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

    private ProposalNode nearestExtractLikeNode(List<ProposalNode> nodes, int beforeIndex) {
        for (int i = beforeIndex - 1; i >= 0; i--) {
            ProposalNode candidate = nodes.get(i);
            if ("llm".equals(candidate.canvasKind()) || "parameter".equals(candidate.canvasKind())) {
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
                                 List<RuntimeWorkflowProposalPlaceholderView> placeholders,
                                 String nodeId,
                                 String kind,
                                 String label,
                                 String reason) {
        config.put("needsConfiguration", true);
        config.put("placeholderReason", reason);
        warnings.add(label + "：" + reason);
        placeholders.add(new RuntimeWorkflowProposalPlaceholderView(nodeId, kind, label, reason));
    }

    private java.util.Optional<GraphSpec.CapabilityRef> capabilityRef(ProposalNode node) {
        if (!"TOOL".equals(node.type()) && !"MCP_CALL".equals(node.type())) {
            return java.util.Optional.empty();
        }
        Map<String, Object> config = mutableMap(node.config());
        String name = firstText(text(config.get("ref")), text(config.get("name")));
        if (!StringUtils.hasText(name) || bool(config.get("needsConfiguration"))) {
            return java.util.Optional.empty();
        }
        String kind = "TOOL";
        return java.util.Optional.of(GraphSpec.CapabilityRef.builder()
                .kind(kind)
                .name(name)
                .qualifiedName(text(config.get("qualifiedName")))
                .definitionId(longValue(config.get("definitionId")))
                .projectCode(text(config.get("projectCode")))
                .build());
    }

    private List<Map<String, Object>> graphPorts(ProposalNode node, String direction) {
        List<Map<String, Object>> explicit = "input".equals(direction) ? node.inputs() : node.outputs();
        if (explicit != null && !explicit.isEmpty()) {
            return explicit;
        }
        Map<String, Object> config = mutableMap(node.config());
        if ("userInput".equals(node.canvasKind()) && "output".equals(direction)) {
            return userInputOutputPorts(fields(config), firstText(text(config.get("outputAlias")), "params"));
        }
        if ("classifier".equals(node.canvasKind()) && "output".equals(direction)) {
            return classifierOutputPorts(config);
        }
        if ("answer".equals(node.canvasKind())) {
            return "input".equals(direction)
                    ? List.of(Map.of("id", "input", "name", "input", "type", "message", "required", false, "source", "$lastOutput"))
                    : List.of(Map.of("id", "answer", "name", "answer", "type", "message"));
        }
        if ("approval".equals(node.canvasKind()) && "output".equals(direction)) {
            return List.of(
                    Map.of("id", "approved", "name", "approved", "type", "boolean"),
                    Map.of("id", "rejected", "name", "rejected", "type", "boolean"),
                    Map.of("id", "timeout", "name", "timeout", "type", "boolean"));
        }
        if ("input".equals(direction)) {
            return List.of(Map.of("id", "input", "name", "input", "type", "message", "required", false, "source", "$input"));
        }
        String output = firstText(text(config.get("outputAlias")), defaultOutputAlias(node.canvasKind()), "output");
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
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("knowledgeBaseCodes", arrayValue(config.get("knowledgeBaseCodes")));
        out.put("query", firstText(text(config.get("query")), "input"));
        out.put("topK", integer(config.get("topK"), 5));
        out.put("similarityThreshold", doubleOr(config.get("similarityThreshold"), 0.5D));
        out.put("searchMode", firstText(text(config.get("searchMode")), "hybrid"));
        out.put("rerankEnabled", config.get("rerankEnabled") == null || bool(config.get("rerankEnabled")));
        // Workflow GraphSpec must never carry Chat-style directReturn* knobs.
        return out;
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
            case "loop" -> {
                Object bodyIds = config.get("bodyNodeIds");
                out.put("loopConfig", Map.of(
                        "mode", "FOREACH",
                        "collection", firstText(text(config.get("collection")), text(config.get("itemExpression")), ""),
                        "itemAlias", firstText(text(config.get("itemAlias")), "item"),
                        "indexAlias", firstText(text(config.get("indexAlias")), "index"),
                        "outputAlias", firstText(text(config.get("outputAlias")), text(config.get("loopKey")), "loop_results"),
                        "bodyOutput", firstText(text(config.get("bodyOutput")), "lastOutput"),
                        "maxIterations", integer(config.get("maxIterations"), 100),
                        "bodyEntry", firstText(text(config.get("bodyEntry")), ""),
                        "bodyExit", firstText(text(config.get("bodyExit")), text(config.get("bodyEntry")), ""),
                        "bodyNodeIds", bodyIds instanceof List<?> list ? list : List.of()));
            }
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

    private Map<String, RuntimeWorkflowProposalResourceView> resourceIndex(RuntimeWorkflowProposalGenerationRequest request) {
        Map<String, RuntimeWorkflowProposalResourceView> out = new LinkedHashMap<>();
        for (RuntimeWorkflowProposalResourceView resource : resources(request)) {
            if (resource == null) continue;
            indexResource(out, resource.name(), resource);
            indexResource(out, resource.qualifiedName(), resource);
            indexResource(out, resource.description(), resource);
        }
        return out;
    }

    private List<RuntimeWorkflowProposalResourceView> resources(RuntimeWorkflowProposalGenerationRequest request) {
        List<RuntimeWorkflowProposalResourceView> resources = new ArrayList<>();
        if (request.tools() != null) resources.addAll(request.tools());
        if (request.capabilities() != null) resources.addAll(request.capabilities());
        if (request.knowledgeBases() != null) resources.addAll(request.knowledgeBases());
        if (request.pageActions() != null) resources.addAll(request.pageActions());
        return resources;
    }

    private void indexResource(Map<String, RuntimeWorkflowProposalResourceView> out, String key, RuntimeWorkflowProposalResourceView resource) {
        if (StringUtils.hasText(key)) {
            out.put(key(key), resource);
            String compact = compactToken(key);
            if (StringUtils.hasText(compact)) {
                out.putIfAbsent(compact, resource);
            }
        }
    }

    private RuntimeWorkflowProposalResourceView resource(String ref, Map<String, RuntimeWorkflowProposalResourceView> resources) {
        if (!StringUtils.hasText(ref)) return null;
        RuntimeWorkflowProposalResourceView hit = resources.get(key(ref));
        if (hit != null) {
            return hit;
        }
        return resources.get(compactToken(ref));
    }

    private boolean backfillPageAssistantActionsWhenMissing(RuntimeWorkflowProposalGenerationRequest request,
                                                            List<ProposalNode> nodes,
                                                            List<String> warnings) {
        if (request == null || request.pageActions() == null || request.pageActions().isEmpty()) {
            return false;
        }
        boolean hasPageAction = nodes.stream().anyMatch(node -> "pageAction".equals(node.canvasKind()));
        if (hasPageAction) {
            return false;
        }
        if ("INTENT_ROUTER".equals(pageAssistantFlowMode(request))) {
            warnings.add("页面助手存在多个互斥意图，模型未生成 INTENT_CLASSIFIER 分支；请按 requirement 中的 intentClasses 手动补充分类节点");
            return false;
        }

        List<ProposalNode> actionNodes = pageAssistantActionNodes(request);
        if (actionNodes.isEmpty()) {
            return false;
        }

        int insertAt = firstNodeIndex(nodes, "answer");
        if (insertAt < 0) {
            insertAt = nodes.size();
        }
        nodes.addAll(insertAt, actionNodes);
        warnings.add("页面助手提案未包含页面动作节点，已按已选 actionKeys 自动补齐执行链路");
        return true;
    }

    private List<ProposalNode> pageAssistantActionNodes(RuntimeWorkflowProposalGenerationRequest request) {
        List<ProposalNode> nodes = new ArrayList<>();
        if (hasPageAction(request, "setFilters")) {
            nodes.add(new ProposalNode(
                    "extract_filters",
                    "LLM",
                    "llm",
                    "提取筛选条件",
                    "从用户问题提取页面筛选条件",
                    new LinkedHashMap<>(Map.of(
                            "configVersion", 2,
                            "source", "AI_PROPOSAL",
                            "outputAlias", "extracted_filters")),
                    List.of(),
                    List.of()));
        }
        Set<String> added = new LinkedHashSet<>();
        List<String> preferred = List.of("setFilters", "search", "readTable", "getPageState", "reset", "openRowAction");
        for (String actionKey : preferred) {
            RuntimeWorkflowProposalResourceView resource = findPageActionResource(request, actionKey);
            if (resource != null) {
                nodes.add(pageAssistantActionNode(request, resource));
                added.add(compactToken(actionResourceKey(resource)));
            }
        }
        for (RuntimeWorkflowProposalResourceView resource : request.pageActions()) {
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

    private ProposalNode pageAssistantActionNode(RuntimeWorkflowProposalGenerationRequest request, RuntimeWorkflowProposalResourceView resource) {
        Map<String, Object> metadata = mutableMap(resource.metadata());
        String actionKey = actionResourceKey(resource);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("configVersion", 2);
        config.put("source", "AI_PROPOSAL");
        config.put("projectCode", firstText(text(metadata.get("projectCode")), resource.projectCode(), request.projectCode()));
        config.put("pageKey", text(metadata.get("pageKey")));
        config.put("routePattern", text(metadata.get("routePattern")));
        config.put("actionKey", actionKey);
        config.put("title", firstText(resource.description(), actionKey));
        config.put("confirm", bool(metadata.get("confirmRequired")));
        config.put("args", Map.of());
        config.put("outputAlias", "page_action_result");
        config.put("metadata", Map.of(
                "source", "AI_PROPOSAL",
                "inputSchema", mutableMap(metadata.get("inputSchema")),
                "outputSchema", mutableMap(metadata.get("outputSchema")),
                "sampleArgs", mutableMap(metadata.get("sampleArgs"))));
        return new ProposalNode(
                pageAssistantActionNodeId(actionKey),
                "PAGE_ACTION",
                "pageAction",
                firstText(resource.description(), actionKey),
                firstText(resource.description(), actionKey),
                config,
                List.of(),
                List.of());
    }

    private String actionResourceKey(RuntimeWorkflowProposalResourceView resource) {
        if (resource == null) {
            return "";
        }
        Map<String, Object> metadata = mutableMap(resource.metadata());
        return firstText(text(metadata.get("actionKey")), resource.name());
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

    private int firstNodeIndex(List<ProposalNode> nodes, String kind) {
        for (int i = 0; i < nodes.size(); i++) {
            if (kind.equals(nodes.get(i).canvasKind())) {
                return i;
            }
        }
        return -1;
    }

    private void normalizePageAssistantProposal(RuntimeWorkflowProposalGenerationRequest request,
                                           List<ProposalNode> nodes,
                                           List<String> warnings) {
        if (nodes.isEmpty()) {
            return;
        }
        ProposalNode extractNode = findPageAssistantExtractNode(nodes);
        String extractAlias = extractNode == null
                ? "extracted_filters"
                : normalizePageAssistantExtractNode(extractNode, request, warnings);
        String extractNodeId = extractNode == null ? "extract_filters" : extractNode.id();
        for (ProposalNode node : nodes) {
            if (!"pageAction".equals(node.canvasKind())) {
                continue;
            }
            String actionKey = firstText(text(node.config().get("actionKey")), text(node.config().get("action")));
            if (isSetFiltersAction(actionKey)) {
                ensureSetFiltersArgs(node, extractNodeId, extractAlias, request, warnings);
            }
        }
        normalizePageAssistantAnswerNodes(nodes);
    }

    private ProposalNode findPageAssistantExtractNode(List<ProposalNode> nodes) {
        int firstPageActionIndex = -1;
        for (int i = 0; i < nodes.size(); i++) {
            if ("pageAction".equals(nodes.get(i).canvasKind())) {
                firstPageActionIndex = i;
                break;
            }
        }
        if (firstPageActionIndex <= 0) {
            return null;
        }
        for (int i = firstPageActionIndex - 1; i >= 0; i--) {
            ProposalNode candidate = nodes.get(i);
            if ("userInput".equals(candidate.canvasKind())) {
                continue;
            }
            if ("llm".equals(candidate.canvasKind()) || "parameter".equals(candidate.canvasKind())) {
                return candidate;
            }
        }
        return null;
    }

    private String normalizePageAssistantExtractNode(ProposalNode node,
                                                     RuntimeWorkflowProposalGenerationRequest request,
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

    private void ensureSetFiltersArgs(ProposalNode node,
                                      String extractNodeId,
                                      String extractAlias,
                                      RuntimeWorkflowProposalGenerationRequest request,
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

    private void normalizePageAssistantAnswerNodes(List<ProposalNode> nodes) {
        boolean hasPageAction = nodes.stream().anyMatch(node -> "pageAction".equals(node.canvasKind()));
        if (!hasPageAction) {
            return;
        }
        for (ProposalNode node : nodes) {
            if (!"answer".equals(node.canvasKind())) {
                continue;
            }
            Map<String, Object> config = node.config();
            String template = text(config.get("template"));
            if (!StringUtils.hasText(template) || template.contains("lastOutput")) {
                config.put("template", "正在按你的条件查询页面数据，请稍候…");
            }
        }
    }

    private List<String> filterFieldNames(RuntimeWorkflowProposalGenerationRequest request) {
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

    private List<Map<String, Object>> filterFieldDefinitions(RuntimeWorkflowProposalGenerationRequest request) {
        RuntimeWorkflowProposalResourceView setFilters = findPageActionResource(request, "setFilters");
        if (setFilters == null) {
            return List.of();
        }
        Map<String, Object> metadata = mutableMap(setFilters.metadata());
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

    private RuntimeWorkflowProposalResourceView findPageActionResource(RuntimeWorkflowProposalGenerationRequest request, String actionKey) {
        if (request == null || request.pageActions() == null || !StringUtils.hasText(actionKey)) {
            return null;
        }
        String targetCompact = compactToken(actionKey);
        for (RuntimeWorkflowProposalResourceView resource : request.pageActions()) {
            if (resource == null) {
                continue;
            }
            if (actionKey.equalsIgnoreCase(resource.name())) {
                return resource;
            }
            Map<String, Object> metadata = mutableMap(resource.metadata());
            String metaActionKey = text(metadata.get("actionKey"));
            if (actionKey.equalsIgnoreCase(metaActionKey)) {
                return resource;
            }
            if (StringUtils.hasText(targetCompact)
                    && (targetCompact.equals(compactToken(resource.name()))
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

    private boolean isPageAssistantProposal(RuntimeWorkflowProposalGenerationRequest request) {
        if (request == null) {
            return false;
        }
        if ("PAGE_ASSISTANT".equalsIgnoreCase(text(request.workflowKind()))) {
            return true;
        }
        return request.pageActions() != null && !request.pageActions().isEmpty();
    }

    private String callModel(RuntimeWorkflowProposalGenerationRequest request) throws JsonProcessingException {
        ModelChatResult result = modelServiceClient.chat(ModelChatRequest.builder()
                .modelInstanceId(request.modelInstanceId())
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

    private String systemPrompt(RuntimeWorkflowProposalGenerationRequest request) {
        List<RuntimeWorkflowNodeCapabilityDescriptor> authorable = nodeCapabilityRegistry.aiAuthoringCatalog();
        String authorableTypes = authorable.stream()
                .map(RuntimeWorkflowNodeCapabilityDescriptor::type)
                .collect(Collectors.joining(", "));
        String closedTypes = nodeCapabilityRegistry.allCatalog().stream()
                .filter(item -> !item.aiAuthoringEnabled())
                .map(RuntimeWorkflowNodeCapabilityDescriptor::type)
                .collect(Collectors.joining(", "));
        StringBuilder prompt = new StringBuilder("""
                You are Workflow Studio's workflow proposal generator. Return only strict JSON.
                Generate a complete workflow proposal from the user's requirement.
                Use only canonical node types listed in nodeTypes. Use only supplied tools/capabilities/knowledgeBases/pageActions when binding real resources.
                Authorable canonical node types (type must match one of these exact values): %s.
                For pageAction nodes, always set config.ref to the exact actionKey from pageActions.
                If a business step has no matching resource, still generate the node and mark it as a placeholder by setting config.needsConfiguration=true and config.placeholderReason.
                Do not output START/END nodes or edges. Set entryNodeId and exitNodeIds explicitly; every edge.from and edge.to must reference a real node id.
                Never invent node kinds outside nodeTypes. Closed / non-authorable types are forbidden: %s.
                INTERACTION is variant-restricted: author it only with config.interactionType=PRESENT_OUTPUT. COLLECT_INPUT, USER_CHOICE, CONFIRM_ACTION, REVIEW_EDIT and other pause/resume variants are forbidden. PRESENT_OUTPUT must be display-only, must set dataExpression to an explicit producer output when possible, and should use presentation.mode=card_only for structured business results.
                Required JSON shape:
                {"summary":"short summary","entryNodeId":"real node id","exitNodeIds":["real node id"],"nodes":[{"id":"stable_snake_case","type":"canonical node type","label":"display name","description":"what it does","config":{},"inputs":[],"outputs":[]}],"edges":[{"id":"optional","from":"real node id","to":"real node id","condition":"always|approved|rejected|route:key|success|error","sourceHandle":"optional","targetHandle":"optional"}],"warnings":[]}
                inputs and outputs must be arrays of port objects like {"id":"portId","name":"portName","type":"any"}, never bare strings.
                """.formatted(authorableTypes, closedTypes));
        if (isPageAssistantProposal(request)) {
            prompt.append("""

                    PAGE_ASSISTANT proposal rules (mandatory when pageActions are supplied):
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
                    - Do not emit blocking INTERACTION or HUMAN_APPROVAL nodes. INTERACTION is allowed only as display-only PRESENT_OUTPUT.
                    - Every branch that returns structured read data must end with INTERACTION/PRESENT_OUTPUT. Use component=list_card for list/page results and component=output_card or detail for a single object. Set dataExpression to nodeOutput.<producerNodeId> (or a declared output alias), behavior.blocking=false and presentation.mode=card_only.
                    - For confirmRequired or operational actions, emit PAGE_ACTION with config.confirm=true. Page Bridge performs pre-execution confirmation before the action runs; if the user rejects, the action must not execute. ANSWER only reports success, rejection, cancellation or failure afterwards — never request confirmation after PAGE_ACTION has already run.
                    - answer node must use a fixed Chinese status sentence, not {{ lastOutput }}, when the flow ends after page actions.
                    - Bind each pageAction config.ref to the exact actionKey from pageActions.

                    Few-shot A (LINEAR_QUERY, no classifier):
                    selectedActionKeys: setFilters, search, readTable
                    USER_INPUT -> LLM(extracted_filters) -> PAGE_ACTION(setFilters) -> PAGE_ACTION(search) -> PAGE_ACTION(readTable) -> INTERACTION(PRESENT_OUTPUT, list_card, dataExpression=nodeOutput.read_table, card_only)

                    Few-shot B (INTENT_ROUTER with HYBRID classifier):
                    selectedActionKeys: search, reset, getPageState
                    USER_INPUT -> INTENT_CLASSIFIER(strategy=HYBRID)
                    route:search_intent -> PAGE_ACTION(search) -> PAGE_ACTION(readTable) -> INTERACTION(PRESENT_OUTPUT, list_card, dataExpression=nodeOutput.read_table, card_only)
                    route:reset_intent -> PAGE_ACTION(reset) -> ANSWER
                    route:page_state_intent -> PAGE_ACTION(getPageState) -> INTERACTION(PRESENT_OUTPUT, output_card, dataExpression=nodeOutput.get_page_state, card_only)
                    route:else -> ANSWER(请说明要查询、重置还是读取页面状态)

                    Few-shot C (INTENT_ROUTER with confirmRequired operational action):
                    selectedActionKeys: readTable, openRowAction(confirmRequired=true)
                    USER_INPUT -> INTENT_CLASSIFIER(strategy=HYBRID)
                    route:read_table_intent -> PAGE_ACTION(readTable) -> INTERACTION(PRESENT_OUTPUT, list_card, dataExpression=nodeOutput.read_table, card_only)
                    route:row_action_intent -> PAGE_ACTION(openRowAction, confirm=true; Page Bridge confirms before execution) -> ANSWER(status only)
                    route:else -> ANSWER
                    """);
        }
        return prompt.toString();
    }

    private String userPrompt(RuntimeWorkflowProposalGenerationRequest request) throws JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workflowId", request.workflowId());
        payload.put("workflowName", request.workflowName());
        payload.put("projectCode", request.projectCode());
        payload.put("workflowKind", request.workflowKind());
        payload.put("requirement", request.requirement());
        payload.put("modelInstanceId", request.modelInstanceId());
        payload.put("nodeTypes", nodeCapabilityRegistry.aiAuthoringCatalog());
        payload.put("tools", request.tools() == null ? List.of() : request.tools());
        payload.put("capabilities", request.capabilities() == null ? List.of() : request.capabilities());
        payload.put("knowledgeBases", request.knowledgeBases() == null ? List.of() : request.knowledgeBases());
        payload.put("pageActions", request.pageActions() == null ? List.of() : request.pageActions());
        if (isPageAssistantProposal(request)) {
            Map<String, Object> template = new LinkedHashMap<>();
            template.put("entryNodeId", "user_input");
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
            template.put("structuredPresentation", Map.of(
                    "interactionType", "PRESENT_OUTPUT",
                    "listComponent", "list_card",
                    "detailComponent", "output_card",
                    "presentationMode", "card_only",
                    "dataExpressionPattern", "nodeOutput.<producerNodeId>"));
            payload.put("pageAssistantTemplate", template);
        }
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
    }

    private List<String> pageAssistantAllowedActionKeys(RuntimeWorkflowProposalGenerationRequest request) {
        if (request == null || request.pageActions() == null) {
            return List.of();
        }
        return request.pageActions().stream()
                .filter(Objects::nonNull)
                .map(resource -> {
                    Map<String, Object> metadata = mutableMap(resource.metadata());
                    return firstText(text(metadata.get("actionKey")), resource.name());
                })
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private List<String> pageAssistantRecommendedFlow(RuntimeWorkflowProposalGenerationRequest request) {
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

    private String pageAssistantFlowMode(RuntimeWorkflowProposalGenerationRequest request) {
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

    private String pageAssistantActionCategory(RuntimeWorkflowProposalGenerationRequest request, String actionKey) {
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
        RuntimeWorkflowProposalResourceView resource = findPageActionResource(request, actionKey);
        if (resource != null && bool(mutableMap(resource.metadata()).get("confirmRequired"))) {
            return "operational";
        }
        if (compact.contains("openrow") || compact.contains("delete") || compact.contains("submit")
                || compact.contains("approve") || compact.contains("export")) {
            return "operational";
        }
        return "other";
    }

    private List<Map<String, Object>> pageAssistantIntentClasses(RuntimeWorkflowProposalGenerationRequest request) {
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

    private String pageAssistantActionLabel(RuntimeWorkflowProposalGenerationRequest request, String actionKey, String fallback) {
        RuntimeWorkflowProposalResourceView resource = findPageActionResource(request, actionKey);
        return resource == null ? fallback : firstText(resource.description(), actionKey, fallback);
    }

    private boolean pageAssistantActionConfirmRequired(RuntimeWorkflowProposalGenerationRequest request, String actionKey) {
        RuntimeWorkflowProposalResourceView resource = findPageActionResource(request, actionKey);
        return resource != null && bool(mutableMap(resource.metadata()).get("confirmRequired"));
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

    private List<String> pageAssistantSafetyNotes(RuntimeWorkflowProposalGenerationRequest request) {
        if (request == null || request.pageActions() == null) {
            return List.of();
        }
        List<String> notes = new ArrayList<>();
        for (RuntimeWorkflowProposalResourceView resource : request.pageActions()) {
            if (resource == null) {
                continue;
            }
            Map<String, Object> metadata = mutableMap(resource.metadata());
            String actionKey = actionResourceKey(resource);
            if (bool(metadata.get("confirmRequired"))) {
                notes.add(actionKey + " 为需确认动作：route 分支生成 PAGE_ACTION(" + actionKey
                        + ") 且 config.confirm=true；Page Bridge 在动作执行前确认，用户拒绝则不执行；"
                        + "ANSWER 仅报告成功/拒绝/失败，不要生成 INTERACTION，也不要在动作后再要求确认。");
            }
        }
        return notes;
    }

    private boolean hasPageAction(RuntimeWorkflowProposalGenerationRequest request, String actionKey) {
        return findPageActionResource(request, actionKey) != null;
    }

    private void validateProposalJson(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("workflow proposal must be a JSON object");
        }
        assertAllowedFields(root, PROPOSAL_FIELDS, "proposal");
        requireTextField(root, "entryNodeId", "proposal.entryNodeId");
        requireStringArray(root, "exitNodeIds", "proposal.exitNodeIds", true);

        JsonNode nodes = requireArray(root, "nodes", "proposal.nodes");
        for (int index = 0; index < nodes.size(); index++) {
            JsonNode node = nodes.get(index);
            String context = "proposal.nodes[" + index + "]";
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException(context + " must be an object");
            }
            assertAllowedFields(node, PROPOSAL_NODE_FIELDS, context);
            requireTextField(node, "id", context + ".id");
            requireTextField(node, "type", context + ".type");
            if (node.has("config") && !node.get("config").isObject()) {
                throw new IllegalArgumentException(context + ".config must be an object");
            }
            validatePorts(node, "inputs", context);
            validatePorts(node, "outputs", context);
        }

        JsonNode edges = requireArray(root, "edges", "proposal.edges");
        for (int index = 0; index < edges.size(); index++) {
            JsonNode edge = edges.get(index);
            String context = "proposal.edges[" + index + "]";
            if (edge == null || !edge.isObject()) {
                throw new IllegalArgumentException(context + " must be an object");
            }
            assertAllowedFields(edge, PROPOSAL_EDGE_FIELDS, context);
            requireTextField(edge, "from", context + ".from");
            requireTextField(edge, "to", context + ".to");
        }

        if (root.has("warnings")) {
            requireStringArray(root, "warnings", "proposal.warnings", false);
        }
    }

    private void validatePorts(JsonNode node, String field, String context) {
        if (!node.has(field)) {
            return;
        }
        JsonNode ports = node.get(field);
        if (!ports.isArray()) {
            throw new IllegalArgumentException(context + "." + field + " must be an array");
        }
        for (int index = 0; index < ports.size(); index++) {
            JsonNode port = ports.get(index);
            String portContext = context + "." + field + "[" + index + "]";
            if (port == null || !port.isObject()) {
                throw new IllegalArgumentException(portContext + " must be a canonical port object");
            }
            assertAllowedFields(port, PROPOSAL_PORT_FIELDS, portContext);
        }
    }

    private JsonNode requireArray(JsonNode root, String field, String context) {
        JsonNode value = root.get(field);
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException(context + " must be an array");
        }
        return value;
    }

    private void requireStringArray(JsonNode root, String field, String context, boolean requireNonEmpty) {
        JsonNode values = requireArray(root, field, context);
        if (requireNonEmpty && values.isEmpty()) {
            throw new IllegalArgumentException(context + " must contain at least one node id");
        }
        for (int index = 0; index < values.size(); index++) {
            JsonNode value = values.get(index);
            if (value == null || !value.isTextual() || !StringUtils.hasText(value.asText())) {
                throw new IllegalArgumentException(context + "[" + index + "] must be a non-blank string");
            }
        }
    }

    private void requireTextField(JsonNode root, String field, String context) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || !StringUtils.hasText(value.asText())) {
            throw new IllegalArgumentException(context + " must be a non-blank string");
        }
    }

    private void assertAllowedFields(JsonNode object, Set<String> allowedFields, String context) {
        object.fieldNames().forEachRemaining(field -> {
            if (!allowedFields.contains(field)) {
                throw new IllegalArgumentException("unsupported " + context + " field: " + field);
            }
        });
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

    private record ProposalResponse(String summary,
                                    String entryNodeId,
                                    List<String> exitNodeIds,
                                    List<ProposalNodePayload> nodes,
                                    List<ProposalEdge> edges,
                                    List<String> warnings) {
    }

    private record ProposalNodePayload(String id,
                                       String type,
                                       String label,
                                       String description,
                                       Map<String, Object> config,
                                       List<Map<String, Object>> inputs,
                                       List<Map<String, Object>> outputs) {
    }

    private record ProposalNode(String id,
                                String type,
                                String canvasKind,
                                String label,
                                String description,
                                Map<String, Object> config,
                                List<Map<String, Object>> inputs,
                                List<Map<String, Object>> outputs) {
    }

    private record ProposalEdge(String id,
                                String from,
                                String to,
                                String condition,
                                String sourceHandle,
                                String targetHandle) {
    }

    private record ProposalBoundaries(String entryNodeId, List<String> exitNodeIds) {
    }
}
