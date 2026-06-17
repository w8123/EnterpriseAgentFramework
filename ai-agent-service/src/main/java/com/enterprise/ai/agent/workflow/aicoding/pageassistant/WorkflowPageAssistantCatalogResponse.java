package com.enterprise.ai.agent.workflow.aicoding.pageassistant;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowPageAssistantCatalogResponse {

    private String workflowId;
    private String workflowType;
    private Long projectId;
    private String projectCode;
    private String pageKey;
    private String routePattern;
    private List<PageActionNodeView> pageActionNodes;
    private List<CatalogActionView> catalogActions;
    private List<String> warnings;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PageActionNodeView {
        private String nodeId;
        private String nodeType;
        private String pageKey;
        private String actionKey;
        private Map<String, Object> args;
        private String outputAlias;
        private MatchStatus matchStatus;
        private String matchMessage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CatalogActionView {
        private String actionKey;
        private String title;
        private String description;
        private String status;
        private Boolean confirmRequired;
        private Map<String, Object> inputSchema;
        private Map<String, Object> outputSchema;
        private Map<String, Object> sampleArgs;
        private Map<String, Object> metadata;
    }

    public enum MatchStatus {
        MATCHED,
        MISSING,
        INACTIVE,
        PAGE_KEY_MISMATCH,
        ACTION_KEY_EMPTY,
        PAGE_KEY_EMPTY
    }
}
