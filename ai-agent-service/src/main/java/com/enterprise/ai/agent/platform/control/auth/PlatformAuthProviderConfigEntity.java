package com.enterprise.ai.agent.platform.control.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("platform_auth_provider")
public class PlatformAuthProviderConfigEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String providerCode;

    private String providerName;

    private String providerType;

    private String configJson;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
