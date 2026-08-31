package com.enterprise.ai.control.mcp.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_mcp_call_log")
public class McpCallLogEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String direction;
    private Long publicationId;
    private Long clientId;
    private String clientName;
    private Long remoteServerId;
    private String method;
    private String toolName;
    private Long projectId;
    private String projectCode;
    private String environment;
    private String tenantId;
    private Boolean success;
    private Long latencyMs;
    private String errorCategory;
    private String requestBody;
    private String responseBody;
    private String errorMessage;
    private String traceId;
    private String runId;
    private String remoteIp;
    private LocalDateTime createdAt;
}
