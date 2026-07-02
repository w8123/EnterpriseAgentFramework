package com.enterprise.ai.runtime.interaction;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_skill_interaction")
public class RuntimeSkillInteractionEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String traceId;

    private String sessionId;

    private String userId;

    private Long agentId;

    private String skillName;

    private String status;

    private String slotState;

    private String pendingKeys;

    private String uiPayload;

    private String specSnapshot;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime expiresAt;
}
