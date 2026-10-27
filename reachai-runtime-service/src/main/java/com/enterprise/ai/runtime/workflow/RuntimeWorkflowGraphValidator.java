package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.graph.GraphSpecLoopConfig;
import com.enterprise.ai.runtime.execution.context.WorkflowVariableNamespaces;
import com.enterprise.ai.runtime.execution.kernel.EdgeConditions;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.enterprise.ai.runtime.workflow.RuntimeWorkflowValidationSupport.firstText;
import static com.enterprise.ai.runtime.workflow.RuntimeWorkflowValidationSupport.text;

/** Validates graph boundaries, topology, LOOP ownership and input references. */
final class RuntimeWorkflowGraphValidator {

    private static final Set<String> ROUTE_EMITTING_NODE_TYPES = Set.of(
            "INTENT_CLASSIFIER", "IF_ELSE", "INTERACTION", "KNOWLEDGE_RETRIEVAL",
            "PAGE_ACTION", "HUMAN_APPROVAL", "LOOP");

    private static final Pattern NODE_OUTPUT_FIELD_REFERENCE = Pattern.compile(
            "nodeOutput\\.([A-Za-z0-9_-]+)\\.([A-Za-z0-9_]+)");

    /**
     * Nodes without branch routes only follow the engine's keyword conditions; any other text on their
     * outgoing edges would be silently skipped at run time, so it is rejected before publish.
     */
    private void validateEdgeConditions(Map<String, GraphSpec.Node> byId,
                                        List<GraphSpec.Edge> edges,
                                        RuntimeWorkflowReleaseValidationResult.Builder report) {
        for (GraphSpec.Edge edge : edges) {
            if (edge == null || !StringUtils.hasText(edge.getCondition())) {
                continue;
            }
            GraphSpec.Node source = byId.get(text(edge.getFrom()));
            if (source == null) {
                continue;
            }
            String type = AgentGraphNodeType.find(source.getType()).map(AgentGraphNodeType::type).orElse("");
            if (ROUTE_EMITTING_NODE_TYPES.contains(type) || EdgeConditions.isKeyword(edge.getCondition())) {
                continue;
            }
            report.error("GRAPH_EDGE_CONDITION_UNSUPPORTED", source.getId(),
                    "Edge condition is not supported for this node: " + edge.getCondition()
                            + "; use always, success, error, empty or not_empty");
        }
    }

    void validateLoopNode(GraphSpec.Node node,
                          Map<String, GraphSpec.Node> byId,
                          List<GraphSpec.Edge> edges,
                          RuntimeWorkflowReleaseValidationResult.Builder report) {
        GraphSpecLoopConfig loop = GraphSpecLoopConfig.from(node);
        String mode = loop.mode();
        if (!"FOREACH".equalsIgnoreCase(mode)) {
            report.error("GRAPH_LOOP_MODE_UNSUPPORTED", node.getId(),
                    "LOOP v1 only supports FOREACH mode");
        }
        String collection = loop.collection();
        if (!StringUtils.hasText(collection)) {
            report.error("GRAPH_LOOP_COLLECTION_REQUIRED", node.getId(),
                    "LOOP requires collection expression");
        }
        String itemAlias = loop.itemAlias();
        String indexAlias = loop.indexAlias();
        String outputAlias = loop.outputAlias();
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
        Integer maxIterations = loop.maxIterations();
        if (maxIterations == null) {
            report.error("GRAPH_LOOP_MAX_ITERATIONS_INVALID", node.getId(),
                    "LOOP maxIterations must be between 1 and "
                            + GraphSpecLoopConfig.HARD_MAX_ITERATIONS
                            + " (missing defaults to "
                            + GraphSpecLoopConfig.DEFAULT_MAX_ITERATIONS
                            + ")");
        }
        String bodyEntry = loop.bodyEntry();
        String bodyExit = loop.bodyExit();
        Set<String> bodyNodeIds = loop.bodyNodeIds();
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
                allBodyNodes.addAll(GraphSpecLoopConfig.from(node).bodyNodeIds());
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

    void validateLoopBodyDualOwnership(Map<String, GraphSpec.Node> byId,
                                       RuntimeWorkflowReleaseValidationResult.Builder report) {
        Map<String, List<String>> owners = new LinkedHashMap<>();
        for (GraphSpec.Node node : byId.values()) {
            if (!"LOOP".equals(AgentGraphNodeType.normalize(node.getType()))) {
                continue;
            }
            for (String bodyId : GraphSpecLoopConfig.from(node).bodyNodeIds()) {
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
            for (String bodyId : GraphSpecLoopConfig.from(node).bodyNodeIds()) {
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

    /**
     * USER_INPUT publishes the raw user text as {@code nodeOutput.<nodeId>}; its declared fields are
     * written to {@code params.<field>}. A field path under the node output silently resolves to null,
     * so fail the release (and AI authoring validation) with the corrected reference.
     */
    void validateUserInputFieldReferences(Map<String, GraphSpec.Node> nodesById,
                                          RuntimeWorkflowReleaseValidationResult.Builder report) {
        Set<String> userInputNodeIds = new LinkedHashSet<>();
        nodesById.forEach((id, node) -> {
            if ("USER_INPUT".equals(node.getType())) userInputNodeIds.add(id);
        });
        if (userInputNodeIds.isEmpty()) {
            return;
        }
        for (GraphSpec.Node consumer : nodesById.values()) {
            Set<String> reported = new LinkedHashSet<>();
            validateUserInputFieldReferences(consumer.getConfig(), consumer.getId(), userInputNodeIds, reported, report);
        }
    }

    private void validateUserInputFieldReferences(Object value,
                                          String consumerNodeId,
                                          Set<String> userInputNodeIds,
                                          Set<String> reported,
                                          RuntimeWorkflowReleaseValidationResult.Builder report) {
        if (value instanceof Map<?, ?> map) {
            for (Object nested : map.values()) {
                validateUserInputFieldReferences(nested, consumerNodeId, userInputNodeIds, reported, report);
            }
            return;
        }
        if (value instanceof Iterable<?> items) {
            for (Object nested : items) {
                validateUserInputFieldReferences(nested, consumerNodeId, userInputNodeIds, reported, report);
            }
            return;
        }
        if (!(value instanceof String expression) || !expression.contains("nodeOutput.")) {
            return;
        }
        Matcher matcher = NODE_OUTPUT_FIELD_REFERENCE.matcher(expression);
        while (matcher.find()) {
            String producerNodeId = matcher.group(1);
            String field = matcher.group(2);
            if (!userInputNodeIds.contains(producerNodeId) || !reported.add(matcher.group())) {
                continue;
            }
            report.error(
                    "GRAPH_USER_INPUT_FIELD_REFERENCE_INVALID",
                    consumerNodeId,
                    "USER_INPUT nodeOutput." + producerNodeId + " is the raw user text and has no field "
                            + field + ". Use params." + field + " instead of " + matcher.group() + ".");
        }
    }

    void validateBoundariesAndEdges(GraphSpec graph,
                                    Map<String, GraphSpec.Node> byId,
                                    List<GraphSpec.Edge> edges,
                                    RuntimeWorkflowReleaseValidationResult.Builder report) {
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

        validateEdgeConditions(byId, edges, report);
        if (entry != null && byId.containsKey(entry)) {
            validateGraphStructure(entry, byId, edges, report);
        }
    }
}
