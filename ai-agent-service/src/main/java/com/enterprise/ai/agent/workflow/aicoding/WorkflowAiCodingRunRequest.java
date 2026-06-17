package com.enterprise.ai.agent.workflow.aicoding;

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
public class WorkflowAiCodingRunRequest {

    private Map<String, Object> input;

    private String message;

    private Map<String, Object> runtimeContext;

    @Builder.Default
    private boolean dryRun = false;
}
