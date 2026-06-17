package com.enterprise.ai.agent.workflow.aicoding;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.workflow.WorkflowReleaseValidationResult;
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
public class WorkflowAiCodingContextResponse {

    private WorkflowInfo workflow;

    private GraphSpec graphSpec;

    private Map<String, Object> canvas;

    private WorkflowReleaseValidationResult validation;

    private List<AgentGraphNodeType.Descriptor> nodeTypes;

    private RuntimeHints runtimeHints;

    private List<BindingSummary> bindings;

    private PageAssistantContext pageAssistantContext;

    private List<ModelOption> availableModels;

    private List<ToolOption> availableTools;

    private List<String> warnings;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkflowInfo {
        private String id;
        private String name;
        private String keySlug;
        private Long projectId;
        private String projectCode;
        private String workflowType;
        private String runtimeType;
        private String status;
        private String defaultModelInstanceId;
        private String updatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RuntimeHints {
        private String runtimeType;
        private boolean debugSupported;
        private boolean pageActionRequiresBridge;
        private List<String> notes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BindingSummary {
        private Long bindingId;
        private String agentId;
        private String bindingType;
        private String pageKey;
        private String routePattern;
        private String actionKey;
        private Boolean enabled;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PageAssistantContext {
        private String pageKey;
        private String routePattern;
        private List<String> actionKeys;
        private List<PageActionCatalogItem> pageActionCatalog;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelOption {
        private String id;
        private String name;
        private String provider;
        private String modelName;
        private String modelType;
        private String status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolOption {
        private String name;
        private String kind;
        private String title;
        private String description;
        private Boolean enabled;
        private String qualifiedName;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PageActionCatalogItem {
        private String actionKey;
        private String title;
        private String status;
    }
}
