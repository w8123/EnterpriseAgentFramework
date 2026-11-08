package com.enterprise.ai.agent.graph;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Static fork/join interpretation of a PARALLEL node, shared by release validation and the Runtime handler so
 * both sides agree on which nodes belong to which branch and where the branches meet again.
 *
 * <p>Every outgoing edge of the PARALLEL node starts one branch. The join is the earliest node reachable from
 * every branch entry; a branch is every node reachable from its entry before the join. Branches must not share
 * nodes, must not escape to nodes outside their own set other than the join, and must not end before the join.</p>
 */
public record GraphSpecParallelPlan(
        String parallelNodeId,
        List<String> branchEntries,
        String joinNodeId,
        List<Set<String>> branchNodeIds,
        int maxConcurrency,
        String failureMode,
        List<Problem> problems) {

    public static final int MIN_BRANCHES = 2;
    public static final int MAX_BRANCHES = 8;
    public static final int DEFAULT_MAX_CONCURRENCY = 4;
    public static final int HARD_MAX_CONCURRENCY = 4;
    /** Any failed branch fails the PARALLEL node (then the node's own error policy applies). */
    public static final String FAIL_FAST = "FAIL_FAST";
    /** Failed branches are recorded; the join still runs with the successful outputs. */
    public static final String CONTINUE = "CONTINUE";

    public GraphSpecParallelPlan {
        branchEntries = List.copyOf(branchEntries);
        branchNodeIds = branchNodeIds.stream()
                .map(ids -> Collections.unmodifiableSet(new LinkedHashSet<>(ids)))
                .toList();
        problems = List.copyOf(problems);
    }

    public boolean valid() {
        return problems.isEmpty();
    }

    public Set<String> allBranchNodeIds() {
        Set<String> all = new LinkedHashSet<>();
        branchNodeIds.forEach(all::addAll);
        return all;
    }

    public record Problem(String code, String message) {
    }

    public static GraphSpecParallelPlan from(GraphSpec.Node node,
                                             Map<String, GraphSpec.Node> nodesById,
                                             List<GraphSpec.Edge> edges) {
        List<Problem> problems = new ArrayList<>();
        String nodeId = node == null ? null : text(node.getId());
        Map<String, Object> config = effectiveConfig(node == null ? null : node.getConfig());
        Integer concurrency = parseConcurrency(config.get("maxConcurrency"));
        if (concurrency == null) {
            problems.add(new Problem("GRAPH_PARALLEL_CONCURRENCY_INVALID",
                    "PARALLEL maxConcurrency must be between 1 and " + HARD_MAX_CONCURRENCY));
            concurrency = DEFAULT_MAX_CONCURRENCY;
        }
        String failureMode = text(config.get("failureMode"));
        failureMode = failureMode == null ? FAIL_FAST : failureMode.toUpperCase(Locale.ROOT);
        if (!FAIL_FAST.equals(failureMode) && !CONTINUE.equals(failureMode)) {
            problems.add(new Problem("GRAPH_PARALLEL_FAILURE_MODE_INVALID",
                    "PARALLEL failureMode must be FAIL_FAST or CONTINUE"));
            failureMode = FAIL_FAST;
        }
        List<GraphSpec.Edge> safeEdges = edges == null ? List.of() : edges;
        Map<String, List<String>> adjacency = adjacency(nodesById, safeEdges);
        List<String> entries = new ArrayList<>();
        safeEdges.stream()
                .filter(edge -> edge != null && nodeId != null && nodeId.equals(text(edge.getFrom())))
                .sorted(Comparator.comparing(edge -> edge.getPriority() == null ? Integer.MAX_VALUE : edge.getPriority()))
                .forEach(edge -> {
                    String condition = text(edge.getCondition());
                    if (condition != null && !Set.of("always", "success").contains(condition.toLowerCase(Locale.ROOT))) {
                        problems.add(new Problem("GRAPH_PARALLEL_EDGE_CONDITION_INVALID",
                                "PARALLEL outgoing edges must be unconditional: " + condition));
                    }
                    String to = text(edge.getTo());
                    if (to != null && nodesById.containsKey(to) && !entries.contains(to)) entries.add(to);
                });
        if (entries.size() < MIN_BRANCHES || entries.size() > MAX_BRANCHES) {
            problems.add(new Problem("GRAPH_PARALLEL_BRANCH_COUNT_INVALID",
                    "PARALLEL needs between " + MIN_BRANCHES + " and " + MAX_BRANCHES
                            + " outgoing branches, found " + entries.size()));
            return new GraphSpecParallelPlan(nodeId, entries, null, List.of(), concurrency, failureMode, problems);
        }

        List<Set<String>> reach = new ArrayList<>();
        for (String entry : entries) reach.add(reachable(entry, adjacency));
        Set<String> common = new LinkedHashSet<>(reach.get(0));
        for (Set<String> other : reach) common.retainAll(other);
        common.remove(nodeId);
        // Earliest common node: not reachable from any other common node.
        List<String> earliest = new ArrayList<>();
        for (String candidate : common) {
            boolean dominatedByOther = false;
            for (String other : common) {
                if (!other.equals(candidate) && reachable(other, adjacency).contains(candidate)) {
                    dominatedByOther = true;
                    break;
                }
            }
            if (!dominatedByOther) earliest.add(candidate);
        }
        if (earliest.size() != 1) {
            problems.add(new Problem(earliest.isEmpty() ? "GRAPH_PARALLEL_JOIN_MISSING" : "GRAPH_PARALLEL_JOIN_AMBIGUOUS",
                    earliest.isEmpty()
                            ? "PARALLEL branches must meet again at one shared downstream node"
                            : "PARALLEL branches meet at more than one node: " + earliest));
            return new GraphSpecParallelPlan(nodeId, entries, null, List.of(), concurrency, failureMode, problems);
        }
        String join = earliest.get(0);
        Set<String> afterJoin = reachable(join, adjacency);
        List<Set<String>> branches = new ArrayList<>();
        Map<String, Integer> owner = new LinkedHashMap<>();
        for (int index = 0; index < entries.size(); index++) {
            Set<String> branch = new LinkedHashSet<>(reach.get(index));
            branch.removeAll(afterJoin);
            branch.remove(nodeId);
            for (String member : branch) {
                Integer previous = owner.putIfAbsent(member, index);
                if (previous != null && previous != index) {
                    problems.add(new Problem("GRAPH_PARALLEL_BRANCH_SHARED",
                            "PARALLEL branches must not share nodes before the join: " + member));
                }
            }
            branches.add(branch);
        }
        for (int index = 0; index < branches.size(); index++) {
            Set<String> branch = branches.get(index);
            for (String member : branch) {
                List<String> next = adjacency.getOrDefault(member, List.of());
                if (next.isEmpty()) {
                    problems.add(new Problem("GRAPH_PARALLEL_BRANCH_DEAD_END",
                            "PARALLEL branch node must lead to the join: " + member));
                }
                for (String target : next) {
                    if (!target.equals(join) && !branch.contains(target)) {
                        problems.add(new Problem("GRAPH_PARALLEL_BRANCH_ESCAPE",
                                "PARALLEL branch must only lead to its own nodes or the join: "
                                        + member + " -> " + target));
                    }
                }
            }
        }
        return new GraphSpecParallelPlan(nodeId, entries, join, branches, concurrency, failureMode, problems);
    }

    public static Integer parseConcurrency(Object raw) {
        if (raw == null || (raw instanceof String text && text.isBlank())) return DEFAULT_MAX_CONCURRENCY;
        int value;
        try {
            value = raw instanceof Number number
                    ? new BigDecimal(number.toString()).intValueExact()
                    : Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException | ArithmeticException ex) {
            return null;
        }
        return value < 1 || value > HARD_MAX_CONCURRENCY ? null : value;
    }

    private static Map<String, List<String>> adjacency(Map<String, GraphSpec.Node> nodesById,
                                                       List<GraphSpec.Edge> edges) {
        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        // LOOP bodies are driven by their LOOP node; treat a LOOP as one atomic step here.
        Set<String> loopBodies = new LinkedHashSet<>();
        for (GraphSpec.Node node : nodesById.values()) {
            if (node != null && "LOOP".equals(AgentGraphNodeType.normalize(node.getType()))) {
                loopBodies.addAll(GraphSpecLoopConfig.from(node).bodyNodeIds());
            }
        }
        for (GraphSpec.Edge edge : edges) {
            if (edge == null) continue;
            String from = text(edge.getFrom());
            String to = text(edge.getTo());
            if (from == null || to == null || !nodesById.containsKey(from) || !nodesById.containsKey(to)) continue;
            if (loopBodies.contains(from) || loopBodies.contains(to)) continue;
            List<String> targets = adjacency.computeIfAbsent(from, key -> new ArrayList<>());
            if (!targets.contains(to)) targets.add(to);
        }
        for (GraphSpec.Node node : nodesById.values()) {
            if (node == null || loopBodies.contains(text(node.getId()))) continue;
            GraphSpec.ErrorPolicy policy = node.getErrorPolicy();
            if (policy != null && "FALLBACK".equalsIgnoreCase(text(policy.getStrategy()))) {
                String fallback = text(policy.getFallbackNodeId());
                if (fallback != null && nodesById.containsKey(fallback)) {
                    List<String> targets = adjacency.computeIfAbsent(node.getId().trim(), key -> new ArrayList<>());
                    if (!targets.contains(fallback)) targets.add(fallback);
                }
            }
        }
        return adjacency;
    }

    private static Set<String> reachable(String start, Map<String, List<String>> adjacency) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!seen.add(current)) continue;
            for (String next : adjacency.getOrDefault(current, List.of())) {
                if (!seen.contains(next)) queue.addLast(next);
            }
        }
        return seen;
    }

    private static Map<String, Object> effectiveConfig(Map<String, Object> config) {
        if (config == null) return Map.of();
        if (!(config.get("parallelConfig") instanceof Map<?, ?> nested) || nested.isEmpty()) return config;
        Map<String, Object> merged = new LinkedHashMap<>();
        nested.forEach((key, value) -> merged.put(String.valueOf(key), value));
        config.forEach((key, value) -> {
            if (!"parallelConfig".equals(key)) merged.putIfAbsent(key, value);
        });
        return merged;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
