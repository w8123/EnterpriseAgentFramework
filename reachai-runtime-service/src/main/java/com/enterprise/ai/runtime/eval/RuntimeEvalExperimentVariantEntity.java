package com.enterprise.ai.runtime.eval;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_eval_experiment_variant")
public class RuntimeEvalExperimentVariantEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long experimentId;
    private String variantKey;
    private String displayName;
    private String variantRole;
    private Long targetSnapshotId;
    private Long targetConfigVersionId;
    private String targetConfigStatus;
    private String targetFingerprint;
    private String status;
    private String summaryJson;
    private LocalDateTime createdAt;
}
