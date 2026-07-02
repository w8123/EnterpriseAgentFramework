package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            return objectMapper.readValue(graphSpecJson, GraphSpec.class);
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
            if (!AgentGraphNodeType.supports(type)) {
                report.error("GRAPH_NODE_TYPE_UNSUPPORTED", nodeId, "Unsupported graph node type: " + node.getType());
            }
            if (requiresModelInstance(type)) {
                validateModelInstance(workflow, node, report);
            }
            if ("PAGE_ACTION".equals(type)) {
                validatePageActionNode(workflow, node, report);
            }
        }

        String entry = StringUtils.hasText(graph.getEntry()) ? graph.getEntry().trim() : null;
        if (entry == null) {
            report.error("GRAPH_ENTRY_MISSING", null, "GraphSpec entry is required");
        } else if (!byId.containsKey(entry)) {
            report.error("GRAPH_ENTRY_INVALID", entry, "GraphSpec entry node does not exist: " + entry);
        }

        for (GraphSpec.Edge edge : graph.getEdges() == null ? List.<GraphSpec.Edge>of() : graph.getEdges()) {
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
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        String nodeModelInstanceId = text(config.get("modelInstanceId"));
        String workflowModelInstanceId = workflow == null ? null : workflow.getDefaultModelInstanceId();
        if (!StringUtils.hasText(nodeModelInstanceId) && !StringUtils.hasText(workflowModelInstanceId)) {
            report.error("GRAPH_MODEL_INSTANCE_REQUIRED", node.getId(),
                    "Model node requires modelInstanceId on node config or Workflow default model instance");
        }
    }

    private boolean requiresModelInstance(String type) {
        return "LLM".equals(type)
                || "INTENT_CLASSIFIER".equals(type)
                || "PARAMETER_EXTRACT".equals(type);
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

    private String firstText(String first, String fallback) {
        return StringUtils.hasText(first) ? first : fallback;
    }
}
