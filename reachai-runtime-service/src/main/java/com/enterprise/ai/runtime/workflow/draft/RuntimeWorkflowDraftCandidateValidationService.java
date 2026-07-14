package com.enterprise.ai.runtime.workflow.draft;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationResult;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Reuses release validation for in-Studio AI proposals before they can be applied. */
@Service
@RequiredArgsConstructor
public class RuntimeWorkflowDraftCandidateValidationService {

    private final RuntimeWorkflowDefinitionService workflowService;
    private final RuntimeWorkflowReleaseValidationService validationService;

    public RuntimeWorkflowReleaseValidationResult validate(String workflowId,
                                                           String projectCode,
                                                           String workflowType,
                                                           String modelInstanceId,
                                                           GraphSpec graphSpec) {
        RuntimeWorkflowDefinitionEntity workflow = StringUtils.hasText(workflowId)
                ? workflowService.findById(workflowId.trim()).orElseGet(RuntimeWorkflowDefinitionEntity::new)
                : new RuntimeWorkflowDefinitionEntity();
        if (!StringUtils.hasText(workflow.getId())) {
            workflow.setId(StringUtils.hasText(workflowId) ? workflowId.trim() : "workflow-authoring-proposal");
            workflow.setProjectCode(projectCode);
            workflow.setWorkflowType(StringUtils.hasText(workflowType) ? workflowType.trim() : "WORKFLOW");
            workflow.setRuntimeType("LANGGRAPH4J");
            workflow.setDefaultModelInstanceId(modelInstanceId);
        }
        return validationService.validateProposed(workflow, graphSpec);
    }
}
