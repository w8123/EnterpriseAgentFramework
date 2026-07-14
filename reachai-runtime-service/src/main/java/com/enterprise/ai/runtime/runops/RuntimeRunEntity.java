package com.enterprise.ai.runtime.runops;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One durable root fact for one externally initiated Runtime execution.
 *
 * <p>RunOps lists and metrics must start from this table. Trace spans, tool calls and guard
 * decisions are child events and must never be used to infer whether a root run existed.</p>
 */
@Data
@TableName("runtime_run")
public class RuntimeRunEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String traceId;
    private String runType;
    private String entryType;
    private String status;
    private Long projectId;
    private String projectCode;
    private String tenantId;
    private String appId;
    private String sessionId;
    private String userId;
    private String externalUserId;
    private String globalUserId;
    private String pageInstanceId;
    private String origin;
    private String agentId;
    private String agentKeySlug;
    private String agentName;
    private Long agentConfigVersionId;
    private Integer agentConfigVersion;
    private String workflowId;
    private String workflowKeySlug;
    private String workflowName;
    private Long workflowVersionId;
    private String workflowVersion;
    private String runtimeType;
    private String rootSpanId;
    private String inputSummary;
    private String outputSummary;
    private String errorCode;
    private String errorMessage;
    private Integer latencyMs;
    private Integer tokenCost;
    private Integer planCount;
    private Integer replanCount;
    private Integer workflowCallCount;
    private Integer toolCallCount;
    private Integer guardDenyCount;
    private Integer approvalCount;
    private String replayOfTraceId;
    private String snapshotJson;
    private String metadataJson;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
