package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_a2a_remote_agent")
public class A2aRemoteAgentEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String remoteAgentKey;
    private String displayName;
    private String tenantScope;
    private String cardUrl;
    private String cardUrlSha256;
    private Long trustProfileId;
    private Long credentialId;
    private Long currentRevisionId;
    private String preferredInterfaceKey;
    private String preferredSecuritySchemeKey;
    private String status;
    private String healthStatus;
    private Integer consecutiveHealthFailures;
    private LocalDateTime lastDiscoveredAt;
    private LocalDateTime lastHealthCheckedAt;
    private String lastHealthSummary;
    @Version
    private Integer version;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
