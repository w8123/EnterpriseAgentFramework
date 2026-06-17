package com.enterprise.ai.agent.workflow.aicoding;

import com.enterprise.ai.agent.workflow.WorkflowReleaseValidationResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowAiCodingValidateResponse {

    private String workflowId;

    private ValidateMode mode;

    private WorkflowReleaseValidationResult validation;

    public enum ValidateMode {
        CURRENT,
        PROPOSED
    }
}
