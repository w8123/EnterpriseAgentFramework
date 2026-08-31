package com.enterprise.ai.runtime.automation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_automation")
public class RuntimeAutomationEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String automationKey;
    private String tenantId;
    private Long projectId;
    private String projectCode;
    private String name;
    private String description;
    private String status;
    private Long currentVersionId;
    private LocalDateTime nextFireAt;
    private LocalDateTime lastFireAt;
    private Long revision;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
