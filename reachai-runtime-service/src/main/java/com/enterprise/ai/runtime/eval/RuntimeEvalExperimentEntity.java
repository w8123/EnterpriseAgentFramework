package com.enterprise.ai.runtime.eval;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_eval_experiment")
public class RuntimeEvalExperimentEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String projectCode;
    private String targetType;
    private String targetId;
    private String name;
    private Long datasetVersionId;
    private Long evaluatorSuiteVersionId;
    private Integer repeatCount;
    private String status;
    private String idempotencyKey;
    private Integer variantCount;
    private Integer taskCount;
    private Integer completedTaskCount;
    private Integer failedTaskCount;
    private String gateStatus;
    private String gateConfigJson;
    private String summaryJson;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
