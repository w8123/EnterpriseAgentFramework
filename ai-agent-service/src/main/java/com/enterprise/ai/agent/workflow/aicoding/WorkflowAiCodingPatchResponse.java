package com.enterprise.ai.agent.workflow.aicoding;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionEntity;
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
public class WorkflowAiCodingPatchResponse {

    private boolean dryRun;

    private boolean saved;

    private String patchSummary;

    private List<String> changedNodes;

    private List<String> changedEdges;

    private GraphSpec proposedGraphSpec;

    private Map<String, Object> proposedCanvas;

    private WorkflowReleaseValidationResult validation;

    private WorkflowSnapshot workflow;

    private List<String> warnings;

    private List<String> errors;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkflowSnapshot {
        private String id;
        private String name;
        private String keySlug;
        private String status;
        private String graphSpecJson;
        private String canvasJson;
        private String updatedAt;

        public static WorkflowSnapshot from(WorkflowDefinitionEntity entity) {
            if (entity == null) {
                return null;
            }
            return WorkflowSnapshot.builder()
                    .id(entity.getId())
                    .name(entity.getName())
                    .keySlug(entity.getKeySlug())
                    .status(entity.getStatus())
                    .graphSpecJson(entity.getGraphSpecJson())
                    .canvasJson(entity.getCanvasJson())
                    .updatedAt(entity.getUpdatedAt() == null ? null : entity.getUpdatedAt().toString())
                    .build();
        }
    }
}
