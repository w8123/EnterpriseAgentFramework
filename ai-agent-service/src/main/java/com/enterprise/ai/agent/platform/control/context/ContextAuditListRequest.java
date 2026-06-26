package com.enterprise.ai.agent.platform.control.context;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ContextAuditListRequest {

    private String tenantId;
    private String projectCode;
    private Long projectId;
    private Long itemId;
    private Long namespaceId;
    private String eventType;
    private String actorType;
    private String actorId;
    private String decision;
    private String traceId;
    private LocalDateTime dateFrom;
    private LocalDateTime dateTo;
    private Integer limit;
}
