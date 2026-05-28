package com.enterprise.ai.agent.platform.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("platform_user")
public class PlatformUserEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String displayName;
    private String email;
    private String mobile;
    private String status;
    private String sourceProvider;
    private String externalSubject;
    private String passwordHash;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
