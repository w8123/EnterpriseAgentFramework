package com.enterprise.ai.runtime.workflow.proposal;

import java.util.Map;

public record RuntimeWorkflowProposalResourceView(
        String kind,
        String name,
        String qualifiedName,
        Long definitionId,
        String projectCode,
        String description,
        Map<String, Object> metadata) {
}
