package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.execution.context.WorkflowVariableNamespaces;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpClient;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionCustomRenderers;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionPresentationPolicy;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionType;
import com.enterprise.ai.runtime.workflow.node.RuntimeWorkflowNodeCapabilityDescriptor;
import com.enterprise.ai.runtime.workflow.node.RuntimeWorkflowNodeCapabilityRegistry;
import com.enterprise.ai.runtime.workflow.node.WorkflowNodeMaturity;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RuntimeWorkflowReleaseValidationService {

    private static final Pattern PAGE_ACTION_DATA_WRAPPER_REFERENCE = Pattern.compile(
            "nodeOutput\\.([A-Za-z0-9_-]+)\\.data(?=\\.|\\b)");

    private final RuntimeControlCatalogClient controlCatalogClient;
    private final ObjectMapper objectMapper;
    private final RuntimeWorkflowNodeCapabilityRegistry nodeCapabilityRegistry;
    private final RuntimeWorkflowResourceBindingService resourceBindingService;

    public RuntimeWorkflowReleaseValidationService(RuntimeControlCatalogClient controlCatalogClient,
                                                   ObjectMapper objectMapper) {
        this(controlCatalogClient, objectMapper, new RuntimeWorkflowNodeCapabilityRegistry(), null);
    }

    public RuntimeWorkflowReleaseValidationService(RuntimeControlCatalogClient controlCatalogClient,
                                                   ObjectMapper objectMapper,
                                                   RuntimeWorkflowNodeCapabilityRegistry nodeCapabilityRegistry) {
        this(controlCatalogClient, objectMapper, nodeCapabilityRegistry, null);
    }

    @Autowired
    public RuntimeWorkflowReleaseValidationService(
            RuntimeControlCatalogClient controlCatalogClient,
            ObjectMapper objectMapper,
            RuntimeWorkflowNodeCapabilityRegistry nodeCapabilityRegistry,
            RuntimeWorkflowResourceBindingService resourceBindingService) {
        this.controlCatalogClient = controlCatalogClient;
        this.objectMapper = objectMapper;
        this.nodeCapabilityRegistry = nodeCapabilityRegistry == null
                ? new RuntimeWorkflowNodeCapabilityRegistry()
                : nodeCapabilityRegistry;
        this.resourceBindingService = resourceBindingService;
    }

    public RuntimeWorkflowReleaseValidationResult validate(RuntimeWorkflowDefinitionEntity workflow) {
        RuntimeWorkflowReleaseValidationResult.Builder report = RuntimeWorkflowReleaseValidationResult.builder();
        if (workflow == null) {
            report.error("WORKFLOW_NOT_FOUND", null, "Workflow does not exist");
            return report.build();
        }
        Set<String> boundPageKeys = validateResourceBindings(workflow, report);
        GraphSpec graph = readGraph(workflow.getGraphSpecJson(), report);
        if (graph == null) {
            return report.build();
        }
        validateGraph(workflow, graph, boundPageKeys, report);
        validateWorkflowKindContract(workflow, graph, report);
        return report.build();
    }

    public RuntimeWorkflowReleaseValidationResult validateProposed(RuntimeWorkflowDefinitionEntity workflow,
                                                                  GraphSpec graphSpec) {
        RuntimeWorkflowReleaseValidationResult.Builder report = RuntimeWorkflowReleaseValidationResult.builder();
        if (workflow == null) {
            report.error("WORKFLOW_NOT_FOUND", null, "Workflow does not exist");
            return report.build();
        }
        Set<String> boundPageKeys = validateResourceBindings(workflow, report);
        if (graphSpec == null) {
            report.error("GRAPH_SPEC_MISSING", null, "GraphSpec is required");
            return report.build();
        }
        validateGraph(workflow, graphSpec, boundPageKeys, report);
        validateWorkflowKindContract(workflow, graphSpec, report);
        return report.build();
    }

    /**
     * Workflow-kind constraints stay beside Runtime release validation so a
     * GraphSpec accepted through AI Coding cannot be rejected later by Studio
     * for a rule that was never exposed by the public contract.
     */
    private void validateWorkflowKindContract(
            RuntimeWorkflowDefinitionEntity workflow,
            GraphSpec graph,
            RuntimeWorkflowReleaseValidationResult.Builder report) {
        if (workflow != null
                && WorkflowSemanticValues.KIND_PAGE_ASSISTANT.equals(workflow.getWorkflowKind())) {
            RuntimeWorkflowInputContract.validatePageAssistant(graph, report);
        }
    }

    public GraphSpec readGraph(String graphSpecJson, RuntimeWorkflowReleaseValidationResult.Builder report) {
        if (!StringUtils.hasText(graphSpecJson)) {
            report.error("GRAPH_SPEC_MISSING", null, "Workflow publishing requires GraphSpec");
            return null;
        }
        try {
            GraphSpec graph = objectMapper.readValue(graphSpecJson, GraphSpec.class);
            if (graph == null) {
                report.error("GRAPH_SPEC_INVALID", null, "GraphSpec JSON must be an object");
                return null;
            }
            return graph;
        } catch (Exception ex) {
            report.error("GRAPH_SPEC_INVALID", null, "GraphSpec JSON is invalid: " + ex.getMessage());
            return null;
        }
    }

    private Set<String> validateResourceBindings(
            RuntimeWorkflowDefinitionEntity workflow,
            RuntimeWorkflowReleaseValidationResult.Builder report) {
        if (!WorkflowSemanticValues.KIND_PAGE_ASSISTANT.equals(workflow.getWorkflowKind())) {
            return Set.of();
        }
        if (resourceBindingService == null) {
            report.error(
                    "PAGE_RESOURCE_BINDING_UNAVAILABLE",
                    null,
                    "PAGE_ASSISTANT release validation cannot read Workflow resource bindings");
            return Set.of();
        }
        List<RuntimeWorkflowResourceBindingService.BindingView> bindings =
                resourceBindingService.list(workflow.getId());
        long targetPages = bindings.stream()
                .filter(binding -> RuntimeWorkflowResourceBindingService.RESOURCE_PAGE
                        .equals(binding.resourceType()))
                .filter(binding -> RuntimeWorkflowResourceBindingService.ROLE_TARGET
                        .equals(binding.bindingRole()))
                .count();
        if (targetPages != 1) {
            report.error(
                    "PAGE_RESOURCE_BINDING_INVALID",
                    null,
                    "PAGE_ASSISTANT workflow requires exactly one active TARGET PAGE resource binding");
        }
        return bindings.stream()
                .filter(binding -> RuntimeWorkflowResourceBindingService.RESOURCE_PAGE
                        .equals(binding.resourceType()))
                .map(RuntimeWorkflowResourceBindingService.BindingView::resourceKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private void validateGraph(RuntimeWorkflowDefinitionEntity workflow,
                               GraphSpec graph,
                               Set<String> boundPageKeys,
                               RuntimeWorkflowReleaseValidationResult.Builder report) {
        if (!Integer.valueOf(2).equals(graph.getSchemaVersion())) {
            report.error("GRAPH_SCHEMA_VERSION_INVALID", null, "GraphSpec schemaVersion must be 2");
        }
        List<GraphSpec.Node> nodes = graph.getNodes() == null ? List.of() : graph.getNodes();
        if (nodes.isEmpty()) {
            report.error("GRAPH_NODE_EMPTY", null, "GraphSpec requires at least one node");
            return;
        }
        List<GraphSpec.Edge> edges = graph.getEdges() == null ? List.of() : graph.getEdges();
        Map<String, GraphSpec.Node> byId = new LinkedHashMap<>();
        Map<String, String> outputAliases = new LinkedHashMap<>();
        Map<String, RuntimeControlCatalogClient.PageActionCatalogEntry> pageActionsByNodeId =
                new LinkedHashMap<>();
        // First pass: index all nodes so LOOP body ownership checks see the full graph.
        for (GraphSpec.Node node : nodes) {
            if (node == null || !StringUtils.hasText(node.getId())) {
                report.error("GRAPH_NODE_ID_EMPTY", null, "GraphSpec node id is required");
                continue;
            }
            String nodeId = node.getId().trim();
            if (byId.putIfAbsent(nodeId, node) != null) {
                report.error("GRAPH_DUPLICATE_NODE_ID", nodeId, "Duplicate node id: " + nodeId);
            }
        }
        for (GraphSpec.Node node : byId.values()) {
            String nodeId = node.getId().trim();
            AgentGraphNodeType canonicalType = AgentGraphNodeType.find(node.getType()).orElse(null);
            boolean knownType = canonicalType != null && canonicalType.type().equals(node.getType());
            String type = knownType ? canonicalType.type() : String.valueOf(node.getType());
            RuntimeWorkflowNodeCapabilityDescriptor capability = nodeCapabilityRegistry.find(type).orElse(null);
            boolean runtimeExecutable = capability != null && capability.runtimeExecutable();
            // The capability registry applies variant-level openness. PRESENT_OUTPUT can publish
            // independently while blocking INTERACTION variants remain behind the pause/resume gate.
            boolean publishable = capability != null
                    && nodeCapabilityRegistry.isPublishable(type, node.getConfig());
            if (!knownType) {
                report.error("GRAPH_NODE_TYPE_UNSUPPORTED", nodeId,
                        "Graph node type must use a canonical value: " + node.getType());
            } else if (canonicalType == AgentGraphNodeType.TOOL
                    && node.getRef() != null
                    && StringUtils.hasText(node.getRef().getKind())
                    && !"TOOL".equals(node.getRef().getKind())) {
                report.error("GRAPH_TOOL_REF_KIND_INVALID", nodeId,
                        "GraphSpec TOOL node ref.kind must be TOOL");
            } else if (!runtimeExecutable) {
                report.error("GRAPH_NODE_RUNTIME_UNSUPPORTED", nodeId,
                        "Graph node type is known but not executable by the current Runtime: " + type);
            } else if (!publishable) {
                String reason = capability == null || !StringUtils.hasText(capability.unavailableReason())
                        ? ("Graph node type is not publishable: " + type)
                        : capability.unavailableReason();
                report.error("GRAPH_NODE_NOT_PUBLISHABLE", nodeId, reason);
                // LOOP structure must still fail-closed while BETA is closed (Debug/tests).
                if ("LOOP".equals(type)) {
                    validateLoopNode(node, byId, edges, report);
                    validateOutputAlias(node, outputAliases, report);
                    validateRetryPolicy(node, report);
                }
            } else {
                if (capability.maturity() == WorkflowNodeMaturity.BETA) {
                    String betaHint = switch (type) {
                        case "PAGE_ACTION" -> " depends on a valid project/page/action catalog and Page Bridge context";
                        case "KNOWLEDGE_RETRIEVAL" -> " depends on Knowledge service availability and ACL";
                        case "HTTP_REQUEST" -> " depends on egress policy, credentials and remote endpoint availability";
                        case "LOOP" -> " FOREACH v1 is bounded/serial; Browser/Live LOOP E2E is still PENDING";
                        case "INTERACTION" -> " PRESENT_OUTPUT is display-only; blocking interaction variants remain closed";
                        default -> "";
                    };
                    report.warn("GRAPH_NODE_BETA", nodeId, "Node type " + type + " is BETA" + betaHint);
                }
                if ("INTENT_CLASSIFIER".equals(type)) {
                    Map<String, Object> config = classifierConfig(node);
                    validateIntentClassifier(node, config, edges, report);
                    if (classifierRequiresModelInstance(config)) {
                        validateModelInstance(workflow, node, config, report);
                    }
                } else if ("IF_ELSE".equals(type)) {
                    validateConditionRoutes(node, conditionConfig(node), edges, report);
                } else if ("LLM".equals(type)) {
                    validateModelInstance(workflow, node, report);
                } else if ("PARAMETER_EXTRACT".equals(type)) {
                    Map<String, Object> config = parameterConfig(node);
                    validateParameterExtract(node, config, report);
                    String mode = firstText(text(config.get("extractMode")), text(config.get("mode")), "expression");
                    if ("LLM".equalsIgnoreCase(mode)) {
                        validateModelInstance(workflow, node, config, report);
                    }
                } else if ("TOOL".equals(type)) {
                    validateToolReference(node, type, report);
                } else if ("INTERACTION".equals(type)) {
                    validateInteractionNode(node, edges, report);
                } else if ("VARIABLE_ASSIGN".equals(type)) {
                    validateVariableAssign(node, report);
                } else if ("TEMPLATE".equals(type)) {
                    validateTemplateNode(node, report);
                } else if ("VARIABLE_AGGREGATOR".equals(type)) {
                    validateVariableAggregator(node, report);
                } else if ("KNOWLEDGE_RETRIEVAL".equals(type)) {
                    validateKnowledgeRetrieval(node, byId, edges, report);
                } else if ("HTTP_REQUEST".equals(type)) {
                    validateHttpRequest(node, report);
                } else if ("LOOP".equals(type)) {
                    validateLoopNode(node, byId, edges, report);
                }
                if ("PAGE_ACTION".equals(type)) {
                    RuntimeControlCatalogClient.PageActionCatalogEntry action =
                            validatePageActionNode(workflow, node, boundPageKeys, report);
                    if (action != null) {
                        pageActionsByNodeId.put(nodeId, action);
                    }
                }
                validateOutputAlias(node, outputAliases, report);
                validateRetryPolicy(node, report);
            }
        }

        for (GraphSpec.Node node : byId.values()) {
            validateErrorPolicy(node, byId.keySet(), report);
        }
        validatePageActionOutputReferences(byId, pageActionsByNodeId, report);
        validateLoopBodyDualOwnership(byId, report);

        String entry = StringUtils.hasText(graph.getEntryNodeId()) ? graph.getEntryNodeId().trim() : null;
        if (entry == null) {
            report.error("GRAPH_ENTRY_MISSING", null, "GraphSpec entry is required");
        } else if (!byId.containsKey(entry)) {
            report.error("GRAPH_ENTRY_INVALID", entry, "GraphSpec entry node does not exist: " + entry);
        }

        Set<String> exitNodeIds = new LinkedHashSet<>();
        for (String exitNodeId : graph.getExitNodeIds()) {
            if (!StringUtils.hasText(exitNodeId)) {
                report.error("GRAPH_EXIT_INVALID", null, "GraphSpec exitNodeIds cannot contain blank values");
                continue;
            }
            String normalizedExit = exitNodeId.trim();
            if (!byId.containsKey(normalizedExit)) {
                report.error("GRAPH_EXIT_INVALID", normalizedExit,
                        "GraphSpec exit node does not exist: " + normalizedExit);
            }
            exitNodeIds.add(normalizedExit);
        }
        if (exitNodeIds.isEmpty()) {
            report.error("GRAPH_EXIT_MISSING", null, "GraphSpec requires at least one exitNodeId");
        }

        for (GraphSpec.Edge edge : edges) {
            if (edge == null) {
                report.error("GRAPH_EDGE_EMPTY", null, "GraphSpec edge item cannot be null");
                continue;
            }
            if (!StringUtils.hasText(edge.getFrom()) || !byId.containsKey(edge.getFrom())) {
                report.error("GRAPH_EDGE_FROM_INVALID", edge.getFrom(), "Edge source node does not exist: " + edge.getFrom());
            }
            if (!StringUtils.hasText(edge.getTo()) || !byId.containsKey(edge.getTo())) {
                report.error("GRAPH_EDGE_TO_INVALID", edge.getTo(), "Edge target node does not exist: " + edge.getTo());
            }
            if (StringUtils.hasText(edge.getFrom()) && exitNodeIds.contains(edge.getFrom())) {
                report.error("GRAPH_EXIT_HAS_OUTGOING_EDGE", edge.getFrom(),
                        "GraphSpec exit node cannot have an outgoing edge: " + edge.getFrom());
            }
        }

        if (entry != null && byId.containsKey(entry)) {
            validateGraphStructure(entry, byId, edges, report);
        }
    }

    private void validateOutputAlias(GraphSpec.Node node,
                                     Map<String, String> aliases,
                                     RuntimeWorkflowReleaseValidationResult.Builder report) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        String alias = text(config.get("outputAlias"));
        if (!StringUtils.hasText(alias)) {
            return;
        }
        if (!WorkflowVariableNamespaces.isValidAlias(alias)) {
            report.error("GRAPH_OUTPUT_ALIAS_INVALID", node.getId(),
                    "outputAlias must be a simple identifier");
            return;
        }
        if (WorkflowVariableNamespaces.isReservedAlias(alias)) {
            // USER_INPUT may intentionally use params; allow only that reserved alias.
            String type = AgentGraphNodeType.normalize(node.getType());
            if (!("USER_INPUT".equals(type) && "params".equals(alias))) {
                report.error("GRAPH_OUTPUT_ALIAS_RESERVED", node.getId(),
                        "outputAlias must not use reserved name: " + alias);
                return;
            }
        }
        String previous = aliases.putIfAbsent(alias, node.getId());
        if (previous != null) {
            report.error("GRAPH_OUTPUT_ALIAS_DUPLICATE", node.getId(),
                    "Duplicate outputAlias '" + alias + "' also used by node " + previous);
        }
    }

    private void validateErrorPolicy(GraphSpec.Node node,
                                     Set<String> nodeIds,
                                     RuntimeWorkflowReleaseValidationResult.Builder report) {
        GraphSpec.ErrorPolicy policy = node.getErrorPolicy();
        if (policy == null || !StringUtils.hasText(policy.getStrategy())) {
            return;
        }
        String strategy = policy.getStrategy().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("TERMINATE", "CONTINUE", "FALLBACK").contains(strategy)) {
            report.error("GRAPH_ERROR_POLICY_INVALID", node.getId(),
                    "ErrorPolicy.strategy must be TERMINATE, CONTINUE or FALLBACK");
            return;
        }
        if ("FALLBACK".equals(strategy)) {
            String fallback = text(policy.getFallbackNodeId());
            if (!StringUtils.hasText(fallback) || !nodeIds.contains(fallback)) {
                report.error("GRAPH_FALLBACK_NODE_MISSING", node.getId(),
                        "ErrorPolicy FALLBACK requires an existing fallbackNodeId");
            } else if (fallback.equals(node.getId())) {
                report.error("GRAPH_FALLBACK_SELF", node.getId(),
                        "ErrorPolicy FALLBACK must not point to the same node");
            }
        }
    }

    private void validateRetryPolicy(GraphSpec.Node node,
                                     RuntimeWorkflowReleaseValidationResult.Builder report) {
        GraphSpec.RetryPolicy retry = node.getRetry();
        if (retry == null || !Boolean.TRUE.equals(retry.getEnabled())) {
            return;
        }
        String nodeType = AgentGraphNodeType.normalize(node.getType());
        Optional<AgentGraphNodeType> type = AgentGraphNodeType.find(nodeType);
        if (type.isEmpty() || !type.get().retryable()) {
            report.error("GRAPH_RETRY_NOT_ALLOWED", node.getId(),
                    "RetryPolicy.enabled is not allowed for non-retryable node type: " + nodeType);
        }
        Integer maxAttempts = retry.getMaxAttempts();
        if (maxAttempts == null || maxAttempts < 1 || maxAttempts > 5) {
            report.error("GRAPH_RETRY_ATTEMPTS_INVALID", node.getId(),
                    "RetryPolicy.maxAttempts must be between 1 and 5");
        }
        Long backoff = retry.getBackoffMs();
        if (backoff != null && (backoff < 0 || backoff > 10_000L)) {
            report.error("GRAPH_RETRY_BACKOFF_INVALID", node.getId(),
                    "RetryPolicy.backoffMs must be between 0 and 10000");
        }
        if ("HTTP_REQUEST".equals(nodeType)) {
            Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
            Map<String, Object> nested = mapValue(config.get("httpConfig"));
            Map<String, Object> effective = nested.isEmpty() ? config : nested;
            String method = firstText(text(effective.get("method")), text(config.get("method")), "GET")
                    .toUpperCase(Locale.ROOT);
            boolean idempotent = "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
            boolean allowNonIdempotent = Boolean.TRUE.equals(effective.get("retryAllowNonIdempotent"))
                    || Boolean.TRUE.equals(config.get("retryAllowNonIdempotent"));
            if (!idempotent && !allowNonIdempotent) {
                report.error("GRAPH_HTTP_RETRY_NON_IDEMPOTENT", node.getId(),
                        "HTTP_REQUEST retry for non-idempotent method requires retryAllowNonIdempotent=true");
            }
        }
    }

    private void validateVariableAssign(GraphSpec.Node node,
                                        RuntimeWorkflowReleaseValidationResult.Builder report) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> assignments = mapValue(config.get("assignments"));
        if (assignments.isEmpty()) {
            report.error("GRAPH_ASSIGNMENTS_REQUIRED", node.getId(),
                    "VARIABLE_ASSIGN requires non-empty assignments");
            return;
        }
        for (String target : assignments.keySet()) {
            try {
                WorkflowVariableNamespaces.normalizeBusinessWriteTarget(target);
            } catch (IllegalArgumentException ex) {
                report.error("GRAPH_ASSIGNMENT_TARGET_INVALID", node.getId(),
                        "Invalid assignment target '" + target + "': " + ex.getMessage());
            }
        }
    }

    private void validateTemplateNode(GraphSpec.Node node,
                                      RuntimeWorkflowReleaseValidationResult.Builder report) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        String template = firstText(text(config.get("template")), text(config.get("content")));
        if (!StringUtils.hasText(template)) {
            report.error("GRAPH_TEMPLATE_REQUIRED", node.getId(), "TEMPLATE requires template");
        }
    }

    private void validateVariableAggregator(GraphSpec.Node node,
                                            RuntimeWorkflowReleaseValidationResult.Builder report) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> nested = mapValue(config.get("aggregateConfig"));
        Map<String, Object> effective = nested.isEmpty() ? config : nested;
        String mode = firstText(text(effective.get("aggregateMode")), text(effective.get("mode")), "object")
                .toLowerCase(Locale.ROOT);
        if (!Set.of("object", "array", "text").contains(mode)) {
            report.error("GRAPH_AGGREGATE_MODE_INVALID", node.getId(),
                    "VARIABLE_AGGREGATOR mode must be object, array or text");
        }
        Object items = firstPresent(effective.get("items"), config.get("items"));
        if (!(items instanceof List<?> list) || list.isEmpty()) {
            report.error("GRAPH_AGGREGATE_ITEMS_REQUIRED", node.getId(),
                    "VARIABLE_AGGREGATOR requires items");
            return;
        }
        Set<String> names = new LinkedHashSet<>();
        for (Object rawItem : list) {
            Map<String, Object> item = mapValue(rawItem);
            String source = firstText(text(item.get("source")), text(item.get("expression")));
            if (!StringUtils.hasText(source)) {
                report.error("GRAPH_AGGREGATE_ITEM_SOURCE_REQUIRED", node.getId(),
                        "VARIABLE_AGGREGATOR item.source is required");
            }
            if ("object".equals(mode)) {
                String name = text(item.get("name"));
                if (!StringUtils.hasText(name)) {
                    report.error("GRAPH_AGGREGATE_ITEM_NAME_REQUIRED", node.getId(),
                            "VARIABLE_AGGREGATOR object mode requires unique item.name");
                } else if (!names.add(name.trim().toLowerCase(Locale.ROOT))) {
                    report.error("GRAPH_AGGREGATE_ITEM_NAME_DUPLICATE", node.getId(),
                            "VARIABLE_AGGREGATOR duplicate item.name: " + name);
                }
            }
        }
    }

    private void validateKnowledgeRetrieval(GraphSpec.Node node,
                                             Map<String, GraphSpec.Node> byId,
                                             List<GraphSpec.Edge> edges,
                                             RuntimeWorkflowReleaseValidationResult.Builder report) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> nested = mapValue(config.get("knowledgeConfig"));
        Map<String, Object> effective = nested.isEmpty() ? config : nested;
        Object codes = firstPresent(effective.get("knowledgeBaseCodes"), config.get("knowledgeBaseCodes"));
        boolean hasCodes = codes instanceof List<?> list && list.stream().anyMatch(item -> StringUtils.hasText(text(item)));
        if (!hasCodes) {
            report.error("GRAPH_KNOWLEDGE_BASE_REQUIRED", node.getId(),
                    "KNOWLEDGE_RETRIEVAL requires knowledgeBaseCodes");
        }
        String query = firstText(text(effective.get("query")), text(config.get("query")));
        if (!StringUtils.hasText(query)) {
            report.error("GRAPH_KNOWLEDGE_QUERY_REQUIRED", node.getId(),
                    "KNOWLEDGE_RETRIEVAL requires query");
        }
        int topK = intValue(firstPresent(effective.get("topK"), config.get("topK")), 5);
        if (topK < 1 || topK > 20) {
            report.error("GRAPH_KNOWLEDGE_TOPK_INVALID", node.getId(),
                    "KNOWLEDGE_RETRIEVAL topK must be between 1 and 20");
        }
        Object threshold = firstPresent(effective.get("similarityThreshold"), config.get("similarityThreshold"));
        if (threshold != null) {
            double value = doubleValue(threshold, 0.5D);
            if (value < 0D || value > 1D) {
                report.error("GRAPH_KNOWLEDGE_THRESHOLD_INVALID", node.getId(),
                        "KNOWLEDGE_RETRIEVAL similarityThreshold must be between 0 and 1");
            }
        }
        String searchMode = firstText(text(effective.get("searchMode")), text(config.get("searchMode")), "hybrid")
                .toLowerCase(Locale.ROOT);
        if (!Set.of("vector", "keyword", "hybrid").contains(searchMode)) {
            report.error("GRAPH_KNOWLEDGE_SEARCH_MODE_INVALID", node.getId(),
                    "KNOWLEDGE_RETRIEVAL searchMode must be vector, keyword or hybrid");
        }
        String rawEvidencePolicy = firstText(
                text(effective.get("evidencePolicy")),
                text(config.get("evidencePolicy")));
        String evidencePolicy = StringUtils.hasText(rawEvidencePolicy)
                ? rawEvidencePolicy.toUpperCase(Locale.ROOT)
                : "OPTIONAL";
        if (!Set.of("REQUIRED", "OPTIONAL").contains(evidencePolicy)) {
            report.error("GRAPH_KNOWLEDGE_EVIDENCE_POLICY_INVALID", node.getId(),
                    "KNOWLEDGE_RETRIEVAL evidencePolicy must be REQUIRED or OPTIONAL");
            return;
        }
        if (!StringUtils.hasText(rawEvidencePolicy)) {
            report.warn("GRAPH_KNOWLEDGE_EVIDENCE_POLICY_LEGACY", node.getId(),
                    "Legacy KNOWLEDGE_RETRIEVAL has no evidencePolicy and keeps OPTIONAL fallback semantics");
        }
        if ("REQUIRED".equals(evidencePolicy)) {
            if (!hasRouteEdge(edges, node.getId(), "evidence")) {
                report.error("GRAPH_KNOWLEDGE_EVIDENCE_ROUTE_MISSING", node.getId(),
                        "REQUIRED KNOWLEDGE_RETRIEVAL must have route:evidence outgoing edge");
            }
            if (!hasRouteEdge(edges, node.getId(), "no_evidence")) {
                report.error("GRAPH_KNOWLEDGE_NO_EVIDENCE_ROUTE_MISSING", node.getId(),
                        "REQUIRED KNOWLEDGE_RETRIEVAL must have route:no_evidence outgoing edge");
            }
            if (hasUnconditionalEdge(edges, node.getId())) {
                report.error("GRAPH_KNOWLEDGE_EVIDENCE_FALLBACK_UNSAFE", node.getId(),
                        "REQUIRED KNOWLEDGE_RETRIEVAL must not have always/success fallback edges");
            }
            edges.stream()
                    .filter(edge -> edge != null && node.getId().equals(text(edge.getFrom())))
                    .filter(edge -> routesEquivalent("no_evidence", routeCondition(edge.getCondition())))
                    .forEach(edge -> {
                        GraphSpec.Node target = byId.get(text(edge.getTo()));
                        if (target == null || !"ANSWER".equalsIgnoreCase(text(target.getType()))) {
                            report.error("GRAPH_KNOWLEDGE_NO_EVIDENCE_TARGET_UNSAFE", node.getId(),
                                    "route:no_evidence must target a deterministic ANSWER node");
                        }
                    });
        }
    }

    private void validateHttpRequest(GraphSpec.Node node,
                                     RuntimeWorkflowReleaseValidationResult.Builder report) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> nested = mapValue(config.get("httpConfig"));
        Map<String, Object> effective = nested.isEmpty() ? config : nested;
        String url = firstText(text(effective.get("url")), text(config.get("url")));
        if (!StringUtils.hasText(url)) {
            report.error("GRAPH_HTTP_URL_REQUIRED", node.getId(), "HTTP_REQUEST requires url");
        }
        String method = firstText(text(effective.get("method")), text(config.get("method")), "GET")
                .toUpperCase(Locale.ROOT);
        if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(method)) {
            report.error("GRAPH_HTTP_METHOD_INVALID", node.getId(),
                    "HTTP_REQUEST method is unsupported: " + method);
        }
        String bodyType = firstText(text(effective.get("bodyType")), text(config.get("bodyType")), "none")
                .toLowerCase(Locale.ROOT);
        if (!Set.of("none", "json", "text").contains(bodyType)) {
            report.error("GRAPH_HTTP_BODY_TYPE_INVALID", node.getId(),
                    "HTTP_REQUEST bodyType must be none, json or text");
        }
        int timeoutMs = intValue(firstPresent(effective.get("timeoutMs"), config.get("timeoutMs")),
                WorkflowHttpClient.DEFAULT_TIMEOUT_MS);
        if (timeoutMs < WorkflowHttpClient.MIN_TIMEOUT_MS || timeoutMs > WorkflowHttpClient.MAX_TIMEOUT_MS) {
            report.error("GRAPH_HTTP_TIMEOUT_INVALID", node.getId(),
                    "HTTP_REQUEST timeoutMs must be between "
                            + WorkflowHttpClient.MIN_TIMEOUT_MS + " and " + WorkflowHttpClient.MAX_TIMEOUT_MS);
        }
    }

    private void validateLoopNode(GraphSpec.Node node,
                                  Map<String, GraphSpec.Node> byId,
                                  List<GraphSpec.Edge> edges,
                                  RuntimeWorkflowReleaseValidationResult.Builder report) {
        Map<String, Object> config = loopConfigMap(node);
        String mode = firstText(text(config.get("mode")), "FOREACH");
        if (!"FOREACH".equalsIgnoreCase(mode)) {
            report.error("GRAPH_LOOP_MODE_UNSUPPORTED", node.getId(),
                    "LOOP v1 only supports FOREACH mode");
        }
        String collection = firstText(text(config.get("collection")), text(config.get("itemExpression")));
        if (!StringUtils.hasText(collection)) {
            report.error("GRAPH_LOOP_COLLECTION_REQUIRED", node.getId(),
                    "LOOP requires collection expression");
        }
        String itemAlias = firstText(text(config.get("itemAlias")), "item");
        String indexAlias = firstText(text(config.get("indexAlias")), "index");
        String outputAlias = firstText(text(config.get("outputAlias")), text(config.get("loopKey")), "loop_results");
        for (String alias : List.of(itemAlias, indexAlias, outputAlias)) {
            if (!WorkflowVariableNamespaces.isValidAlias(alias)
                    || WorkflowVariableNamespaces.isReservedAlias(alias)) {
                report.error("GRAPH_LOOP_ALIAS_INVALID", node.getId(),
                        "LOOP alias is invalid or reserved: " + alias);
            }
        }
        if (itemAlias.equals(indexAlias) || itemAlias.equals(outputAlias) || indexAlias.equals(outputAlias)) {
            report.error("GRAPH_LOOP_ALIAS_CONFLICT", node.getId(),
                    "LOOP itemAlias/indexAlias/outputAlias must be distinct");
        }
        Integer maxIterations = com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor
                .parseLoopMaxIterations(config.get("maxIterations"));
        if (maxIterations == null) {
            report.error("GRAPH_LOOP_MAX_ITERATIONS_INVALID", node.getId(),
                    "LOOP maxIterations must be between 1 and "
                            + com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor.LOOP_HARD_MAX_ITERATIONS
                            + " (missing defaults to "
                            + com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor.LOOP_DEFAULT_MAX_ITERATIONS
                            + ")");
        }
        String bodyEntry = text(config.get("bodyEntry"));
        String bodyExit = firstText(text(config.get("bodyExit")), bodyEntry);
        Set<String> bodyNodeIds = loopBodyNodeIds(config, bodyEntry, bodyExit);
        if (!StringUtils.hasText(bodyEntry) || !byId.containsKey(bodyEntry)) {
            report.error("GRAPH_LOOP_BODY_ENTRY_INVALID", node.getId(),
                    "LOOP bodyEntry is required and must exist");
        }
        if (!StringUtils.hasText(bodyExit) || !byId.containsKey(bodyExit)) {
            report.error("GRAPH_LOOP_BODY_EXIT_INVALID", node.getId(),
                    "LOOP bodyExit is required and must exist");
        }
        if (bodyNodeIds.isEmpty()) {
            report.error("GRAPH_LOOP_BODY_EMPTY", node.getId(), "LOOP bodyNodeIds cannot be empty");
        }
        for (String bodyId : bodyNodeIds) {
            GraphSpec.Node bodyNode = byId.get(bodyId);
            if (bodyNode == null) {
                report.error("GRAPH_LOOP_BODY_NODE_MISSING", node.getId(),
                        "LOOP body node does not exist: " + bodyId);
                continue;
            }
            String bodyType = AgentGraphNodeType.normalize(bodyNode.getType());
            if ("LOOP".equals(bodyType)) {
                report.error("GRAPH_LOOP_NESTED", node.getId(), "LOOP nesting is not supported in v1");
            }
            if ("INTERACTION".equals(bodyType) || "HUMAN_APPROVAL".equals(bodyType)) {
                report.error("GRAPH_LOOP_BODY_FORBIDDEN", node.getId(),
                        "LOOP body cannot include " + bodyType);
            }
            GraphSpec.ErrorPolicy policy = bodyNode.getErrorPolicy();
            if (policy != null
                    && "FALLBACK".equalsIgnoreCase(firstText(text(policy.getStrategy()), ""))
                    && StringUtils.hasText(policy.getFallbackNodeId())
                    && !bodyNodeIds.contains(policy.getFallbackNodeId().trim())) {
                report.error("GRAPH_LOOP_FALLBACK_OUTSIDE_BODY", bodyId,
                        "LOOP body FALLBACK must stay inside the same bodyNodeIds");
            }
        }
        Map<String, String> bodyOwner = bodyOwnerIndex(byId);
        for (GraphSpec.Edge edge : edges) {
            if (edge == null || !StringUtils.hasText(edge.getFrom()) || !StringUtils.hasText(edge.getTo())) {
                continue;
            }
            String from = edge.getFrom().trim();
            String to = edge.getTo().trim();
            String fromOwner = bodyOwner.get(from);
            String toOwner = bodyOwner.get(to);
            boolean fromInThis = bodyNodeIds.contains(from);
            boolean toInThis = bodyNodeIds.contains(to);
            if (fromInThis && !toInThis && !node.getId().equals(to)) {
                report.error("GRAPH_LOOP_BODY_ESCAPE_EDGE", node.getId(),
                        "LOOP body must not have outgoing edges to external nodes: " + from + " -> " + to);
            }
            if (!fromInThis && toInThis && !node.getId().equals(from)) {
                report.error("GRAPH_LOOP_BODY_ENTRY_EDGE", node.getId(),
                        "External nodes must not jump into LOOP body: " + from + " -> " + to);
            }
            if (fromOwner != null && toOwner != null && !fromOwner.equals(toOwner)) {
                report.error("GRAPH_LOOP_BODY_CROSS", node.getId(),
                        "Edges must not cross different LOOP bodies");
            }
        }
        Map<String, List<String>> bodyAdj = new LinkedHashMap<>();
        for (String bodyId : bodyNodeIds) {
            bodyAdj.put(bodyId, new ArrayList<>());
        }
        for (GraphSpec.Edge edge : edges) {
            if (edge == null || !StringUtils.hasText(edge.getFrom()) || !StringUtils.hasText(edge.getTo())) {
                continue;
            }
            String from = edge.getFrom().trim();
            String to = edge.getTo().trim();
            if (bodyNodeIds.contains(from) && bodyNodeIds.contains(to)) {
                bodyAdj.computeIfAbsent(from, key -> new ArrayList<>()).add(to);
            }
        }
        for (String bodyId : bodyNodeIds) {
            GraphSpec.Node bodyNode = byId.get(bodyId);
            if (bodyNode == null) {
                continue;
            }
            GraphSpec.ErrorPolicy policy = bodyNode.getErrorPolicy();
            if (policy != null
                    && "FALLBACK".equalsIgnoreCase(firstText(text(policy.getStrategy()), ""))
                    && StringUtils.hasText(policy.getFallbackNodeId())
                    && bodyNodeIds.contains(policy.getFallbackNodeId().trim())) {
                bodyAdj.computeIfAbsent(bodyId, key -> new ArrayList<>())
                        .add(policy.getFallbackNodeId().trim());
            }
        }
        if (StringUtils.hasText(bodyEntry) && bodyNodeIds.contains(bodyEntry)
                && hasCycle(bodyEntry, bodyAdj, new HashSet<>(), new HashSet<>())) {
            report.error("GRAPH_LOOP_BODY_CYCLE", node.getId(),
                    "LOOP body must be a DAG; arbitrary cycles are not allowed");
        }
        if (StringUtils.hasText(bodyEntry) && bodyNodeIds.contains(bodyEntry)) {
            Set<String> reachable = reachableFrom(bodyEntry, bodyAdj);
            for (String bodyId : bodyNodeIds) {
                if (!reachable.contains(bodyId)) {
                    report.error("GRAPH_LOOP_BODY_UNREACHABLE", node.getId(),
                            "LOOP body node is unreachable from bodyEntry: " + bodyId);
                }
            }
            if (StringUtils.hasText(bodyExit) && bodyNodeIds.contains(bodyExit)
                    && !reachable.contains(bodyExit)) {
                report.error("GRAPH_LOOP_BODY_EXIT_UNREACHABLE", node.getId(),
                        "LOOP bodyExit is unreachable from bodyEntry: " + bodyExit);
            }
        }
    }

    private void validateGraphStructure(String entry,
                                        Map<String, GraphSpec.Node> byId,
                                        List<GraphSpec.Edge> edges,
                                        RuntimeWorkflowReleaseValidationResult.Builder report) {
        Set<String> allBodyNodes = new LinkedHashSet<>();
        for (GraphSpec.Node node : byId.values()) {
            if ("LOOP".equals(AgentGraphNodeType.normalize(node.getType()))) {
                Map<String, Object> config = loopConfigMap(node);
                allBodyNodes.addAll(loopBodyNodeIds(config, text(config.get("bodyEntry")),
                        firstText(text(config.get("bodyExit")), text(config.get("bodyEntry")))));
            }
        }
        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        for (String nodeId : byId.keySet()) {
            adjacency.put(nodeId, new ArrayList<>());
        }
        for (GraphSpec.Edge edge : edges) {
            if (edge == null || !StringUtils.hasText(edge.getFrom()) || !StringUtils.hasText(edge.getTo())) {
                continue;
            }
            String from = edge.getFrom().trim();
            String to = edge.getTo().trim();
            if (!byId.containsKey(from)) {
                continue;
            }
            // Body-internal edges are owned by LOOP executor; exclude from main-graph cycle checks.
            if (allBodyNodes.contains(from) && allBodyNodes.contains(to)) {
                continue;
            }
            if (byId.containsKey(to)) {
                adjacency.computeIfAbsent(from, key -> new ArrayList<>()).add(to);
            }
        }
        for (GraphSpec.Node node : byId.values()) {
            if (allBodyNodes.contains(node.getId())) {
                continue;
            }
            GraphSpec.ErrorPolicy policy = node.getErrorPolicy();
            if (policy != null
                    && "FALLBACK".equalsIgnoreCase(firstText(text(policy.getStrategy()), ""))
                    && StringUtils.hasText(policy.getFallbackNodeId())
                    && byId.containsKey(policy.getFallbackNodeId().trim())) {
                adjacency.computeIfAbsent(node.getId(), key -> new ArrayList<>())
                        .add(policy.getFallbackNodeId().trim());
            }
        }

        Set<String> reachable = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(entry);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!reachable.add(current)) {
                continue;
            }
            for (String next : adjacency.getOrDefault(current, List.of())) {
                if (!reachable.contains(next)) {
                    queue.addLast(next);
                }
            }
        }
        for (String nodeId : byId.keySet()) {
            if (!reachable.contains(nodeId) && !allBodyNodes.contains(nodeId)) {
                report.warn("GRAPH_NODE_UNREACHABLE", nodeId,
                        "Node is unreachable from entry; it will never execute");
            }
        }

        Set<String> stack = new HashSet<>();
        Set<String> visited = new HashSet<>();
        if (hasCycle(entry, adjacency, visited, stack)) {
            report.error("GRAPH_CYCLE_UNSUPPORTED", entry,
                    "Unsupported arbitrary cycle detected outside LOOP body ownership");
        }

        boolean hasAnswerPath = byId.values().stream()
                .anyMatch(node -> reachable.contains(node.getId())
                        && "ANSWER".equals(AgentGraphNodeType.normalize(node.getType())));
        if (!hasAnswerPath) {
            report.warn("GRAPH_ANSWER_PATH_MISSING", entry,
                    "No reachable ANSWER node; workflow may finish without a canonical user answer");
        }
    }

    private Map<String, Object> loopConfigMap(GraphSpec.Node node) {
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

    private Set<String> loopBodyNodeIds(Map<String, Object> config, String bodyEntry, String bodyExit) {
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

    private void validateLoopBodyDualOwnership(Map<String, GraphSpec.Node> byId,
                                               RuntimeWorkflowReleaseValidationResult.Builder report) {
        Map<String, List<String>> owners = new LinkedHashMap<>();
        for (GraphSpec.Node node : byId.values()) {
            if (!"LOOP".equals(AgentGraphNodeType.normalize(node.getType()))) {
                continue;
            }
            Map<String, Object> config = loopConfigMap(node);
            for (String bodyId : loopBodyNodeIds(config, text(config.get("bodyEntry")),
                    firstText(text(config.get("bodyExit")), text(config.get("bodyEntry"))))) {
                owners.computeIfAbsent(bodyId, key -> new ArrayList<>()).add(node.getId());
            }
        }
        for (Map.Entry<String, List<String>> entry : owners.entrySet()) {
            if (entry.getValue().size() > 1) {
                report.error("GRAPH_LOOP_BODY_DUAL_OWNERSHIP", entry.getValue().get(0),
                        "LOOP body node is owned by multiple LOOPs: " + entry.getKey()
                                + " (" + String.join(", ", entry.getValue()) + ")");
            }
        }
    }

    private Map<String, String> bodyOwnerIndex(Map<String, GraphSpec.Node> byId) {
        Map<String, String> owner = new LinkedHashMap<>();
        for (GraphSpec.Node node : byId.values()) {
            if (!"LOOP".equals(AgentGraphNodeType.normalize(node.getType()))) {
                continue;
            }
            Map<String, Object> config = loopConfigMap(node);
            for (String bodyId : loopBodyNodeIds(config, text(config.get("bodyEntry")),
                    firstText(text(config.get("bodyExit")), text(config.get("bodyEntry"))))) {
                owner.putIfAbsent(bodyId, node.getId());
            }
        }
        return owner;
    }

    private Set<String> reachableFrom(String entry, Map<String, List<String>> adjacency) {
        Set<String> reachable = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(entry);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!reachable.add(current)) {
                continue;
            }
            for (String next : adjacency.getOrDefault(current, List.of())) {
                if (!reachable.contains(next)) {
                    queue.addLast(next);
                }
            }
        }
        return reachable;
    }

    private boolean hasCycle(String nodeId,
                             Map<String, List<String>> adjacency,
                             Set<String> visited,
                             Set<String> stack) {
        if (stack.contains(nodeId)) {
            return true;
        }
        if (visited.contains(nodeId)) {
            return false;
        }
        stack.add(nodeId);
        for (String next : adjacency.getOrDefault(nodeId, List.of())) {
            if (hasCycle(next, adjacency, visited, stack)) {
                return true;
            }
        }
        stack.remove(nodeId);
        visited.add(nodeId);
        return false;
    }

    private void validateModelInstance(RuntimeWorkflowDefinitionEntity workflow,
                                       GraphSpec.Node node,
                                       RuntimeWorkflowReleaseValidationResult.Builder report) {
        validateModelInstance(workflow, node, node.getConfig() == null ? Map.of() : node.getConfig(), report);
    }

    private void validateModelInstance(RuntimeWorkflowDefinitionEntity workflow,
                                       GraphSpec.Node node,
                                       Map<String, Object> config,
                                       RuntimeWorkflowReleaseValidationResult.Builder report) {
        String nodeModelInstanceId = text(config.get("modelInstanceId"));
        String workflowModelInstanceId = workflow == null ? null : workflow.getDefaultModelInstanceId();
        if (!StringUtils.hasText(nodeModelInstanceId) && !StringUtils.hasText(workflowModelInstanceId)) {
            report.error("GRAPH_MODEL_INSTANCE_REQUIRED", node.getId(),
                    "Model node requires modelInstanceId on node config or Workflow default model instance");
        }
    }

    private Map<String, Object> classifierConfig(GraphSpec.Node node) {
        Map<String, Object> config = node == null || node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> nested = mapValue(config.get("classifierConfig"));
        if (nested.isEmpty()) {
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

    private boolean classifierRequiresModelInstance(Map<String, Object> config) {
        String strategy = firstText(text(config.get("strategy")), "KEYWORD");
        return "LLM".equalsIgnoreCase(strategy) || "HYBRID".equalsIgnoreCase(strategy);
    }

    private Map<String, Object> conditionConfig(GraphSpec.Node node) {
        Map<String, Object> config = node == null || node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> nested = mapValue(config.get("conditionConfig"));
        if (nested.isEmpty()) {
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

    private void validateConditionRoutes(GraphSpec.Node node,
                                         Map<String, Object> config,
                                         List<GraphSpec.Edge> edges,
                                         RuntimeWorkflowReleaseValidationResult.Builder report) {
        Object rawGroups = config.get("conditionGroups") != null
                ? config.get("conditionGroups")
                : config.get("groups");
        if (rawGroups instanceof List<?> groups) {
            for (Object rawGroup : groups) {
                String groupId = text(mapValue(rawGroup).get("id"));
                if (StringUtils.hasText(groupId) && !hasRouteEdge(edges, node.getId(), groupId)) {
                    report.error("GRAPH_CONDITION_GROUP_ROUTE_MISSING", node.getId(),
                            "IF_ELSE condition group has no matching outgoing edge: " + groupId);
                }
            }
        }
        String defaultRoute = firstText(text(config.get("defaultRoute")), "else");
        if (!hasRouteEdge(edges, node.getId(), defaultRoute)) {
            report.error("GRAPH_CONDITION_DEFAULT_ROUTE_MISSING", node.getId(),
                    "IF_ELSE default route has no matching outgoing edge: " + defaultRoute);
        }
    }

    private Map<String, Object> parameterConfig(GraphSpec.Node node) {
        Map<String, Object> config = node == null || node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> nested = mapValue(config.get("parameterConfig"));
        if (nested.isEmpty()) {
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

    private void validateToolReference(GraphSpec.Node node,
                                       String nodeType,
                                       RuntimeWorkflowReleaseValidationResult.Builder report) {
        if (!StringUtils.hasText(resolveToolReference(node))) {
            report.error("GRAPH_TOOL_REF_REQUIRED", node.getId(),
                    nodeType + " requires ref.qualifiedName/name or a configured qualifiedName/ref/toolName");
        }
    }

    private String resolveToolReference(GraphSpec.Node node) {
        if (node.getRef() != null) {
            String reference = firstText(
                    text(node.getRef().getQualifiedName()),
                    text(node.getRef().getName()));
            if (StringUtils.hasText(reference)) {
                return reference;
            }
        }
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> nested = mapValue(config.get("toolConfig"));
        return firstText(
                text(config.get("qualifiedName")),
                configuredReference(config.get("ref")),
                text(config.get("toolName")),
                nested.isEmpty() ? null : text(nested.get("qualifiedName")),
                nested.isEmpty() ? null : configuredReference(nested.get("ref")),
                nested.isEmpty() ? null : text(nested.get("toolName")));
    }

    private String configuredReference(Object rawReference) {
        Map<String, Object> reference = mapValue(rawReference);
        if (!reference.isEmpty()) {
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

    private void validateParameterExtract(GraphSpec.Node node,
                                          Map<String, Object> config,
                                          RuntimeWorkflowReleaseValidationResult.Builder report) {
        Object rawFields = config.get("fields");
        if (!(rawFields instanceof List<?> fields) || fields.isEmpty()) {
            report.error("GRAPH_PARAMETER_FIELDS_REQUIRED", node.getId(),
                    "PARAMETER_EXTRACT requires at least one target field");
            return;
        }
        Set<String> names = new LinkedHashSet<>();
        for (Object item : fields) {
            String name = text(mapValue(item).get("name"));
            if (!StringUtils.hasText(name)) {
                report.error("GRAPH_PARAMETER_FIELD_NAME_EMPTY", node.getId(),
                        "PARAMETER_EXTRACT field name is required");
            } else if (!names.add(name.toLowerCase(Locale.ROOT))) {
                report.error("GRAPH_PARAMETER_FIELD_NAME_DUPLICATE", node.getId(),
                        "Duplicate PARAMETER_EXTRACT field name: " + name);
            }
        }
        String mode = firstText(text(config.get("extractMode")), text(config.get("mode")), "expression");
        String inputExpression = firstText(text(config.get("inputExpression")), "input");
        String userPrompt = text(config.get("userPrompt"));
        if ("LLM".equalsIgnoreCase(mode)
                && inputExpression.startsWith("nodeOutput.")
                && StringUtils.hasText(userPrompt)
                && !compactWhitespace(userPrompt).contains(compactWhitespace(inputExpression))) {
            report.error("GRAPH_PARAMETER_USER_PROMPT_INPUT_MISSING", node.getId(),
                    "PARAMETER_EXTRACT userPrompt overrides inputExpression; include {{ "
                            + inputExpression + " }} in userPrompt or leave userPrompt empty");
        }
    }

    private String compactWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    private void validateIntentClassifier(GraphSpec.Node node,
                                          Map<String, Object> config,
                                          List<GraphSpec.Edge> edges,
                                          RuntimeWorkflowReleaseValidationResult.Builder report) {
        Object rawClasses = config.get("classes");
        List<?> classes = rawClasses instanceof List<?> items ? items : List.of();
        if (classes.isEmpty()) {
            report.error("GRAPH_CLASSIFIER_CLASSES_REQUIRED", node.getId(),
                    "INTENT_CLASSIFIER requires at least one class");
        }

        Set<String> classIds = new LinkedHashSet<>();
        Set<String> normalizedClassIds = new LinkedHashSet<>();
        for (Object item : classes) {
            Map<String, Object> classConfig = mapValue(item);
            String classId = text(classConfig.get("id"));
            if (!StringUtils.hasText(classId)) {
                report.error("GRAPH_CLASSIFIER_CLASS_ID_EMPTY", node.getId(),
                        "INTENT_CLASSIFIER class id is required");
                continue;
            }
            if (!normalizedClassIds.add(classId.toLowerCase(Locale.ROOT))) {
                report.error("GRAPH_CLASSIFIER_CLASS_ID_DUPLICATE", node.getId(),
                        "Duplicate INTENT_CLASSIFIER class id: " + classId);
                continue;
            }
            classIds.add(classId);
        }

        for (String classId : classIds) {
            if (!hasRouteEdge(edges, node.getId(), classId)) {
                report.error("GRAPH_CLASSIFIER_CLASS_ROUTE_MISSING", node.getId(),
                        "INTENT_CLASSIFIER class has no matching outgoing edge: " + classId);
            }
        }
        String defaultRoute = firstText(text(config.get("defaultRoute")), "else");
        if (!hasRouteEdge(edges, node.getId(), defaultRoute)) {
            report.error("GRAPH_CLASSIFIER_DEFAULT_ROUTE_MISSING", node.getId(),
                    "INTENT_CLASSIFIER default route has no matching outgoing edge: " + defaultRoute);
        }
    }

    private boolean hasRouteEdge(List<GraphSpec.Edge> edges, String nodeId, String route) {
        return edges.stream()
                .filter(edge -> edge != null && nodeId.equals(text(edge.getFrom())))
                .map(GraphSpec.Edge::getCondition)
                .map(this::routeCondition)
                .anyMatch(condition -> routesEquivalent(route, condition));
    }

    private boolean routesEquivalent(String route, String condition) {
        if (route.equalsIgnoreCase(condition)) {
            return true;
        }
        boolean routeIsDefault = "else".equalsIgnoreCase(route) || "default".equalsIgnoreCase(route);
        boolean conditionIsDefault = "else".equalsIgnoreCase(condition) || "default".equalsIgnoreCase(condition);
        return routeIsDefault && conditionIsDefault;
    }

    private String routeCondition(String rawCondition) {
        String condition = text(rawCondition);
        if (condition.regionMatches(true, 0, "route:", 0, "route:".length())) {
            return condition.substring("route:".length()).trim();
        }
        return condition;
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private void validateInteractionNode(GraphSpec.Node node,
                                         List<GraphSpec.Edge> edges,
                                         RuntimeWorkflowReleaseValidationResult.Builder report) {
        Map<String, Object> config = interactionConfig(node);
        Object rawType = firstPresent(config.get("interactionType"), config.get("mode"), config.get("type"));
        WorkflowInteractionType type;
        try {
            type = WorkflowInteractionType.from(rawType);
            // Unknown tokens collapse to CUSTOM in from(); reject ambiguous legacy mode names.
            if (rawType != null) {
                String normalized = String.valueOf(rawType).trim().replace('-', '_').toUpperCase(Locale.ROOT);
                if ("CONFIRM_ACTION".equals(normalized) || "COLLECT_INPUT".equals(normalized)
                        || "USER_CHOICE".equals(normalized) || "PRESENT_OUTPUT".equals(normalized)
                        || "CUSTOM".equals(normalized) || "REVIEW_EDIT".equals(normalized)) {
                    type = WorkflowInteractionType.valueOf(normalized);
                } else if (StringUtils.hasText(normalized)
                        && type == WorkflowInteractionType.CUSTOM
                        && !"CUSTOM".equals(normalized)) {
                    report.error("GRAPH_INTERACTION_TYPE_UNSUPPORTED", node.getId(),
                            "Unsupported INTERACTION interactionType: " + rawType);
                    return;
                }
            }
        } catch (Exception ex) {
            report.error("GRAPH_INTERACTION_TYPE_UNSUPPORTED", node.getId(),
                    "Unsupported INTERACTION interactionType: " + rawType);
            return;
        }
        if (type == WorkflowInteractionType.REVIEW_EDIT) {
            report.error("GRAPH_INTERACTION_TYPE_UNSUPPORTED", node.getId(),
                    "REVIEW_EDIT is not part of the formal INTERACTION publish contract");
            return;
        }
        validateInteractionPresentation(node, type, config, report);
        switch (type) {
            case COLLECT_INPUT -> validateCollectInputInteraction(node, config, report);
            case USER_CHOICE -> validateUserChoiceInteraction(node, config, edges, report);
            case CONFIRM_ACTION -> validateConfirmActionInteraction(node, config, edges, report);
            case PRESENT_OUTPUT -> validatePresentOutputInteraction(node, config, report);
            case CUSTOM -> validateCustomInteraction(node, config, report);
            default -> report.error("GRAPH_INTERACTION_TYPE_UNSUPPORTED", node.getId(),
                    "Unsupported INTERACTION interactionType: " + type);
        }
        Object schema = config.get("schema");
        if (schema instanceof String schemaText && containsUnsafeMarkup(schemaText)) {
            report.error("GRAPH_INTERACTION_SCHEMA_UNSAFE", node.getId(),
                    "INTERACTION schema must not contain HTML/JS/script markup");
        }
    }

    private Map<String, Object> interactionConfig(GraphSpec.Node node) {
        Map<String, Object> config = node == null || node.getConfig() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(node.getConfig());
        Map<String, Object> nested = mapValue(config.get("interactionConfig"));
        if (!nested.isEmpty()) {
            nested.forEach(config::putIfAbsent);
        }
        return config;
    }

    private void validateCollectInputInteraction(GraphSpec.Node node,
                                                 Map<String, Object> config,
                                                 RuntimeWorkflowReleaseValidationResult.Builder report) {
        Object rawFields = config.get("fields");
        if (!(rawFields instanceof List<?> fields) || fields.isEmpty()) {
            report.error("GRAPH_INTERACTION_FIELDS_REQUIRED", node.getId(),
                    "COLLECT_INPUT requires at least one field");
            return;
        }
        Set<String> keys = new LinkedHashSet<>();
        for (Object item : fields) {
            Map<String, Object> field = mapValue(item);
            String key = firstText(text(field.get("key")), text(field.get("name")));
            if (!StringUtils.hasText(key)) {
                report.error("GRAPH_INTERACTION_FIELD_KEY_EMPTY", node.getId(),
                        "COLLECT_INPUT field key is required");
                continue;
            }
            if (!keys.add(key.toLowerCase(Locale.ROOT))) {
                report.error("GRAPH_INTERACTION_FIELD_KEY_DUPLICATE", node.getId(),
                        "Duplicate COLLECT_INPUT field key: " + key);
            }
            String fieldType = firstText(text(field.get("type")), "string").toLowerCase(Locale.ROOT);
            if (!Set.of("string", "text", "number", "integer", "boolean", "bool", "enum", "select", "multi_select",
                    "object", "array", "date", "datetime").contains(fieldType)) {
                report.error("GRAPH_INTERACTION_FIELD_TYPE_INVALID", node.getId(),
                        "COLLECT_INPUT field type is invalid: " + fieldType);
            }
            if (Set.of("enum", "select", "multi_select").contains(fieldType)
                    && !(field.get("options") instanceof List<?> options && !options.isEmpty())) {
                report.error("GRAPH_INTERACTION_FIELD_OPTIONS_REQUIRED", node.getId(),
                        "COLLECT_INPUT enum/select/multi_select field requires options: " + key);
            }
        }
        String outputAlias = text(config.get("outputAlias"));
        if (StringUtils.hasText(outputAlias) && !outputAlias.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            report.error("GRAPH_INTERACTION_OUTPUT_ALIAS_INVALID", node.getId(),
                    "COLLECT_INPUT outputAlias must be a simple identifier");
        }
    }

    private void validateUserChoiceInteraction(GraphSpec.Node node,
                                               Map<String, Object> config,
                                               List<GraphSpec.Edge> edges,
                                               RuntimeWorkflowReleaseValidationResult.Builder report) {
        List<?> options = config.get("options") instanceof List<?> list ? list : List.of();
        if (options.isEmpty()) {
            report.error("GRAPH_INTERACTION_OPTIONS_REQUIRED", node.getId(),
                    "USER_CHOICE requires at least one option");
            return;
        }
        Set<String> values = new LinkedHashSet<>();
        for (Object item : options) {
            Map<String, Object> option = mapValue(item);
            String value = firstText(text(option.get("value")), text(option.get("id")), text(option.get("key")));
            if (!StringUtils.hasText(value)) {
                report.error("GRAPH_INTERACTION_OPTION_VALUE_EMPTY", node.getId(),
                        "USER_CHOICE option value is required");
                continue;
            }
            if (!values.add(value.toLowerCase(Locale.ROOT))) {
                report.error("GRAPH_INTERACTION_OPTION_VALUE_DUPLICATE", node.getId(),
                        "Duplicate USER_CHOICE option value: " + value);
            }
            String route = firstText(text(option.get("route")), value);
            if (!hasRouteEdge(edges, node.getId(), route)
                    && !hasUnconditionalEdge(edges, node.getId())) {
                report.error("GRAPH_INTERACTION_OPTION_ROUTE_MISSING", node.getId(),
                        "USER_CHOICE option has no matching outgoing edge: " + route);
            }
        }
    }

    private void validateConfirmActionInteraction(GraphSpec.Node node,
                                                  Map<String, Object> config,
                                                  List<GraphSpec.Edge> edges,
                                                  RuntimeWorkflowReleaseValidationResult.Builder report) {
        boolean hasConfirm = hasRouteEdge(edges, node.getId(), "confirm")
                || hasRouteEdge(edges, node.getId(), "approve");
        if (!hasConfirm) {
            report.error("GRAPH_INTERACTION_CONFIRM_ROUTE_MISSING", node.getId(),
                    "CONFIRM_ACTION requires an outgoing confirm/approve route edge");
        }
        boolean rejectOrCancelHasRoute = hasRouteEdge(edges, node.getId(), "reject")
                || hasRouteEdge(edges, node.getId(), "cancel")
                || hasRouteEdge(edges, node.getId(), "deny");
        boolean hasAlways = hasUnconditionalEdge(edges, node.getId());
        if (hasAlways && !rejectOrCancelHasRoute) {
            // reject/cancel must not fall through an always edge into a protected write.
            report.error("GRAPH_INTERACTION_REJECT_FALLBACK_UNSAFE", node.getId(),
                    "CONFIRM_ACTION reject/cancel must not fall through an always edge; declare explicit routes");
        }
        Object protectedAction = firstPresent(config.get("protectedAction"), config.get("actionKey"));
        if (protectedAction != null && !StringUtils.hasText(text(protectedAction))) {
            report.error("GRAPH_INTERACTION_PROTECTED_ACTION_EMPTY", node.getId(),
                    "CONFIRM_ACTION protectedAction cannot be blank when declared");
        }
    }

    private void validatePresentOutputInteraction(GraphSpec.Node node,
                                                  Map<String, Object> config,
                                                  RuntimeWorkflowReleaseValidationResult.Builder report) {
        String component = firstText(text(config.get("component")), text(config.get("renderer")), "detail")
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        if (!Set.of("detail", "card", "table", "list_card", "summary_card", "output_card", "markdown")
                .contains(component)) {
            report.error("GRAPH_INTERACTION_PRESENT_RENDERER_UNSUPPORTED", node.getId(),
                    "PRESENT_OUTPUT renderer/component is not supported: " + component);
        }
        Object blocking = mapValue(config.get("behavior")).get("blocking");
        if (Boolean.TRUE.equals(blocking)) {
            report.error("GRAPH_INTERACTION_PRESENT_MUST_NOT_BLOCK", node.getId(),
                    "PRESENT_OUTPUT must be display-only (blocking=false)");
        }
        if ("list_card".equals(component)) {
            validateListCardRenderSchema(node, config, report);
        }
    }

    private void validateInteractionPresentation(GraphSpec.Node node,
                                                 WorkflowInteractionType type,
                                                 Map<String, Object> config,
                                                 RuntimeWorkflowReleaseValidationResult.Builder report) {
        Object rawPresentation = config.get("presentation");
        if (rawPresentation == null) {
            return;
        }
        if (!(rawPresentation instanceof Map<?, ?>)) {
            report.error("GRAPH_INTERACTION_PRESENTATION_INVALID", node.getId(),
                    "INTERACTION presentation must be an object");
            return;
        }
        Map<String, Object> presentation = mapValue(rawPresentation);
        Object rawMode = presentation.get("mode");
        if (rawMode == null) {
            return;
        }
        String mode = WorkflowInteractionPresentationPolicy.normalizeMode(rawMode);
        if (!StringUtils.hasText(mode)) {
            report.error("GRAPH_INTERACTION_PRESENTATION_MODE_UNSUPPORTED", node.getId(),
                    "Unsupported INTERACTION presentation.mode: " + rawMode);
            return;
        }
        if (type.blocking() && WorkflowInteractionPresentationPolicy.TEXT_ONLY.equals(mode)) {
            report.error("GRAPH_INTERACTION_PRESENTATION_TEXT_ONLY_UNSAFE", node.getId(),
                    "Blocking INTERACTION nodes cannot hide their interactive card");
        }
    }

    private void validateListCardRenderSchema(GraphSpec.Node node,
                                              Map<String, Object> config,
                                              RuntimeWorkflowReleaseValidationResult.Builder report) {
        Map<String, Object> schema = mapValue(config.get("renderSchema"));
        if (schema.isEmpty()) {
            schema = mapValue(config.get("schema"));
        }
        Object initialVisibleCount = schema.get("initialVisibleCount");
        if (initialVisibleCount != null) {
            int count = intValue(initialVisibleCount, -1);
            if (count < 1 || count > 50) {
                report.error("GRAPH_INTERACTION_LIST_CARD_VISIBLE_COUNT_INVALID", node.getId(),
                        "LIST_CARD initialVisibleCount must be between 1 and 50");
            }
        }
        Object fields = schema.get("fields");
        if (fields != null && !(fields instanceof List<?>)) {
            report.error("GRAPH_INTERACTION_LIST_CARD_FIELDS_INVALID", node.getId(),
                    "LIST_CARD fields must be an array");
        }
    }

    private void validateCustomInteraction(GraphSpec.Node node,
                                           Map<String, Object> config,
                                           RuntimeWorkflowReleaseValidationResult.Builder report) {
        Map<String, Object> renderSchema = mapValue(config.get("renderSchema"));
        String rendererKey = firstText(text(renderSchema.get("rendererKey")), text(config.get("rendererKey")));
        if (!WorkflowInteractionCustomRenderers.isSupported(rendererKey)) {
            report.error("GRAPH_INTERACTION_CUSTOM_RENDERER_UNSUPPORTED", node.getId(),
                    "CUSTOM rendererKey is not in the shared whitelist");
            return;
        }
        Object schema = firstPresent(renderSchema.get("schema"), config.get("schema"), renderSchema);
        if (schema == null || (schema instanceof Map<?, ?> map && map.isEmpty())) {
            report.error("GRAPH_INTERACTION_CUSTOM_SCHEMA_REQUIRED", node.getId(),
                    "CUSTOM interaction requires a schema");
            return;
        }
        if (schema instanceof String schemaText && containsUnsafeMarkup(schemaText)) {
            report.error("GRAPH_INTERACTION_SCHEMA_UNSAFE", node.getId(),
                    "CUSTOM schema must not contain HTML/JS/script markup");
        } else if (schema instanceof Map<?, ?> schemaMap) {
            String serialized = String.valueOf(schemaMap);
            if (containsUnsafeMarkup(serialized)) {
                report.error("GRAPH_INTERACTION_SCHEMA_UNSAFE", node.getId(),
                        "CUSTOM schema must not contain HTML/JS/script markup");
            }
        }
    }

    private boolean hasUnconditionalEdge(List<GraphSpec.Edge> edges, String nodeId) {
        return edges.stream()
                .filter(edge -> edge != null && nodeId.equals(text(edge.getFrom())))
                .map(GraphSpec.Edge::getCondition)
                .map(this::routeCondition)
                .anyMatch(condition -> !StringUtils.hasText(condition)
                        || "always".equalsIgnoreCase(condition)
                        || "success".equalsIgnoreCase(condition));
    }

    private boolean containsUnsafeMarkup(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("<script")
                || lower.contains("javascript:")
                || lower.contains("onerror=")
                || lower.contains("onload=")
                || lower.contains("<iframe")
                || lower.contains("<img");
    }

    private Object firstPresent(Object first, Object... rest) {
        if (first != null) {
            return first;
        }
        if (rest != null) {
            for (Object item : rest) {
                if (item != null) {
                    return item;
                }
            }
        }
        return null;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private RuntimeControlCatalogClient.PageActionCatalogEntry validatePageActionNode(
            RuntimeWorkflowDefinitionEntity workflow,
            GraphSpec.Node node,
            Set<String> boundPageKeys,
            RuntimeWorkflowReleaseValidationResult.Builder report) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        String pageKey = text(config.get("pageKey"));
        String actionKey = text(config.get("actionKey"));
        String projectCode = firstText(text(config.get("projectCode")), workflow.getProjectCode());
        if (!StringUtils.hasText(pageKey)) {
            report.error("GRAPH_PAGE_ACTION_PAGE_KEY_EMPTY", node.getId(), "PAGE_ACTION node requires pageKey");
        }
        if (!StringUtils.hasText(actionKey)) {
            report.error("GRAPH_PAGE_ACTION_KEY_EMPTY", node.getId(), "PAGE_ACTION node requires actionKey");
        }
        if (!StringUtils.hasText(projectCode) || !StringUtils.hasText(pageKey) || !StringUtils.hasText(actionKey)) {
            return null;
        }
        if (WorkflowSemanticValues.KIND_PAGE_ASSISTANT.equals(workflow.getWorkflowKind())
                && !boundPageKeys.contains(pageKey)) {
            report.error(
                    "GRAPH_PAGE_ACTION_PAGE_UNBOUND",
                    node.getId(),
                    "PAGE_ACTION pageKey is not bound to this Workflow: " + pageKey);
        }

        RuntimeControlCatalogClient.PageActionCatalogEntry action;
        try {
            action = controlCatalogClient.getPageAction(projectCode, pageKey, actionKey);
        } catch (FeignException.NotFound ex) {
            action = null;
        }
        if (action == null) {
            report.error("GRAPH_PAGE_ACTION_CATALOG_MISSING", node.getId(),
                    "PAGE_ACTION catalog entry does not exist: " + projectCode + "/" + pageKey + "/" + actionKey);
            return null;
        }
        if (!"ACTIVE".equalsIgnoreCase(action.status())) {
            report.error("GRAPH_PAGE_ACTION_CATALOG_INACTIVE", node.getId(),
                    "PAGE_ACTION catalog entry is not ACTIVE: " + projectCode + "/" + pageKey + "/" + actionKey);
        }
        Map<String, Object> inputSchema = mapValue(action.inputSchema());
        Map<String, Object> args = mapValue(config.get("args"));
        Object requiredValue = inputSchema.get("required");
        if (requiredValue instanceof Iterable<?> requiredFields) {
            List<String> missing = new ArrayList<>();
            for (Object requiredField : requiredFields) {
                String field = text(requiredField);
                if (StringUtils.hasText(field) && !args.containsKey(field)) {
                    missing.add(field);
                }
            }
            if (!missing.isEmpty()) {
                report.error(
                        "GRAPH_PAGE_ACTION_ARGS_REQUIRED_MISSING",
                        node.getId(),
                        "PAGE_ACTION args are missing required fields: " + String.join(", ", missing));
            }
        }
        if (action.confirmRequired()) {
            report.warn(
                    "GRAPH_PAGE_ACTION_CONFIRM_REQUIRED",
                    node.getId(),
                    "PAGE_ACTION requires explicit runtime confirmation: " + actionKey);
        }
        return action;
    }

    /**
     * Page Bridge removes the action result envelope before publishing a PAGE_ACTION node output:
     * {@code nodeOutput.<nodeId>} is the business action's declared {@code data} value itself.
     * A second {@code .data} silently resolves to null unless that property is explicitly part of
     * the business output schema, so fail the release while the mistake is still actionable.
     */
    private void validatePageActionOutputReferences(
            Map<String, GraphSpec.Node> nodesById,
            Map<String, RuntimeControlCatalogClient.PageActionCatalogEntry> pageActionsByNodeId,
            RuntimeWorkflowReleaseValidationResult.Builder report) {
        for (GraphSpec.Node consumer : nodesById.values()) {
            validatePageActionOutputReferences(
                    consumer.getConfig(),
                    consumer.getId(),
                    pageActionsByNodeId,
                    report);
        }
    }

    private void validatePageActionOutputReferences(
            Object value,
            String consumerNodeId,
            Map<String, RuntimeControlCatalogClient.PageActionCatalogEntry> pageActionsByNodeId,
            RuntimeWorkflowReleaseValidationResult.Builder report) {
        if (value instanceof Map<?, ?> map) {
            for (Object nested : map.values()) {
                validatePageActionOutputReferences(nested, consumerNodeId, pageActionsByNodeId, report);
            }
            return;
        }
        if (value instanceof Iterable<?> items) {
            for (Object nested : items) {
                validatePageActionOutputReferences(nested, consumerNodeId, pageActionsByNodeId, report);
            }
            return;
        }
        if (!(value instanceof String expression) || !expression.contains("nodeOutput.")) {
            return;
        }
        Matcher matcher = PAGE_ACTION_DATA_WRAPPER_REFERENCE.matcher(expression);
        while (matcher.find()) {
            String producerNodeId = matcher.group(1);
            RuntimeControlCatalogClient.PageActionCatalogEntry action =
                    pageActionsByNodeId.get(producerNodeId);
            if (action == null || declaresRootProperty(action.outputSchema(), "data")) {
                continue;
            }
            String corrected = expression.substring(0, matcher.start())
                    + "nodeOutput." + producerNodeId
                    + expression.substring(matcher.end());
            report.error(
                    "GRAPH_PAGE_ACTION_OUTPUT_DATA_REDUNDANT",
                    consumerNodeId,
                    "PAGE_ACTION nodeOutput already is the business action data. Use "
                            + corrected + " instead of adding another .data after nodeOutput."
                            + producerNodeId);
        }
    }

    private boolean declaresRootProperty(Object schemaValue, String propertyName) {
        Map<String, Object> schema = mapValue(schemaValue);
        Map<String, Object> properties = mapValue(schema.get("properties"));
        return properties.containsKey(propertyName);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }
}
