package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_a2a_trust_profile")
public class A2aTrustProfileEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String profileKey;
    private String name;
    private String description;
    private String direction;
    private String environment;
    private String trustLevel;
    private String authenticationMethodsJson;
    private String allowedScopesJson;
    private String authorizationPolicyJson;
    private String dataPolicyJson;
    private String delegatedIdentityPolicy;
    private String personalMemoryPolicy;
    private Integer rateLimitPerMinute;
    private Integer maxConcurrentTasks;
    private Long maxRequestBytes;
    private Long maxArtifactBytes;
    private Long taskTimeoutMs;
    private Boolean allowAnonymous;
    private String status;
    @Version
    private Integer version;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
