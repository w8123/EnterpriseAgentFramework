package com.enterprise.ai.control.agentskill;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_agent_skill_review")
public class AgentSkillReviewEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long skillId;
    private Long skillVersionId;
    private String decision;
    private String comment;
    private String findingsJson;
    private String reviewer;
    private LocalDateTime createdAt;
}
