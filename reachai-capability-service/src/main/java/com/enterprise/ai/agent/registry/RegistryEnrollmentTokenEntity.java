package com.enterprise.ai.agent.registry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One-time project enrollment proof. The raw token is never persisted.
 */
@Data
@TableName("capability_registry_enrollment_token")
public class RegistryEnrollmentTokenEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String projectCode;

    private String tokenDigest;

    private Long createdByUserId;

    private LocalDateTime expiresAt;

    private LocalDateTime consumedAt;

    private LocalDateTime createdAt;
}
