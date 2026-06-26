package com.enterprise.ai.agent.platform.control.context;

import lombok.Data;

@Data
public class ContextBindingRequest {

    private String bindType;
    private String bindId;
    private String bindKey;
    private String tenantId;
    private Long projectId;
    private String projectCode;
}
