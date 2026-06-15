package com.enterprise.ai.agent.workflow;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class WorkflowRuntimeRequest {

    private String traceId;

    private String sessionId;

    private String message;

    private AgentEntryEntity agent;

    private WorkflowDefinitionEntity workflow;

    private WorkflowVersionEntity activeVersion;

    private Map<String, Object> principal;

    private Map<String, Object> pageContext;

    private Map<String, Object> metadata;

    private boolean allowDraftFallback;
}
