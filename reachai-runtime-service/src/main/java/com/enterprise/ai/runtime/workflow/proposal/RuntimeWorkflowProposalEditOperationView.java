package com.enterprise.ai.runtime.workflow.proposal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuntimeWorkflowProposalEditOperationView {

    private RuntimeWorkflowProposalEditOperationType type;

    private String nodeId;

    private String edgeId;

    private Map<String, Object> node;

    private Map<String, Object> edge;

    private Map<String, Object> patch;

    private String reason;
}
