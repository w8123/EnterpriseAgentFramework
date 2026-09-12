package com.enterprise.ai.runtime.workflow;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/** Management release history, separate from the execution-only published version projection. */
@Value
@Builder
public class RuntimeWorkflowVersionView {
    Long id;
    String workflowId;
    String version;
    String snapshotJson;
    String graphSpecSnapshotJson;
    String canvasSnapshotJson;
    Integer rolloutPercent;
    String status;
    String publishedBy;
    LocalDateTime publishedAt;
    String note;
    LocalDateTime createdAt;

    static RuntimeWorkflowVersionView fromEntity(RuntimeWorkflowVersionEntity entity) {
        return builder().id(entity.getId()).workflowId(entity.getWorkflowId()).version(entity.getVersion())
                .snapshotJson(entity.getSnapshotJson()).graphSpecSnapshotJson(entity.getGraphSpecSnapshotJson())
                .canvasSnapshotJson(entity.getCanvasSnapshotJson()).rolloutPercent(entity.getRolloutPercent())
                .status(entity.getStatus()).publishedBy(entity.getPublishedBy()).publishedAt(entity.getPublishedAt())
                .note(entity.getNote()).createdAt(entity.getCreatedAt()).build();
    }
}
