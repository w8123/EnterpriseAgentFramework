package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_a2a_credential")
public class A2aCredentialEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String credentialKey;
    private String name;
    private String direction;
    private String credentialType;
    private String materialMode;
    private String materialHash;
    private String materialSalt;
    private String materialCiphertext;
    private String encryptionKeyId;
    private String encryptionNonce;
    private String externalRef;
    private String fingerprint;
    private Integer versionNo;
    private String status;
    private LocalDateTime notBefore;
    private LocalDateTime expiresAt;
    private LocalDateTime lastUsedAt;
    private Long rotatedFromId;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
