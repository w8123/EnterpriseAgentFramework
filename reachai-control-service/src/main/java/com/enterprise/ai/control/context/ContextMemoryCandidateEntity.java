package com.enterprise.ai.control.context;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("context_memory_candidate")
public class ContextMemoryCandidateEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String candidateKey;

    private String tenantId;

    private Long projectId;

    private String projectCode;

    private Long namespaceId;

    private String namespaceKey;

    private String memoryLane;

    private String candidateType;

    private String title;

    private String content;

    private String summary;

    private String reason;

    private String sourceType;

    private String sourceRef;

    private String traceId;

    private String sessionId;

    private String userId;

    private String externalUserId;

    private String globalUserId;

    private String agentId;

    private String agentKey;

    private String workflowId;

    private String workflowKey;

    private String pageInstanceId;

    private String origin;

    private BigDecimal confidence;

    private String trustLevel;

    private String visibility;

    private String status;

    private String proposedBy;

    private String reviewedBy;

    private LocalDateTime reviewedAt;

    private String reviewReason;

    private Long approvedItemId;

    private String metadataJson;

    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;
}
