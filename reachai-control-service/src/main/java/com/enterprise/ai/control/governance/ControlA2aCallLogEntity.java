package com.enterprise.ai.control.governance;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_a2a_call_log")
public class ControlA2aCallLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long endpointId;
    private String agentKey;
    private Long projectId;
    private String projectCode;
    private String environment;
    private String tenantId;
    private String taskId;
    private String method;
    private Boolean success;
    private Long latencyMs;
    private String requestBody;
    private String responseBody;
    private String errorMessage;
    private String traceId;
    private String remoteIp;
    private LocalDateTime createdAt;
}
