package com.enterprise.ai.runtime.workflow.proposal;

import java.util.List;

public record RuntimeWorkflowProposalGenerationRequest(
        String workflowId,
        String workflowName,
        String requirement,
        String projectCode,
        String modelInstanceId,
        String workflowKind,
        List<RuntimeWorkflowProposalResourceView> tools,
        List<RuntimeWorkflowProposalResourceView> capabilities,
        List<RuntimeWorkflowProposalResourceView> knowledgeBases,
        List<RuntimeWorkflowProposalResourceView> pageActions) {
}
