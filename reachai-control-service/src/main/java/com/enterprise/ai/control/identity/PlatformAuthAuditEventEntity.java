package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Immutable, non-secret audit record for high-risk platform auth changes. */
@Data
@TableName("control_platform_auth_audit_event")
public class PlatformAuthAuditEventEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String eventType;

    private Long actorUserId;

    private String actorSessionId;

    private String targetType;

    private String targetId;

    private String detailsJson;

    private LocalDateTime createdAt;
}
