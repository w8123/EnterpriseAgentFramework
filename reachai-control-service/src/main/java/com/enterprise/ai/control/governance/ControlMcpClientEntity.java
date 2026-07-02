package com.enterprise.ai.control.governance;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_mcp_client")
public class ControlMcpClientEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String apiKeyPrefix;
    private Long projectId;
    private String projectCode;
    private String environment;
    private String tenantId;
    private String apiKeyHash;
    private String rolesJson;
    private String toolWhitelistJson;
    private Boolean enabled;
    private LocalDateTime expiresAt;
    private LocalDateTime lastUsedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
