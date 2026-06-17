package com.enterprise.ai.agent.workflow.aicoding.pageassistant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowPageAssistantValidateResponse {

    private String workflowId;
    private String workflowType;
    private String pageKey;
    private boolean valid;
    private List<Item> items;
    private List<String> warnings;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private String nodeId;
        private WorkflowPageAssistantCatalogResponse.MatchStatus matchStatus;
        private String actionKey;
        private String pageKey;
        private List<Finding> errors;
        private List<Finding> warnings;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Finding {
        private String code;
        private String level;
        private String field;
        private String message;
    }
}
