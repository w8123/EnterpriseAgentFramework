package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_a2a_conformance_run")
public class A2aConformanceRunEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String runId;
    private String targetType;
    private Long targetId;
    private String protocolVersion;
    private String transport;
    private String suiteName;
    private String suiteVersion;
    private String requirementLevel;
    private String status;
    private Integer passedCount;
    private Integer failedCount;
    private Integer skippedCount;
    private String reportRef;
    private String reportSha256;
    private String safeSummaryJson;
    private String triggeredBy;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
}
