package com.enterprise.ai.runtime.execution;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_interaction_session")
public class RuntimeInteractionSessionEntity {

    @TableId
    private String id;

    private String sourceType;

    private String agentId;

    private String runId;

    private String traceId;

    private String workflowId;

    private Long workflowVersionId;

    private String compositionQualifiedName;

    private String graphSpecSnapshotJson;

    private String nodeId;

    private String interactionType;

    private String status;

    private Integer revision;

    private String idempotencyKey;

    private LocalDateTime resumeDeadlineAt;

    private String resumeCheckpointJson;

    private Integer checkpointSchemaVersion;

    private String executionEngineVersion;

    private String checkpointDigest;

    private Integer checkpointSizeBytes;

    private String uiRequestJson;

    private String submittedPayloadJson;

    private String resultJson;

    private String continuationJson;

    private String appId;

    private String tenantId;

    private String sessionId;

    private String userId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private LocalDateTime expiresAt;
}
