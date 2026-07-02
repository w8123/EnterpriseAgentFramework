package com.enterprise.ai.agent.registry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("capability_sync_log")
public class CapabilitySyncLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private String projectCode;

    private String syncId;

    private String source;

    private String status;

    private String summaryJson;

    private String errorMessage;

    private LocalDateTime createdAt;
}
