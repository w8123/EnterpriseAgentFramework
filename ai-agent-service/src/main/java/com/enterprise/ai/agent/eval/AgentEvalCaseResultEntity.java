package com.enterprise.ai.agent.eval;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_eval_case_result")
public class AgentEvalCaseResultEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Long datasetId;
    private Long caseId;
    private String caseNo;
    private Integer roundNo;
    private String status;
    private Boolean runtimeSuccess;
    private Boolean assertionPassed;
    private Double semanticScore;
    private Double score;
    private Integer elapsedMs;
    private String answer;
    private String traceId;
    private String stepResultsJson;
    private String judgeResultJson;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime createTime;
}
