package com.enterprise.ai.control.mcp.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_ai_tool_connection")
public class AiToolConnectionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long businessUserId;
    private String appId;
    private String externalUserId;
    private String userName;
    private String toolKind;
    private String keyPrefix;
    private String keyHash;
    private String state;
    private LocalDateTime expiresAt;
    private LocalDateTime lastUsedAt;
    private LocalDateTime revokedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
