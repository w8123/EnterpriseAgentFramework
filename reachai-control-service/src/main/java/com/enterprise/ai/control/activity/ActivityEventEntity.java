package com.enterprise.ai.control.activity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** One operation or search an employee made inside a connected business system. Never carries request content. */
@Data
@TableName("control_activity_event")
public class ActivityEventEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String projectCode;
    private String tenantId;
    private String userId;
    private String userName;
    private String eventType;
    private String operationKey;
    private String operationName;
    private String pagePath;
    private String keyword;
    private String outcome;
    private String errorMessage;
    private Integer durationMs;
    private String source;
    private LocalDateTime occurredAt;
    private LocalDateTime createdAt;
}
