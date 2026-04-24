package com.enterprise.ai.agent.tool.log;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Tool 调用审计日志实体。
 * <p>
 * Phase 1 采集，Phase 2 用于 Skill Mining（高频 chain 挖掘）。
 */
@Data
@TableName("tool_call_log")
public class ToolCallLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 一次 Agent 执行的 trace id，同一次 Agent 内多次 tool 调用共享。 */
    private String traceId;

    private String sessionId;

    private String userId;

    private String agentName;

    private String intentType;

    private String toolName;

    private String argsJson;

    private String resultSummary;

    private Boolean success;

    private String errorCode;

    private Integer elapsedMs;

    private Integer tokenCost;

    /** 本次 Agent 召回 top-K + 分数 + 选中项，同 trace 共享一份。 */
    private String retrievalTraceJson;

    private LocalDateTime createTime;
}
