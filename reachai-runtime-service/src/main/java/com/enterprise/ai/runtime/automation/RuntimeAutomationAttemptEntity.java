package com.enterprise.ai.runtime.automation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_automation_attempt")
public class RuntimeAutomationAttemptEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long occurrenceId;
    private Integer attemptNo;
    private String status;
    private String workerId;
    private String leaseToken;
    private String traceId;
    private String resultSummary;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private LocalDateTime createdAt;
}
