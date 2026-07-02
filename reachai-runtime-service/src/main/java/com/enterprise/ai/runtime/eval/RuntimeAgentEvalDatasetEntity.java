package com.enterprise.ai.runtime.eval;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_agent_eval_dataset")
public class RuntimeAgentEvalDatasetEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String agentId;

    private String agentName;

    private String name;

    private String description;

    private String source;

    private Integer caseCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
