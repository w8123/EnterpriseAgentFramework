package com.enterprise.ai.runtime.workflow;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/** Immutable execution data exported by its owning module. */
@Value
@Builder
public class RuntimeWorkflowPublishedVersionView {
    Long id;
    String workflowId;
    String version;
    String snapshotJson;
    String graphSpecSnapshotJson;
    String status;
    LocalDateTime publishedAt;

    public static RuntimeWorkflowPublishedVersionView fromEntity(RuntimeWorkflowVersionEntity entity) {
        if (entity == null) return null;
        return RuntimeWorkflowPublishedVersionView.builder()
                .id(entity.getId())
                .workflowId(entity.getWorkflowId())
                .version(entity.getVersion())
                .snapshotJson(entity.getSnapshotJson())
                .graphSpecSnapshotJson(entity.getGraphSpecSnapshotJson())
                .status(entity.getStatus())
                .publishedAt(entity.getPublishedAt())
                .build();
    }
}
