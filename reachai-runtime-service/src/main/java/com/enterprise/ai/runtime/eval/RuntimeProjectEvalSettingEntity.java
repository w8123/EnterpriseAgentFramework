package com.enterprise.ai.runtime.eval;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** One business system's automatic evaluation and optimization switches. */
@Data
@TableName("runtime_project_eval_setting")
public class RuntimeProjectEvalSettingEntity {
    @TableId(type = IdType.INPUT)
    private String projectCode;
    private String tenantId;
    /** The assistant Agent evaluated for this system. */
    private String agentId;
    /** The business-system account the evaluation runs as; only its read-only capabilities are used. */
    private String evalUserId;
    private Boolean enabled;
    /** Hour of day (0-23) of the daily run. */
    private Integer runHour;
    private Integer questionCount;
    private Long datasetId;
    private LocalDateTime lastGeneratedAt;
    private Long lastExperimentId;
    private LocalDateTime lastRunAt;
    /** Enable read-only flows learned from repeated requests without waiting for an admin. */
    private Boolean autoEnableReadonlyFlows;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
