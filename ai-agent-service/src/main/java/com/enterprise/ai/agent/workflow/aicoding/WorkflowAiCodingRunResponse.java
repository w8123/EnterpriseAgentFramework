package com.enterprise.ai.agent.workflow.aicoding;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowAiCodingRunResponse {

    private String status;

    private String answer;

    private String traceId;

    private String runId;

    private List<Map<String, Object>> nodeOutputs;

    private List<String> errors;

    private List<String> warnings;

    private Map<String, Object> metadata;
}
