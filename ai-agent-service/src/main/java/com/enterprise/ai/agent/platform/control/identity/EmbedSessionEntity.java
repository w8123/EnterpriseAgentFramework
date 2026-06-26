package com.enterprise.ai.agent.platform.control.identity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("eaf_embed_session")
public class EmbedSessionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;

    private String tenantId;

    private String appId;

    private String projectCode;

    private String agentId;

    private String externalUserId;

    private String globalUserId;

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
