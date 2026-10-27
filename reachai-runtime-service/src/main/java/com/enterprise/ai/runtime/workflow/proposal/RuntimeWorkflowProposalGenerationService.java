package com.enterprise.ai.runtime.workflow.proposal;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest.ChatMessage;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.workflow.layout.RuntimeWorkflowCanvasLayoutService;
import com.enterprise.ai.runtime.workflow.node.RuntimeWorkflowNodeCapabilityRegistry;
import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalDraft.ProposalBoundaries;
import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalDraft.ProposalEdge;
import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalDraft.ProposalNode;
import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalDraft.ProposalNodePayload;
import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalDraft.ProposalResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
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

import static com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalValues.arrayOrDefault;
import static com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalValues.arrayValue;
import static com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalValues.blankToNull;
import static com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalValues.bool;
import static com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalValues.boolOr;
import static com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalValues.compactToken;
import static com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalValues.doubleOr;
import static com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalValues.firstText;
import static com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalValues.integer;
import static com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalValues.key;
import static com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalValues.longValue;
import static com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalValues.slug;
import static com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalValues.text;

@Service
public class RuntimeWorkflowProposalGenerationService {

    private static final String PROVIDER = "LLM_PROPOSAL";

    private final RuntimeWorkflowProposalValues values;
    private final RuntimeWorkflowPageAssistantProposalPolicy pageAssistantPolicy;
    private final RuntimeWorkflowProposalPromptBuilder prompts;
    private final RuntimeWorkflowProposalJsonCodec proposalJson;
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
        this.modelServiceClient = modelServiceClient;
        this.canvasLayoutService = canvasLayoutService;
        this.candidateValidationService = candidateValidationService;
        this.repairService = repairService;
        this.nodeCapabilityRegistry = nodeCapabilityRegistry == null
                ? new RuntimeWorkflowNodeCapabilityRegistry()
                : nodeCapabilityRegistry;
        this.values = new RuntimeWorkflowProposalValues(objectMapper);
        this.pageAssistantPolicy = new RuntimeWorkflowPageAssistantProposalPolicy(values);
        this.prompts = new RuntimeWorkflowProposalPromptBuilder(objectMapper, this.nodeCapabilityRegistry, pageAssistantPolicy);
        this.proposalJson = new RuntimeWorkflowProposalJsonCodec(objectMapper);
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
            proposal = proposalJson.read(raw);
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
        boolean backfilledPageActions = pageAssistantPolicy.normalize(request, normalizedNodes, warnings);
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
        Map<String, Object> canvas = canvasLayoutService.projectAndLayout(
                graphSpec, Map.of(),
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

            Map<String, Object> config = values.mutableMap(raw.config());
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
        Map<String, Object> config = values.mutableMap(node.config());
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
        config.put("fields", values.fields(config).isEmpty()
                ? List.of(Map.of("name", "question", "type", "string", "required", true, "description", "用户问题", "source", "input.message"))
                : values.fields(config));
    }

    private void normalizeAnswerConfig(Map<String, Object> config) {
        config.put("template", firstText(text(config.get("template")), "{{ lastOutput }}"));
        config.put("writeToAnswer", true);
    }

