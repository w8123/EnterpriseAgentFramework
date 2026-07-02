package com.enterprise.ai.runtime.trace;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tool_call_log")
public class RuntimeToolCallLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String traceId;
    private String sessionId;
    private String userId;
    private String agentName;
    private String intentType;
    private Long projectId;
    private String projectCode;
    private String environment;
    private String tenantId;
    private String appId;
    private String externalUserId;
    private String globalUserId;
    private String pageInstanceId;
    private String origin;
    private String toolName;
    private String argsJson;
    private String resultSummary;
    private Boolean success;
    private String errorCode;
    private Integer elapsedMs;
    private Integer tokenCost;
    private String retrievalTraceJson;
    private LocalDateTime createTime;
}
