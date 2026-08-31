package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_a2a_outbound_execution")
public class A2aOutboundExecutionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskRefId;
    private Long runtimeBindingId;
    private Long agentConfigVersionId;
    private Long principalTrustProfileId;
    private Long remoteTrustProfileId;
    private String remoteInterfaceKey;
    private String remoteSecuritySchemeKey;
    private Long credentialId;
    private String protocolSkillId;
    private String contentClassification;
    private String acceptedOutputModesJson;
    private Integer historyLength;
    private Long maxRequestBytes;
    private Integer maxResponseBytes;
    private Long maxArtifactBytes;
    private Long timeoutMs;
    private String pollStatus;
    private Integer pollAttemptCount;
    private LocalDateTime nextPollAt;
    private LocalDateTime lastPolledAt;
    private String lastPollErrorCode;
    private String lastPollErrorSummary;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
