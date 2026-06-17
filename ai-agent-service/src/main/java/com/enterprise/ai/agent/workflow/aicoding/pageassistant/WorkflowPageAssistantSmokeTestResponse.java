package com.enterprise.ai.agent.workflow.aicoding.pageassistant;

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
public class WorkflowPageAssistantSmokeTestResponse {

    private String status;
    private boolean dryRun;
    private boolean bridgeContextPresent;
    private String runtimeVerificationStatus;
    private List<NodeResult> nodes;
    private List<String> warnings;
    private List<String> errors;
    private Map<String, Object> metadata;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NodeResult {
        private String nodeId;
        private String actionKey;
        private String status;
        private WorkflowPageAssistantCatalogResponse.MatchStatus matchStatus;
        private String message;
    }

    public enum NodeStatus {
        SKIPPED,
        DRY_RUN,
        NEED_CONFIRM,
        READY_TO_QUEUE,
        QUEUED,
        RUNTIME_PASS,
        INVALID
    }
}
