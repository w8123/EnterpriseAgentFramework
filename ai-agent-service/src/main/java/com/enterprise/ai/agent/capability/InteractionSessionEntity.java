package com.enterprise.ai.agent.capability;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("interaction_session")
public class InteractionSessionEntity {

    @TableId
    private String id;

    private String runId;

    private String compositionQualifiedName;

    private String nodeId;

    private String interactionType;

    private String status;

    private String stateJson;

    private String uiRequestJson;

    private String submittedPayloadJson;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private LocalDateTime expiresAt;
}
