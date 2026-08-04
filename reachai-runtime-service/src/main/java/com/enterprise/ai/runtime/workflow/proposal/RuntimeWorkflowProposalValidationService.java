package com.enterprise.ai.runtime.workflow.proposal;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationResult;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationService;
import com.enterprise.ai.runtime.workflow.WorkflowSemanticValues;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Reuses release validation for in-Studio AI proposals before they can be applied. */
@Service
@RequiredArgsConstructor
public class RuntimeWorkflowProposalValidationService {

    private final RuntimeWorkflowDefinitionService workflowService;
    private final RuntimeWorkflowReleaseValidationService validationService;

    public RuntimeWorkflowReleaseValidationResult validate(String workflowId,
                                                           String projectCode,
                                                           String workflowKind,
                                                           String modelInstanceId,
                                                           GraphSpec graphSpec) {
        RuntimeWorkflowDefinitionEntity workflow = StringUtils.hasText(workflowId)
                ? workflowService.findById(workflowId.trim()).orElseGet(RuntimeWorkflowDefinitionEntity::new)
                : new RuntimeWorkflowDefinitionEntity();
        if (!StringUtils.hasText(workflow.getId())) {
            workflow.setId(StringUtils.hasText(workflowId) ? workflowId.trim() : "workflow-authoring-proposal");
            workflow.setProjectCode(projectCode);
            workflow.setWorkflowKind(WorkflowSemanticValues.normalizeWorkflowKind(
                    StringUtils.hasText(workflowKind) ? workflowKind.trim() : WorkflowSemanticValues.KIND_GENERAL));
            workflow.setExecutionEngine(WorkflowSemanticValues.ENGINE_GRAPH_SPEC);
            workflow.setDefinitionAuthority(WorkflowSemanticValues.AUTHORITY_USER);
            workflow.setCreationChannel(WorkflowSemanticValues.CHANNEL_AI_CODING);
            workflow.setDefaultModelInstanceId(modelInstanceId);
        }
        return validationService.validateProposed(workflow, graphSpec);
    }
}
