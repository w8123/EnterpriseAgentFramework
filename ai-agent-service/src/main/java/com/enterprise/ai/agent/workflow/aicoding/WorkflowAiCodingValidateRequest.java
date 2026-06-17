package com.enterprise.ai.agent.workflow.aicoding;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowAiCodingValidateRequest {

    private GraphSpec graphSpec;

    @Builder.Default
    private ValidateMode mode = ValidateMode.CURRENT;

    public enum ValidateMode {
        CURRENT,
        PROPOSED
    }
}