    private void normalizeClassifierConfig(Map<String, Object> config) {
        config.put("inputExpression", firstText(text(config.get("inputExpression")), "input"));
        config.put("strategy", normalizeClassifierStrategy(text(config.get("strategy"))));
        config.put("classes", values.classes(config).isEmpty()
                ? List.of(Map.of("id", "matched", "label", "匹配", "keywords", List.of()))
                : values.classes(config));
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

        Map<String, Object> behavior = values.mutableMap(config.get("behavior"));
        behavior.put("blocking", false);
        behavior.put("readonly", true);
        config.put("behavior", behavior);

        Map<String, Object> presentation = values.mutableMap(config.get("presentation"));
        presentation.put("mode", firstText(text(presentation.get("mode")), "card_only"));
        config.put("presentation", presentation);
        config.put("renderSchema", values.mutableMap(config.get("renderSchema")));
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
        // Retrieval knobs left empty use the knowledge base's own settings at run time.
        if (config.get("topK") != null) config.put("topK", integer(config.get("topK"), 5));
        if (config.get("similarityThreshold") != null) {
            config.put("similarityThreshold", doubleOr(config.get("similarityThreshold"), 0.5D));
        }
        String searchMode = text(config.get("searchMode"));
        if (StringUtils.hasText(searchMode)) {
            searchMode = searchMode.toLowerCase(Locale.ROOT);
            config.put("searchMode", Set.of("vector", "keyword", "hybrid").contains(searchMode) ? searchMode : "hybrid");
        } else {
            config.remove("searchMode");
        }
        if (config.get("rerankEnabled") != null) config.put("rerankEnabled", bool(config.get("rerankEnabled")));
        String evidencePolicy = firstText(text(config.get("evidencePolicy")), "REQUIRED")
                .toUpperCase(Locale.ROOT);
        if (!Set.of("REQUIRED", "OPTIONAL").contains(evidencePolicy)) {
            warnings.add("知识检索节点 " + nodeId + " 的 evidencePolicy 无效，已按 REQUIRED 处理");
            evidencePolicy = "REQUIRED";
        }
        config.put("evidencePolicy", evidencePolicy);
        // Direct return is not a retrieval feature; drop stale knobs from model output.
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
        Map<String, Object> source = values.mutableMap(raw);
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
        if (resource == null && StringUtils.hasText(ref) && pageAssistantPolicy.isPageAssistantProposal(request)) {
            markPlaceholder(config, warnings, placeholders, nodeId, "pageAction", label,
                    "页面动作未在本次选择/注册的 actionKeys 中：" + ref);
            validationErrors.add("PAGE_ASSISTANT pageAction is not supplied by selected pageActions: " + ref);
            config.put("args", values.mutableMap(config.get("args")));
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
            Map<String, Object> metadata = values.mutableMap(resource.metadata());
            config.put("projectCode", firstText(text(metadata.get("projectCode")), resource.projectCode(), request.projectCode()));
            config.put("pageKey", firstText(text(metadata.get("pageKey")), text(config.get("pageKey"))));
            config.put("routePattern", firstText(text(metadata.get("routePattern")), text(config.get("routePattern"))));
            config.put("actionKey", firstText(text(metadata.get("actionKey")), resource.name(), ref));
            config.put("title", firstText(text(config.get("title")), label, resource.description(), resource.name()));
            config.put("confirm", boolOr(metadata.get("confirmRequired"), bool(config.get("confirm"))));
            config.putIfAbsent("metadata", Map.of(
                    "source", "AI_PROPOSAL",
                    "inputSchema", values.mutableMap(metadata.get("inputSchema")),
                    "outputSchema", values.mutableMap(metadata.get("outputSchema")),
                    "sampleArgs", values.mutableMap(metadata.get("sampleArgs"))));
            usedPageActions.add(firstText(text(metadata.get("actionKey")), resource.name()));
            if (!StringUtils.hasText(ref)) {
                warnings.add(label + "：已根据节点语义自动绑定页面动作 " + resource.name());
            }
        }
        config.put("args", values.mutableMap(config.get("args")));
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
        String title = firstText(candidate.description(), text(values.mutableMap(candidate.metadata()).get("title")));
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
        Map<String, Object> config = values.mutableMap(node.config());
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
        Map<String, Object> config = values.mutableMap(node.config());
        if ("userInput".equals(node.canvasKind()) && "output".equals(direction)) {
            return userInputOutputPorts(values.fields(config), firstText(text(config.get("outputAlias")), "params"));
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
        for (Map<String, Object> item : values.classes(config)) {
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

    private List<Map<String, Object>> messages(Map<String, Object> config) {
        Object raw = config.get("messages");
        if (raw instanceof List<?> list && !list.isEmpty()) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(values::mutableMap)
                    .toList();
        }
        return List.of(
                Map.of("id", "system", "role", "system", "content", firstText(text(config.get("systemPrompt")), ""), "templateEngine", "mustache", "enabled", true),
                Map.of("id", "user", "role", "user", "content", firstText(text(config.get("userPrompt")), "{{ input }}"), "templateEngine", "mustache", "enabled", true));
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

    private String callModel(RuntimeWorkflowProposalGenerationRequest request) throws JsonProcessingException {
        ModelChatResult result = modelServiceClient.chat(ModelChatRequest.builder()
                .modelInstanceId(request.modelInstanceId())
                .messages(List.of(
                        ChatMessage.builder().role("system").content(prompts.systemPrompt(request)).build(),
                        ChatMessage.builder().role("user").content(prompts.userPrompt(request)).build()))
                .options(Map.of("temperature", 0.2))
                .build());
        if (result == null || result.getData() == null || !StringUtils.hasText(result.getData().getContent())) {
            throw new IllegalArgumentException("empty model response");
        }
        return result.getData().getContent();
    }

    private String defaultOutputAlias(String kind) {
        if ("userInput".equals(kind)) return "params";
        if (!List.of("start", "end", "llm", "condition", "answer").contains(kind)) return kind + "_output";
        return "";
    }

}
