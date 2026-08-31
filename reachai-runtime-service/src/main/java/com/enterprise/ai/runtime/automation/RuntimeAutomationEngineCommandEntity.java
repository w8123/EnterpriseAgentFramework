package com.enterprise.ai.runtime.automation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_automation_engine_command")
public class RuntimeAutomationEngineCommandEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long automationId;
    private Long automationVersionId;
    private String commandType;
    private String status;
    private Integer attemptCount;
    private LocalDateTime availableAt;
    private String leaseOwner;
    private String leaseToken;
    private LocalDateTime leasedUntil;
    private String lastErrorCode;
    private String lastErrorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
}
