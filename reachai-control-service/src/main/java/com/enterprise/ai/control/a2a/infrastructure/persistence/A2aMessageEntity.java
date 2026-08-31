package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_a2a_message")
public class A2aMessageEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String messageId;
    private String direction;
    private Long principalId;
    private String tenantScope;
    private Long contextRefId;
    private Long taskRefId;
    private String role;
    private String payloadCiphertext;
    private String payloadObjectRef;
    private String encryptionKeyId;
    private String encryptionNonce;
    private String payloadSha256;
    private String idempotencySha256;
    private Long payloadBytes;
    private String contentClassification;
    private String safeSummary;
    private String safeMetadataJson;
    private LocalDateTime retentionExpiresAt;
    private LocalDateTime contentDeletedAt;
    private LocalDateTime createdAt;
}
