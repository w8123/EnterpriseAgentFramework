package com.enterprise.ai.runtime.trace.usage;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** One model call's token usage inside Runtime. Never stores prompts or answers. */
@Data
@TableName("runtime_model_usage")
public class RuntimeModelUsageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Root runtime_run.trace_id; NULL when the call ran outside a run (e.g. Studio AI editing). */
    private String traceId;

    /** AGENT / AGENT_COMPACTION / WORKFLOW_NODE / WORKFLOW_AUTHORING. */
    private String usageSource;

    private String modelInstanceId;

    private Integer inputTokens;

    private Integer outputTokens;

    private LocalDateTime createdAt;
}
