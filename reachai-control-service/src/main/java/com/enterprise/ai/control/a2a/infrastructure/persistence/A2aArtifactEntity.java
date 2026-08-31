package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_a2a_artifact")
public class A2aArtifactEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskRefId;
    private String artifactId;
    private String name;
    private String description;
    private String payloadCiphertext;
    private String payloadObjectRef;
    private String encryptionKeyId;
    private String encryptionNonce;
    private String payloadSha256;
    private Long payloadBytes;
    private String mediaTypesJson;
    private String contentClassification;
    private String safeSummary;
    private Integer appendRevision;
    private Boolean lastChunk;
    private LocalDateTime retentionExpiresAt;
    private LocalDateTime contentDeletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
