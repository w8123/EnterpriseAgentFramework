package com.enterprise.ai.agent.registry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("capability_registry_project_credential")
public class RegistryCredentialEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private String projectCode;

    private String appKey;

    /** 当前 MVP 直接存储签名密钥；生产可替换为 KMS/密文列。 */
    private String appSecret;

    private String status;

    private LocalDateTime expiresAt;

    private String allowedOriginsJson;

    private String allowedAgentIdsJson;

    private Integer tokenTtlSeconds;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
