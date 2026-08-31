package com.enterprise.ai.runtime.eval;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_eval_target_snapshot")
public class RuntimeEvalTargetSnapshotEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String projectCode;
    private String targetType;
    private String targetId;
    private String targetVersionRef;
    private Long agentConfigVersionId;
    private String sourceStatus;
    private Integer snapshotSchemaVersion;
    private String fingerprintSha256;
    private String snapshotJson;
    private String createdBy;
    private LocalDateTime createdAt;
}
