package com.enterprise.ai.agent.workflow.aicoding.pageassistant;

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
public class WorkflowPageAssistantValidateRequest {

    private GraphSpec graphSpec;
}
