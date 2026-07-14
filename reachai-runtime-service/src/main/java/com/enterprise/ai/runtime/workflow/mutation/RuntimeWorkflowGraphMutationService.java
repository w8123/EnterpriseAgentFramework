package com.enterprise.ai.runtime.workflow.mutation;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Applies atomic, GraphSpec-first workflow mutations.
 *
 * <p>This service deliberately has no authentication, persistence, LLM or canvas responsibilities.
 * Web authoring, external AI Coding clients and future MCP/CLI adapters can therefore share exactly
 * the same semantic mutation rules while keeping their own transport and authorization boundaries.</p>
 */
@Service
@RequiredArgsConstructor
public class RuntimeWorkflowGraphMutationService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public MutationResult mutate(GraphSpec source, List<MutationOperation> operations) {
        if (source == null) {
            throw new IllegalArgumentException("GraphSpec is required");
        }
        GraphSpec candidate = deepCopy(source, GraphSpec.class);
        List<MutationOperation> actualOperations = operations == null ? List.of() : operations;
        MutationAccumulator accumulator = new MutationAccumulator();
        for (int index = 0; index < actualOperations.size(); index++) {
            MutationOperation operation = actualOperations.get(index);
            if (operation == null || operation.op() == null) {
                throw new IllegalArgumentException("operation[" + index + "].op is required");
            }
            apply(candidate, operation, accumulator);
        }
        return new MutationResult(
                candidate,
                actualOperations.size(),
                List.copyOf(accumulator.changedNodes),
                List.copyOf(accumulator.changedEdges));
    }

    private void apply(GraphSpec graph,
                       MutationOperation operation,
                       MutationAccumulator accumulator) {
        switch (operation.op()) {
            case ADD_NODE -> addNode(graph, operation.node(), accumulator);
            case UPDATE_NODE -> updateNode(graph, operation.nodeId(), operation.patch(), accumulator);
            case DELETE_NODE -> deleteNode(graph, operation.nodeId(), accumulator);
            case ADD_EDGE -> addEdge(graph, operation.edge(), accumulator);
            case UPDATE_EDGE -> updateEdge(graph, operation.edgeId(), operation.patch(), accumulator);
            case DELETE_EDGE -> deleteEdge(graph, operation.edgeId(), operation.edge(), accumulator);
            case SET_ENTRY -> setEntry(graph, operation.entry(), accumulator);
            case SET_FINISH -> setFinish(graph, operation.finish(), accumulator);
        }
    }

    private void addNode(GraphSpec graph,
                         GraphSpec.Node node,
                         MutationAccumulator accumulator) {
        if (node == null || !StringUtils.hasText(node.getId())) {
            throw new IllegalArgumentException("ADD_NODE requires node.id");
        }
        String nodeId = node.getId().trim();
        if (findNode(graph, nodeId) != null) {
            throw new IllegalArgumentException("duplicate graph node id: " + nodeId);
        }
        GraphSpec.Node candidate = deepCopy(node, GraphSpec.Node.class);
        candidate.setId(nodeId);
        List<GraphSpec.Node> nodes = mutableNodes(graph);
        nodes.add(candidate);
        graph.setNodes(nodes);
        accumulator.changedNode(nodeId);
    }

    private void updateNode(GraphSpec graph,
                            String nodeId,
                            Map<String, Object> patch,
                            MutationAccumulator accumulator) {
        String id = requireText(nodeId, "UPDATE_NODE requires nodeId");
        GraphSpec.Node current = findNode(graph, id);
        if (current == null) {
            throw new IllegalArgumentException("graph node not found: " + id);
        }
        Map<String, Object> actualPatch = mutableMap(patch);
        String patchedId = text(actualPatch.get("id"));
        if (StringUtils.hasText(patchedId) && !id.equals(patchedId)) {
            throw new IllegalArgumentException("UPDATE_NODE cannot change node id from " + id + " to " + patchedId);
        }
        actualPatch.remove("id");
        Map<String, Object> merged = mutableMap(current);
        deepMerge(merged, actualPatch);
        merged.put("id", id);
        GraphSpec.Node updated = objectMapper.convertValue(merged, GraphSpec.Node.class);
        List<GraphSpec.Node> nodes = mutableNodes(graph);
        for (int index = 0; index < nodes.size(); index++) {
            if (id.equals(nodes.get(index).getId())) {
                nodes.set(index, updated);
                break;
            }
        }
        graph.setNodes(nodes);
        accumulator.changedNode(id);
    }

    private void deleteNode(GraphSpec graph,
                            String nodeId,
                            MutationAccumulator accumulator) {
        String id = requireText(nodeId, "DELETE_NODE requires nodeId");
        List<GraphSpec.Node> nodes = mutableNodes(graph);
        if (!nodes.removeIf(node -> node != null && id.equals(node.getId()))) {
            throw new IllegalArgumentException("graph node not found: " + id);
        }
        graph.setNodes(nodes);
        List<GraphSpec.Edge> edges = mutableEdges(graph);
        edges.removeIf(edge -> edge != null && (id.equals(edge.getFrom()) || id.equals(edge.getTo())));
        graph.setEdges(edges);
        if (id.equals(graph.getEntry())) {
            graph.setEntry(null);
        }
        if (graph.getFinish() != null) {
            graph.setFinish(graph.getFinish().stream().filter(item -> !id.equals(item)).toList());
        }
        accumulator.changedNode(id);
    }

    private void addEdge(GraphSpec graph,
                         GraphSpec.Edge edge,
                         MutationAccumulator accumulator) {
        if (edge == null || !StringUtils.hasText(edge.getFrom()) || !StringUtils.hasText(edge.getTo())) {
            throw new IllegalArgumentException("ADD_EDGE requires edge.from and edge.to");
        }
        GraphSpec.Edge candidate = deepCopy(edge, GraphSpec.Edge.class);
        candidate.setFrom(candidate.getFrom().trim());
        candidate.setTo(candidate.getTo().trim());
        assertEndpoint(graph, candidate.getFrom(), "ADD_EDGE source");
        assertEndpoint(graph, candidate.getTo(), "ADD_EDGE target");
        String id = edgeId(candidate);
        if (!StringUtils.hasText(id)) {
            throw new IllegalArgumentException("ADD_EDGE requires edge.id or edge.from/edge.to");
        }
        candidate.setId(id);
        if (findEdge(graph, id) != null) {
            throw new IllegalArgumentException("duplicate graph edge id: " + id);
        }
        List<GraphSpec.Edge> edges = mutableEdges(graph);
        edges.add(candidate);
        graph.setEdges(edges);
        accumulator.changedEdge(id);
    }

    private void updateEdge(GraphSpec graph,
                            String edgeId,
                            Map<String, Object> patch,
                            MutationAccumulator accumulator) {
        String id = requireText(edgeId, "UPDATE_EDGE requires edgeId");
        GraphSpec.Edge current = findEdge(graph, id);
        if (current == null) {
            throw new IllegalArgumentException("graph edge not found: " + id);
        }
        Map<String, Object> actualPatch = mutableMap(patch);
        alias(actualPatch, "source", "from");
        alias(actualPatch, "target", "to");
        String patchedId = text(actualPatch.get("id"));
        if (StringUtils.hasText(patchedId) && !id.equals(patchedId)) {
            throw new IllegalArgumentException("UPDATE_EDGE cannot change edge id from " + id + " to " + patchedId);
        }
        actualPatch.remove("id");
        Map<String, Object> merged = mutableMap(current);
        deepMerge(merged, actualPatch);
        merged.put("id", id);
        GraphSpec.Edge updated = objectMapper.convertValue(merged, GraphSpec.Edge.class);
        assertEndpoint(graph, updated.getFrom(), "UPDATE_EDGE source");
        assertEndpoint(graph, updated.getTo(), "UPDATE_EDGE target");
        List<GraphSpec.Edge> edges = mutableEdges(graph);
        for (int index = 0; index < edges.size(); index++) {
            if (id.equals(edgeId(edges.get(index)))) {
                edges.set(index, updated);
                break;
            }
        }
        graph.setEdges(edges);
        accumulator.changedEdge(id);
    }

    private void deleteEdge(GraphSpec graph,
                            String edgeId,
                            GraphSpec.Edge edge,
                            MutationAccumulator accumulator) {
        String id = StringUtils.hasText(edgeId) ? edgeId.trim() : edgeId(edge);
        if (!StringUtils.hasText(id)) {
            throw new IllegalArgumentException("DELETE_EDGE requires edgeId or edge");
        }
        List<GraphSpec.Edge> edges = mutableEdges(graph);
        if (!edges.removeIf(item -> id.equals(edgeId(item)))) {
            throw new IllegalArgumentException("graph edge not found: " + id);
        }
        graph.setEdges(edges);
        accumulator.changedEdge(id);
    }

    private void setEntry(GraphSpec graph,
                          String entry,
                          MutationAccumulator accumulator) {
        String nodeId = requireText(entry, "SET_ENTRY requires entry");
        if (findNode(graph, nodeId) == null) {
            throw new IllegalArgumentException("SET_ENTRY references missing node: " + nodeId);
        }
        graph.setEntry(nodeId);
        accumulator.changedNode(nodeId);
    }

    private void setFinish(GraphSpec graph,
                           List<String> finish,
                           MutationAccumulator accumulator) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String item : finish == null ? List.<String>of() : finish) {
            String nodeId = requireText(item, "SET_FINISH contains a blank node id");
            if (findNode(graph, nodeId) == null) {
                throw new IllegalArgumentException("SET_FINISH references missing node: " + nodeId);
            }
            normalized.add(nodeId);
            accumulator.changedNode(nodeId);
        }
        graph.setFinish(List.copyOf(normalized));
    }

    private void assertEndpoint(GraphSpec graph, String endpoint, String field) {
        String value = requireText(endpoint, field + " is required");
        if (!"START".equalsIgnoreCase(value)
                && !"END".equalsIgnoreCase(value)
                && findNode(graph, value) == null) {
            throw new IllegalArgumentException(field + " references missing node: " + value);
        }
    }

    private GraphSpec.Node findNode(GraphSpec graph, String nodeId) {
        return graph.getNodes() == null ? null : graph.getNodes().stream()
                .filter(node -> node != null && nodeId.equals(node.getId()))
                .findFirst()
                .orElse(null);
    }

    private GraphSpec.Edge findEdge(GraphSpec graph, String id) {
        return graph.getEdges() == null ? null : graph.getEdges().stream()
                .filter(edge -> edge != null && id.equals(edgeId(edge)))
                .findFirst()
                .orElse(null);
    }

    private List<GraphSpec.Node> mutableNodes(GraphSpec graph) {
        return new ArrayList<>(graph.getNodes() == null ? List.of() : graph.getNodes());
    }

    private List<GraphSpec.Edge> mutableEdges(GraphSpec graph) {
        return new ArrayList<>(graph.getEdges() == null ? List.of() : graph.getEdges());
    }

    private String edgeId(GraphSpec.Edge edge) {
        if (edge == null) {
            return null;
        }
        if (StringUtils.hasText(edge.getId())) {
            return edge.getId().trim();
        }
        if (StringUtils.hasText(edge.getFrom()) && StringUtils.hasText(edge.getTo())) {
            return edge.getFrom().trim() + "->" + edge.getTo().trim();
        }
        return null;
    }

    private void alias(Map<String, Object> map, String alias, String canonical) {
        if (!map.containsKey(canonical) && map.containsKey(alias)) {
            map.put(canonical, map.get(alias));
        }
        map.remove(alias);
    }

    private void deepMerge(Map<String, Object> target, Map<String, Object> patch) {
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            if (target.get(entry.getKey()) instanceof Map<?, ?> current
                    && entry.getValue() instanceof Map<?, ?> update) {
                Map<String, Object> merged = mutableMap(current);
                deepMerge(merged, mutableMap(update));
                target.put(entry.getKey(), merged);
            } else {
                target.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private Map<String, Object> mutableMap(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(value, MAP_TYPE);
    }

    private <T> T deepCopy(Object value, Class<T> type) {
        try {
            return objectMapper.treeToValue(objectMapper.valueToTree(value), type);
        } catch (Exception ex) {
            throw new IllegalArgumentException("workflow mutation payload is invalid: " + ex.getMessage(), ex);
        }
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record MutationOperation(Op op,
                                    GraphSpec.Node node,
                                    String nodeId,
                                    Map<String, Object> patch,
                                    GraphSpec.Edge edge,
                                    String edgeId,
                                    String entry,
                                    List<String> finish) {
        public enum Op {
            ADD_NODE,
            UPDATE_NODE,
            DELETE_NODE,
            ADD_EDGE,
            UPDATE_EDGE,
            DELETE_EDGE,
            SET_ENTRY,
            SET_FINISH
        }
    }

    public record MutationResult(GraphSpec graphSpec,
                                 int operationCount,
                                 List<String> changedNodes,
                                 List<String> changedEdges) {
        public String summary() {
            return operationCount + " operations";
        }
    }

    private static final class MutationAccumulator {
        private final LinkedHashSet<String> changedNodes = new LinkedHashSet<>();
        private final LinkedHashSet<String> changedEdges = new LinkedHashSet<>();

        private void changedNode(String nodeId) {
            if (StringUtils.hasText(nodeId)) {
                changedNodes.add(nodeId);
            }
        }

        private void changedEdge(String edgeId) {
            if (StringUtils.hasText(edgeId)) {
                changedEdges.add(edgeId);
            }
        }
    }
}
