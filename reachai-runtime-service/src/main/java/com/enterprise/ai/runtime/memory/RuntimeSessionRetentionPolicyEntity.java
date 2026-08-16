package com.enterprise.ai.runtime.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_session_retention_policy")
public class RuntimeSessionRetentionPolicyEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Integer activeRetentionDays;
    private Integer clearedRetentionHours;
    private String updatedByHash;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
