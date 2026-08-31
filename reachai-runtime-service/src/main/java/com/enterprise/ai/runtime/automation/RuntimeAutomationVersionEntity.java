package com.enterprise.ai.runtime.automation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_automation_version")
public class RuntimeAutomationVersionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long automationId;
    private Integer versionNo;
    private String targetType;
    private String targetId;
    private Long targetVersionId;
    private String targetSnapshotJson;
    private String triggerType;
    private String cronExpression;
    private LocalDateTime fireAt;
    private String timeZone;
    private String misfirePolicy;
    private Integer misfireGraceSeconds;
    private Integer maxCatchUp;
    private String concurrencyPolicy;
    private Integer maxConcurrentRuns;
    private Integer timeoutSeconds;
    private Integer maxAttempts;
    private Integer initialBackoffSeconds;
    private Integer maxBackoffSeconds;
    private String inputJson;
    private String principalType;
    private String principalId;
    private String principalSnapshotJson;
    private String interactionPolicy;
    private String fingerprintSha256;
    private String createdBy;
    private LocalDateTime createdAt;
}
