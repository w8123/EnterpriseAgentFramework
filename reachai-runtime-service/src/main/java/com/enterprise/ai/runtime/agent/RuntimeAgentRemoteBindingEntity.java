package com.enterprise.ai.runtime.agent;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Immutable-per-config-version snapshot; Runtime never reads Control A2A catalog tables. */
@Data
@TableName("runtime_agent_remote_agent_binding")
public class RuntimeAgentRemoteBindingEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String agentId;
    private Long agentConfigVersionId;
    private Long principalId;
    private Long remoteAgentId;
    private Long remoteAgentRevisionId;
    private String remoteAgentKeySnapshot;
    private String toolName;
    private String descriptionSnapshot;
    private String allowedSkillIdsJson;
    private String inputModesJson;
    private String outputModesJson;
    private String riskLevel;
    private String permissionKey;
    private Long timeoutMs;
    private Integer priority;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
