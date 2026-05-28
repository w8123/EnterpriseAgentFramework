package com.enterprise.ai.agent.trace;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_trace_span")
public class AgentTraceSpanEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String traceId;

    private String spanId;

    private String parentSpanId;

    private String spanType;

    private String runtimeType;

    private String agentId;

    private String agentName;

    private String nodeId;

    private String toolName;

    private String modelInstanceId;

    private String projectCode;

    private String tenantId;

    private String appId;

    private String externalUserId;

    private String globalUserId;

    private String pageInstanceId;

    private String status;

    private String inputSummary;

    private String outputSummary;

    private String metadataJson;

    private String errorCode;

    private String errorMessage;

    private Integer latencyMs;

    private Integer tokenCost;

    private LocalDateTime startedAt;

    private LocalDateTime endedAt;

    private LocalDateTime createdAt;
}
