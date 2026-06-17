package com.enterprise.ai.agent.workflow.aicoding;

import com.enterprise.ai.agent.runops.RunOpsService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowAiCodingRunDetailResponse {

    private String workflowId;

    private String traceId;

    private RunOpsService.RunDetail detail;

    private List<String> warnings;
}
