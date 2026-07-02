package com.enterprise.ai.control.governance;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_mcp_call_log")
public class ControlMcpCallLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long clientId;
    private String clientName;
    private String method;
    private String toolName;
    private Long projectId;
    private String projectCode;
    private String environment;
    private String tenantId;
    private Boolean success;
    private Long latencyMs;
    private String requestBody;
    private String responseBody;
    private String errorMessage;
    private String traceId;
    private String remoteIp;
    private LocalDateTime createdAt;
}
