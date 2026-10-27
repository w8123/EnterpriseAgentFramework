package com.enterprise.ai.control.mcp.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_mcp_write_approval")
public class McpWriteApprovalEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long connectionId;
    private Long businessUserId;
    private String externalUserId;
    private String userName;
    private String toolKind;
    private Long projectId;
    private String projectCode;
    private String systemName;
    private Long publicationId;
    private Integer revisionNo;
    private String toolName;
    private String toolDescription;
    private String sourceKind;
    private String sourceRef;
    private Long workflowVersionId;
    private String capabilityContractHash;
    private String riskLevel;
    private String argumentsJson;
    private String argumentsHash;
    private String state;
    private String decidedBy;
    private LocalDateTime decidedAt;
    private String decisionComment;
    private LocalDateTime executedAt;
    private String resultJson;
    private String errorCode;
    private String runId;
    private String traceId;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
