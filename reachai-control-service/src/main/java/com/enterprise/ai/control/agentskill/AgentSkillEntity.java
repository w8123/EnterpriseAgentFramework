package com.enterprise.ai.control.agentskill;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_agent_skill")
public class AgentSkillEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String scopeKey;
    private String publisher;
    private String standardName;
    private String displayName;
    private String description;
    private String visibility;
    private Long ownerUserId;
    private String projectCode;
    private String status;
    private Long latestVersionId;
    private Long defaultVersionId;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
