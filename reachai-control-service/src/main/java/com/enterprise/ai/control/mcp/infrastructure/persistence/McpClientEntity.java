package com.enterprise.ai.control.mcp.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_mcp_client")
public class McpClientEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long publicationId;
    private String name;
    private Long projectId;
    private String projectCode;
    private String environment;
    private String tenantId;
    private String apiKeyPrefix;
    private String apiKeyHash;
    private String rolesJson;
    private String toolScopeJson;
    private String state;
    private Boolean enabled;
    private LocalDateTime expiresAt;
    private LocalDateTime lastUsedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
