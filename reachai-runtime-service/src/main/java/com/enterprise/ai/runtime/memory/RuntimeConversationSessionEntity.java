package com.enterprise.ai.runtime.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_conversation_session")
public class RuntimeConversationSessionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String sessionId;
    private String userId;
    private String agentId;
    private Long agentConfigVersionId;
    private String projectCode;
    private String identitySource;
    private String stateUserKey;
    private String stateSessionKey;
    private String status;
    private Integer eventCount;
    private String turnLeaseOwner;
    private LocalDateTime turnLeaseExpiresAt;
    private LocalDateTime lastTurnAt;
    private LocalDateTime clearedAt;
    private Boolean legalHold;
    private String legalHoldReasonCode;
    private String legalHoldReference;
    private LocalDateTime legalHoldSetAt;
    private String legalHoldSetByHash;
    private String lifecycleOwner;
    private LocalDateTime lifecycleLeaseExpiresAt;
    private String lifecycleReasonCode;
    private String lifecyclePreviousStatus;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
