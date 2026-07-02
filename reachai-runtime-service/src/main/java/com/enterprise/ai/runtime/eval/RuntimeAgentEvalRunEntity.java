package com.enterprise.ai.runtime.eval;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_agent_eval_run")
public class RuntimeAgentEvalRunEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long datasetId;

    private String agentId;

    private String agentName;

    private String runName;

    private Integer repeatCount;

    private String status;

    private String canvasSnapshotJson;

    private String graphSpecJson;

    private String summaryJson;

    private String suggestionJson;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createTime;
}
