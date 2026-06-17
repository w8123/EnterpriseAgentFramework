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
public class WorkflowAiCodingRunListResponse {

    private String workflowId;

    private List<RunOpsService.RunSummary> runs;

    private List<String> warnings;
}
