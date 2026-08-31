package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_a2a_context")
public class A2aContextEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String contextId;
    private String direction;
    private Long principalId;
    private String tenantScope;
    private Long publicationId;
    private Long remoteAgentId;
    private Long remoteRevisionId;
    private String remoteContextId;
    private String runtimeSessionId;
    private String status;
    private LocalDateTime lastActivityAt;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
