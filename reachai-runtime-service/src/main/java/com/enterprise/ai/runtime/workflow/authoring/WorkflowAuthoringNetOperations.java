package com.enterprise.ai.runtime.workflow.authoring;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalEditOperationType;
import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalEditOperationView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the net GraphSpec operations from the original working copy to the final proposal.
 */
final class WorkflowAuthoringNetOperations {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private WorkflowAuthoringNetOperations() {
    }

    static List<RuntimeWorkflowProposalEditOperationView> diff(ObjectMapper objectMapper,
                                                            GraphSpec original,
                                                            GraphSpec candidate) {
        GraphSpec from = original == null
                ? GraphSpec.builder().nodes(List.of()).edges(List.of()).build()
                : original;
        GraphSpec to = candidate == null
                ? GraphSpec.builder().nodes(List.of()).edges(List.of()).build()
                : candidate;
        Map<String, GraphSpec.Node> fromNodes = indexNodes(from);
        Map<String, GraphSpec.Node> toNodes = indexNodes(to);
        Map<String, GraphSpec.Edge> fromEdges = indexEdges(from);
        Map<String, GraphSpec.Edge> toEdges = indexEdges(to);

        List<RuntimeWorkflowProposalEditOperationView> operations = new ArrayList<>();
        for (Map.Entry<String, GraphSpec.Node> entry : toNodes.entrySet()) {
            GraphSpec.Node previous = fromNodes.get(entry.getKey());
            if (previous == null) {
                operations.add(RuntimeWorkflowProposalEditOperationView.builder()
                        .type(RuntimeWorkflowProposalEditOperationType.ADD_NODE)
                        .node(toMap(objectMapper, entry.getValue()))
                        .reason("net add node")
                        .build());
            } else if (!sameJson(objectMapper, previous, entry.getValue())) {
                operations.add(RuntimeWorkflowProposalEditOperationView.builder()
                        .type(RuntimeWorkflowProposalEditOperationType.UPDATE_NODE)
                        .nodeId(entry.getKey())
                        .patch(toMap(objectMapper, entry.getValue()))
                        .reason("net update node")
                        .build());
            }
        }
        for (String nodeId : fromNodes.keySet()) {
            if (!toNodes.containsKey(nodeId)) {
                operations.add(RuntimeWorkflowProposalEditOperationView.builder()
                        .type(RuntimeWorkflowProposalEditOperationType.DELETE_NODE)
                        .nodeId(nodeId)
                        .reason("net delete node")
                        .build());
            }
        }
        for (Map.Entry<String, GraphSpec.Edge> entry : toEdges.entrySet()) {
            GraphSpec.Edge previous = fromEdges.get(entry.getKey());
            if (previous == null) {
                operations.add(RuntimeWorkflowProposalEditOperationView.builder()
                        .type(RuntimeWorkflowProposalEditOperationType.ADD_EDGE)
                        .edge(toMap(objectMapper, entry.getValue()))
                        .reason("net add edge")
                        .build());
            } else if (!sameJson(objectMapper, previous, entry.getValue())) {
                operations.add(RuntimeWorkflowProposalEditOperationView.builder()
                        .type(RuntimeWorkflowProposalEditOperationType.UPDATE_EDGE)
                        .edgeId(entry.getKey())
                        .patch(toMap(objectMapper, entry.getValue()))
                        .reason("net update edge")
                        .build());
            }
        }
        for (String edgeId : fromEdges.keySet()) {
            if (!toEdges.containsKey(edgeId)) {
                operations.add(RuntimeWorkflowProposalEditOperationView.builder()
                        .type(RuntimeWorkflowProposalEditOperationType.DELETE_EDGE)
                        .edgeId(edgeId)
                        .reason("net delete edge")
                        .build());
            }
        }
        if (!sameJson(objectMapper, from.getInputSchema(), to.getInputSchema())
                && to.getInputSchema() != null
                && !to.getInputSchema().isEmpty()) {
            operations.add(RuntimeWorkflowProposalEditOperationView.builder()
                    .type(RuntimeWorkflowProposalEditOperationType.SET_INPUT_SCHEMA)
                    .patch(toMap(objectMapper, to.getInputSchema()))
                    .reason("net set input schema")
                    .build());
        }
        String fromEntry = text(from.getEntryNodeId());
        String toEntry = text(to.getEntryNodeId());
        if (!Objects.equals(fromEntry, toEntry) && StringUtils.hasText(toEntry)) {
            operations.add(RuntimeWorkflowProposalEditOperationView.builder()
                    .type(RuntimeWorkflowProposalEditOperationType.SET_ENTRY_NODE)
                    .patch(Map.of("entryNodeId", toEntry))
                    .reason("net set entry node")
                    .build());
        }
        List<String> fromFinish = from.getExitNodeIds();
        List<String> toFinish = to.getExitNodeIds();
        if (!fromFinish.equals(toFinish)) {
            operations.add(RuntimeWorkflowProposalEditOperationView.builder()
                    .type(RuntimeWorkflowProposalEditOperationType.SET_EXIT_NODES)
                    .patch(Map.of("exitNodeIds", toFinish))
                    .reason("net set exit nodes")
                    .build());
        }
        return List.copyOf(operations);
    }

    private static Map<String, GraphSpec.Node> indexNodes(GraphSpec graph) {
        Map<String, GraphSpec.Node> nodes = new LinkedHashMap<>();
        if (graph.getNodes() == null) {
            return nodes;
        }
        for (GraphSpec.Node node : graph.getNodes()) {
            if (node != null && StringUtils.hasText(node.getId())) {
                nodes.put(node.getId().trim(), node);
            }
        }
        return nodes;
    }

    private static Map<String, GraphSpec.Edge> indexEdges(GraphSpec graph) {
        Map<String, GraphSpec.Edge> edges = new LinkedHashMap<>();
        if (graph.getEdges() == null) {
            return edges;
        }
        for (GraphSpec.Edge edge : graph.getEdges()) {
            String id = edgeId(edge);
            if (StringUtils.hasText(id)) {
                edges.put(id, edge);
            }
        }
        return edges;
    }

    private static String edgeId(GraphSpec.Edge edge) {
        if (edge == null) {
            return "";
        }
        if (StringUtils.hasText(edge.getId())) {
            return edge.getId().trim();
        }
        if (StringUtils.hasText(edge.getFrom()) && StringUtils.hasText(edge.getTo())) {
            return edge.getFrom().trim() + "->" + edge.getTo().trim();
        }
        return "";
    }

    private static Map<String, Object> toMap(ObjectMapper objectMapper, Object value) {
        if (value == null) {
            return Map.of();
        }
        return objectMapper.convertValue(value, MAP_TYPE);
    }

    private static boolean sameJson(ObjectMapper objectMapper, Object left, Object right) {
        return objectMapper.valueToTree(left).equals(objectMapper.valueToTree(right));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
