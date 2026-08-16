package com.enterprise.ai.runtime.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Metadata-only lifecycle audit. Conversation text and raw user identifiers are forbidden here. */
@Data
@TableName("runtime_session_retention_audit")
public class RuntimeSessionRetentionAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String sessionIdHash;
    private Long conversationSessionId;
    private String eventType;
    private String actorType;
    private String actorIdHash;
    private String reasonCode;
    private String referenceId;
    private String previousStatus;
    private String resultStatus;
    private String failureCode;
    private LocalDateTime createdAt;
}
