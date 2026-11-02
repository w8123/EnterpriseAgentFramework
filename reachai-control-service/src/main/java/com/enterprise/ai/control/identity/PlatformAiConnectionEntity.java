package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Platform-user delegation; this is not an employee or browser-session credential. */
@Data
@TableName("control_platform_ai_connection")
public class PlatformAiConnectionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String label;
    private String toolKind;
    private String tokenHash;
    private String tokenHint;
    private String rolesJson;
    private String grantsJson;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private LocalDateTime lastUsedAt;
    private LocalDateTime createdAt;
}
