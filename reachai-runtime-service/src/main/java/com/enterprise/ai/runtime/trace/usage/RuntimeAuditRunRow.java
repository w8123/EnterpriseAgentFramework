package com.enterprise.ai.runtime.trace.usage;

import lombok.Data;

import java.time.LocalDateTime;

/** One business run for the audit export; carries no prompt, answer or argument content. */
@Data
public class RuntimeAuditRunRow {
    private String traceId;
    private String runType;
    private String entryType;
    private String status;
    private String projectCode;
    private String appId;
    private String externalUserId;
    private String userId;
    private String agentName;
    private String workflowName;
    private String errorCode;
    private Integer toolCallCount;
    private Integer approvalCount;
    private Integer tokenCost;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
}
