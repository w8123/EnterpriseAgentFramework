package com.enterprise.ai.agent.workflow.aicoding;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowGraphPatchOperation {

    private OperationOp op;

    private GraphSpec.Node node;

    private String nodeId;

    private Map<String, Object> patch;

    private GraphSpec.Edge edge;

    private String edgeId;

    private String entry;

    public enum OperationOp {
        ADD_NODE,
        UPDATE_NODE,
        DELETE_NODE,
        ADD_EDGE,
        DELETE_EDGE,
        SET_ENTRY
    }
}
