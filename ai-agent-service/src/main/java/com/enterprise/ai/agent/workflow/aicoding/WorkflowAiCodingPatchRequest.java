package com.enterprise.ai.agent.workflow.aicoding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowAiCodingPatchRequest {

    private String baseRevision;

    /**
     * When omitted or null, defaults to true (preview only, no save).
     */
    private Boolean dryRun;

    private List<WorkflowGraphPatchOperation> operations;

    private LayoutOptions layout;

    private String reason;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LayoutOptions {
        @Builder.Default
        private boolean autoLayout = true;
    }
}
