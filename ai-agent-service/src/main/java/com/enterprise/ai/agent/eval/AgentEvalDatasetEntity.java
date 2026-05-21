package com.enterprise.ai.agent.eval;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_eval_dataset")
public class AgentEvalDatasetEntity {
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
