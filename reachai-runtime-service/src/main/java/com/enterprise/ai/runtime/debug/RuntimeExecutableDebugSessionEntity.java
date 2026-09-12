package com.enterprise.ai.runtime.debug;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_executable_debug_session")
public class RuntimeExecutableDebugSessionEntity {

    @TableId
    private String id;

    private String runId;

    private String traceId;

    private String targetType;

    private String ownerTenantId;

    private String ownerUserId;

    private String status;

    private Integer revision;

    private String creationRequestHash;

    private String idempotencyKey;

    private String submittedPayloadJson;

    private String resultJson;

    private LocalDateTime executionDeadlineAt;

    private String currentNodeId;

    private String workingCopyDefinitionJson;

    private String debugOptionsJson;

    private String stateSnapshotJson;

    private String messagesJson;

    private String stepsJson;

    private String uiRequestJson;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private LocalDateTime expiresAt;
}
