package com.enterprise.ai.agent.mcp;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mcp_call_log")
public class McpCallLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long clientId;

    private String clientName;

    private String method;

    private String toolName;

    private Boolean success;

    private Long latencyMs;

    private String requestBody;

    private String responseBody;

    private String errorMessage;

    private String traceId;

    private String remoteIp;

    private LocalDateTime createdAt;
}
