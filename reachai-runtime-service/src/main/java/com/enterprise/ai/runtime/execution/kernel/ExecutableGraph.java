package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.agent.graph.GraphSpec;

import java.util.Map;
import java.util.Set;

/** Immutable, validated GraphSpec view consumed by the Runtime execution engine. */
public record ExecutableGraph(
        GraphSpec graph,
        Map<String, GraphSpec.Node> nodesById,
        Set<String> exitNodeIds,
        String declaredEntryNodeId,
        String executionEntryNodeId
) {

    public ExecutableGraph {
        nodesById = Map.copyOf(nodesById);
        exitNodeIds = Set.copyOf(exitNodeIds);
    }
}
