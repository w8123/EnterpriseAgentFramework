package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RuntimeWorkflowReleaseValidationService {

    private static final String END = "END";

    private final RuntimeControlCatalogClient controlCatalogClient;
    private final ObjectMapper objectMapper;

    public RuntimeWorkflowReleaseValidationResult validate(RuntimeWorkflowDefinitionEntity workflow) {
        RuntimeWorkflowReleaseValidationResult.Builder report = RuntimeWorkflowReleaseValidationResult.builder();
        if (workflow == null) {
            report.error("WORKFLOW_NOT_FOUND", null, "Workflow does not exist");
            return report.build();
        }
        GraphSpec graph = readGraph(workflow.getGraphSpecJson(), report);
        if (graph == null) {
            return report.build();
        }
        validateGraph(workflow, graph, report);
        return report.build();
    }

    public RuntimeWorkflowReleaseValidationResult validateProposed(RuntimeWorkflowDefinitionEntity workflow,
                                                                  GraphSpec graphSpec) {
        RuntimeWorkflowReleaseValidationResult.Builder report = RuntimeWorkflowReleaseValidationResult.builder();
        if (workflow == null) {
            report.error("WORKFLOW_NOT_FOUND", null, "Workflow does not exist");
            return report.build();
        }
        if (graphSpec == null) {
            report.error("GRAPH_SPEC_MISSING", null, "GraphSpec is required");
            return report.build();
        }
        validateGraph(workflow, graphSpec, report);
        return report.build();
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

    private void validateGraph(RuntimeWorkflowDefinitionEntity workflow,
                               GraphSpec graph,
                               RuntimeWorkflowReleaseValidationResult.Builder report) {
        List<GraphSpec.Node> nodes = graph.getNodes() == null ? List.of() : graph.getNodes();
        if (nodes.isEmpty()) {
            report.error("GRAPH_NODE_EMPTY", null, "GraphSpec requires at least one node");
            return;
        }
        List<GraphSpec.Edge> edges = graph.getEdges() == null ? List.of() : graph.getEdges();
        Map<String, GraphSpec.Node> byId = new LinkedHashMap<>();
        for (GraphSpec.Node node : nodes) {
            if (node == null || !StringUtils.hasText(node.getId())) {
                report.error("GRAPH_NODE_ID_EMPTY", null, "GraphSpec node id is required");
                continue;
            }
            String nodeId = node.getId().trim();
            if (byId.putIfAbsent(nodeId, node) != null) {
                report.error("GRAPH_DUPLICATE_NODE_ID", nodeId, "Duplicate node id: " + nodeId);
            }
            String type = AgentGraphNodeType.normalize(node.getType());
            boolean knownType = AgentGraphNodeType.supports(type);
            boolean runtimeExecutable = RuntimeGraphSpecExecutor.supportsNodeType(type);
            if (!knownType) {
                report.error("GRAPH_NODE_TYPE_UNSUPPORTED", nodeId, "Unsupported graph node type: " + node.getType());
            } else if (!runtimeExecutable) {
                report.error("GRAPH_NODE_RUNTIME_UNSUPPORTED", nodeId,
                        "Graph node type is known but not executable by the current Runtime: " + type);
            } else if ("INTENT_CLASSIFIER".equals(type)) {
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
            } else if ("TOOL".equals(type) || "CAPABILITY".equals(type)) {
                validateToolReference(node, type, report);
            }
            if (knownType && runtimeExecutable && "PAGE_ACTION".equals(type)) {
                validatePageActionNode(workflow, node, report);
            }
        }

        String entry = StringUtils.hasText(graph.getEntry()) ? graph.getEntry().trim() : null;
        if (entry == null) {
            report.error("GRAPH_ENTRY_MISSING", null, "GraphSpec entry is required");
        } else if (!byId.containsKey(entry)) {
            report.error("GRAPH_ENTRY_INVALID", entry, "GraphSpec entry node does not exist: " + entry);
        }

        for (GraphSpec.Edge edge : edges) {
            if (edge == null) {
                report.error("GRAPH_EDGE_EMPTY", null, "GraphSpec edge item cannot be null");
                continue;
            }
            if (!StringUtils.hasText(edge.getFrom()) || (!byId.containsKey(edge.getFrom()) && !"START".equalsIgnoreCase(edge.getFrom()))) {
                report.error("GRAPH_EDGE_FROM_INVALID", edge.getFrom(), "Edge source node does not exist: " + edge.getFrom());
            }
            if (!StringUtils.hasText(edge.getTo()) || (!byId.containsKey(edge.getTo()) && !END.equalsIgnoreCase(edge.getTo()))) {
                report.error("GRAPH_EDGE_TO_INVALID", edge.getTo(), "Edge target node does not exist: " + edge.getTo());
            }
        }
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
        Object nestedConfig = config.get("toolConfig") != null
                ? config.get("toolConfig")
                : config.get("capabilityConfig");
        Map<String, Object> nested = mapValue(nestedConfig);
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

    private void validatePageActionNode(RuntimeWorkflowDefinitionEntity workflow,
                                        GraphSpec.Node node,
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
            return;
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
            return;
        }
        if (!"ACTIVE".equalsIgnoreCase(action.status())) {
            report.error("GRAPH_PAGE_ACTION_CATALOG_INACTIVE", node.getId(),
                    "PAGE_ACTION catalog entry is not ACTIVE: " + projectCode + "/" + pageKey + "/" + actionKey);
        }
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
