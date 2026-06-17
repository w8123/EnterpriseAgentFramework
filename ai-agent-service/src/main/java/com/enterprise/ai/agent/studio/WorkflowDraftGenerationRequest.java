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
public class WorkflowDraftGenerationRequest {

    private String agentId;

    private String agentName;

    private String requirement;

    private String projectCode;

    private String modelInstanceId;

    private String draftScenario;

    private Map<String, Object> currentCanvas;

    @Singular
    private List<WorkflowDraftResource> tools;

    @Singular
    private List<WorkflowDraftResource> capabilities;

    @Singular
    private List<WorkflowDraftResource> knowledgeBases;

    @Singular
    private List<WorkflowDraftResource> pageActions;
}
