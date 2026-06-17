package com.enterprise.ai.agent.workflow.aicoding.pageassistant;

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
public class WorkflowPageAssistantSmokeTestRequest {

    /**
     * Defaults to true when omitted or null.
     */
    private Boolean dryRun;

    private Map<String, Object> runtimeContext;

    /**
     * Optional evidence from a real browser/bridge verification run.
     */
    private Map<String, Object> runtimeVerification;
}
