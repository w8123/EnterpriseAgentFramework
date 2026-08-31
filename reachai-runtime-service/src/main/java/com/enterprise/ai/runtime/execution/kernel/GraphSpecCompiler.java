package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Parses and performs the Runtime's defense-in-depth validation for GraphSpec schema version 2. */
public final class GraphSpecCompiler {

    private final ObjectMapper objectMapper;

    public GraphSpecCompiler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ExecutableGraph compile(String graphSpecJson, String entryOverride) {
        GraphSpec graph;
        try {
            graph = objectMapper.readValue(graphSpecJson, GraphSpec.class);
        } catch (Exception ex) {
            throw failure("RUNTIME_WORKFLOW_GRAPH_INVALID",
                    "Workflow GraphSpec JSON is invalid: " + ex.getMessage(), null, null);
        }
        if (graph == null || graph.getNodes() == null || graph.getNodes().isEmpty()) {
            throw failure("RUNTIME_GRAPH_NODE_EMPTY", "GraphSpec requires at least one node", null, null);
        }
        if (!Integer.valueOf(2).equals(graph.getSchemaVersion())) {
            throw failure("RUNTIME_GRAPH_SCHEMA_VERSION_INVALID", "GraphSpec schemaVersion must be 2", null, null);
        }
        Map<String, GraphSpec.Node> nodesById = new LinkedHashMap<>();
        for (GraphSpec.Node node : graph.getNodes()) {
            String nodeId = node == null ? null : text(node.getId());
            if (!StringUtils.hasText(nodeId)) {
                throw failure("RUNTIME_GRAPH_NODE_ID_INVALID",
                        "GraphSpec node.id must not be blank", null,
                        node == null ? null : node.getType());
            }
            if (nodesById.putIfAbsent(nodeId, node) != null) {
                throw failure("RUNTIME_GRAPH_NODE_ID_DUPLICATE",
                        "GraphSpec node.id must be unique: " + nodeId, nodeId, node.getType());
            }

            AgentGraphNodeType type = AgentGraphNodeType.find(node.getType()).orElse(null);
            if (type == null || !type.type().equals(node.getType())) {
                throw failure("RUNTIME_GRAPH_NODE_TYPE_INVALID",
                        "GraphSpec node.type must use a canonical value: " + node.getType(),
                        nodeId, node.getType());
            }
            if (type == AgentGraphNodeType.TOOL
                    && node.getRef() != null
                    && StringUtils.hasText(node.getRef().getKind())
                    && !"TOOL".equals(node.getRef().getKind())) {
                throw failure("RUNTIME_GRAPH_TOOL_REF_KIND_INVALID",
                        "GraphSpec TOOL node ref.kind must be TOOL", node.getId(), node.getType());
            }
        }

        String declaredEntry = text(graph.getEntryNodeId());
        if (!StringUtils.hasText(declaredEntry)) {
            throw failure("RUNTIME_GRAPH_ENTRY_MISSING", "GraphSpec entry is required", null, null);
        }

        if (!nodesById.containsKey(declaredEntry)) {
            throw failure("RUNTIME_GRAPH_ENTRY_INVALID",
                    "GraphSpec entry node does not exist: " + declaredEntry, declaredEntry, null);
        }

        Set<String> exitNodeIds = new LinkedHashSet<>();
        for (String exitNodeId : graph.getExitNodeIds()) {
            String exit = text(exitNodeId);
            if (!StringUtils.hasText(exit) || !nodesById.containsKey(exit)) {
                throw failure("RUNTIME_GRAPH_EXIT_INVALID",
                        "GraphSpec exit node does not exist: " + exit, exit, null);
            }
            exitNodeIds.add(exit);
        }
        if (exitNodeIds.isEmpty()) {
            throw failure("RUNTIME_GRAPH_EXIT_MISSING", "GraphSpec requires at least one exitNodeId", null, null);
        }

        for (GraphSpec.Edge edge : graph.getEdges() == null ? List.<GraphSpec.Edge>of() : graph.getEdges()) {
            if (edge == null
                    || !nodesById.containsKey(text(edge.getFrom()))
                    || !nodesById.containsKey(text(edge.getTo()))) {
                throw failure("RUNTIME_GRAPH_EDGE_INVALID",
                        "GraphSpec edges must connect real nodes; use entryNodeId/exitNodeIds for boundaries",
                        null, null);
            }
        }

        String entry = StringUtils.hasText(text(entryOverride)) ? text(entryOverride) : declaredEntry;
        if (!nodesById.containsKey(entry)) {
            throw failure("RUNTIME_GRAPH_ENTRY_INVALID",
                    "GraphSpec entry node does not exist: " + entry, entry, null);
        }
        return new ExecutableGraph(graph, nodesById, exitNodeIds, declaredEntry, entry);
    }

    private static GraphSpecCompilationException failure(String code,
                                                         String message,
                                                         String nodeId,
                                                         String nodeType) {
        return new GraphSpecCompilationException(code, message, nodeId, nodeType);
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String result = String.valueOf(value).trim();
        return result.isEmpty() ? null : result;
    }
}
