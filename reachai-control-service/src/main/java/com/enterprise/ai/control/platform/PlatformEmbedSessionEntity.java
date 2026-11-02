package com.enterprise.ai.control.platform;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

@Data
@TableName("control_embed_session")
public class PlatformEmbedSessionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private String tenantId;
    private String appId;
    private String projectCode;
    private String agentId;
    private String externalUserId;
    private String externalUserName;
    private String globalUserId;
    @JsonIgnore
    @ToString.Exclude
    private String businessSessionId;
    private String pageKey;
    private String pageInstanceId;
    private String route;
    private String origin;
    private String sdkVersion;
    private String bridgeActionsJson;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
