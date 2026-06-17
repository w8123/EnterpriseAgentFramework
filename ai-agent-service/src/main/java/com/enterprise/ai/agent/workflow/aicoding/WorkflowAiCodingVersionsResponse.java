package com.enterprise.ai.agent.workflow.aicoding;

import com.enterprise.ai.agent.workflow.WorkflowReleaseValidationResult;
import com.enterprise.ai.agent.workflow.WorkflowVersionEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowAiCodingVersionsResponse {

    private String workflowId;

    private String currentStatus;

    private PublishedVersionView publishedVersion;

    private List<WorkflowVersionEntity> versions;

    private WorkflowReleaseValidationResult releaseValidation;

    private boolean draftDirty;

    private List<String> warnings;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PublishedVersionView {
        private Long id;
        private String version;
        private String status;
        private Integer rolloutPercent;
        private String publishedBy;
        private String publishedAt;
    }

    public static PublishedVersionView fromEntity(WorkflowVersionEntity entity) {
        if (entity == null) {
            return null;
        }
        return PublishedVersionView.builder()
                .id(entity.getId())
                .version(entity.getVersion())
                .status(entity.getStatus())
                .rolloutPercent(entity.getRolloutPercent())
                .publishedBy(entity.getPublishedBy())
                .publishedAt(entity.getPublishedAt() == null ? null : entity.getPublishedAt().toString())
                .build();
    }
}
