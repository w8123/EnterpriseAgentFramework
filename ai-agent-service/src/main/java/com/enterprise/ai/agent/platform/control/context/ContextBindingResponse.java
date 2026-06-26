package com.enterprise.ai.agent.platform.control.context;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ContextBindingResponse {

    private Long id;
    private Long itemId;
    private String bindType;
    private String bindId;
    private String bindKey;
    private String tenantId;
    private Long projectId;
    private String projectCode;
    private String status;
    private LocalDateTime createdAt;
}
