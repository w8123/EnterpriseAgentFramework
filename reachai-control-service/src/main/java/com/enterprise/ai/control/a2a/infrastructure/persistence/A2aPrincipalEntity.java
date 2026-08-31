package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_a2a_principal")
public class A2aPrincipalEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String principalKey;
    private String principalType;
    private String displayName;
    private String tenantScope;
    private String authenticatedSubject;
    private Long trustProfileId;
    private Long credentialId;
    private String scopesJson;
    private String attributesJson;
    private String status;
    private LocalDateTime lastAuthenticatedAt;
    @Version
    private Integer version;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
