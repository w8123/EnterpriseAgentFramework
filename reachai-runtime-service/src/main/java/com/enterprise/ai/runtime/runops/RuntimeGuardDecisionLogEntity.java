package com.enterprise.ai.runtime.runops;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_guard_decision_log")
public class RuntimeGuardDecisionLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String traceId;
    private Long projectId;
    private String projectCode;
    private String environment;
    private String tenantId;
    private String decisionType;
    private String targetKind;
    private String targetName;
    private String decision;
    private String reason;
    private String metadataJson;
    private LocalDateTime createdAt;
}
