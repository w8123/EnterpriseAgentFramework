package com.enterprise.ai.runtime.eval;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_agent_eval_case")
public class RuntimeAgentEvalCaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long datasetId;

    private String caseNo;

    private String message;

    private String inputParamsJson;

    private String expectedJson;

    private String judgeConfigJson;

    private String tags;

    private Boolean enabled;

    private LocalDateTime createTime;
}
