package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.graph.GraphSpecLoopConfig;
import com.enterprise.ai.agent.graph.GraphSpecParallelPlan;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Determines whether a timed-out container could have dispatched a side effect. */
final class RuntimeNodeTimeoutSafety {
    private static final Set<String> READ_ONLY_TYPES = Set.of(
            "USER_INPUT", "IF_ELSE", "VARIABLE_ASSIGN", "TEMPLATE", "ANSWER", "CODE", "LLM",
            "INTENT_CLASSIFIER", "VARIABLE_AGGREGATOR", "LIST_OPERATOR", "PARAMETER_EXTRACT",
            "KNOWLEDGE_RETRIEVAL");

    private RuntimeNodeTimeoutSafety() {
    }

    static boolean containerMayWrite(GraphSpec.Node node, GraphSpec graph) {
        String type = AgentGraphNodeType.normalize(node.getType());
        if (!"LOOP".equals(type) && !"PARALLEL".equals(type)) return false;
        if (graph == null || graph.getNodes() == null) return true;
        Map<String, GraphSpec.Node> nodes = new HashMap<>();
        for (GraphSpec.Node candidate : graph.getNodes()) {
            if (candidate != null && candidate.getId() != null) nodes.put(candidate.getId().trim(), candidate);
        }
        return mayWrite(node, graph, nodes, new HashSet<>());
    }

    private static boolean mayWrite(GraphSpec.Node node, GraphSpec graph,
                                    Map<String, GraphSpec.Node> nodes, Set<String> visiting) {
        String type = AgentGraphNodeType.normalize(node.getType());
        if (READ_ONLY_TYPES.contains(type)) return false;
        if (!"LOOP".equals(type) && !"PARALLEL".equals(type)) return true;
        if (node.getId() == null || !visiting.add(node.getId())) return true;
        Set<String> members;
        if ("LOOP".equals(type)) {
            members = GraphSpecLoopConfig.from(node).bodyNodeIds();
        } else {
            GraphSpecParallelPlan plan = GraphSpecParallelPlan.from(node, nodes,
                    graph.getEdges() == null ? List.of() : graph.getEdges());
            if (!plan.valid()) return true;
            members = plan.allBranchNodeIds();
        }
        if (members.isEmpty()) return true;
        for (String member : members) {
            GraphSpec.Node child = nodes.get(member);
            if (child == null || mayWrite(child, graph, nodes, visiting)) return true;
        }
        visiting.remove(node.getId());
        return false;
    }
}
