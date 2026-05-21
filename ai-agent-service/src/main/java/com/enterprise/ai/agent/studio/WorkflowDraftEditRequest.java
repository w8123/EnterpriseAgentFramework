package com.enterprise.ai.agent.studio;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Singular;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowDraftEditRequest {

    private String agentId;

    private String agentName;

    private String instruction;

    private String projectCode;

    private String modelInstanceId;

    private Map<String, Object> currentCanvas;

    @Singular
    private List<String> selectedNodeIds;

    @Singular
    private List<String> selectedEdgeIds;

    @Singular
    private List<WorkflowDraftResource> tools;

    @Singular
    private List<WorkflowDraftResource> capabilities;

    @Singular
    private List<WorkflowDraftResource> knowledgeBases;
}
