package com.enterprise.ai.agent.platform.control.context;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ContextPackageResponse {

    private String memoryLane;
    private String tenantId;
    private String projectCode;
    private int totalItems;
    private int truncatedCount;
    private List<ContextSearchResult> projectMemory;
    private List<ContextSearchResult> userMemory;
    private List<ContextSearchResult> pageContext;
    private List<ContextSearchResult> workflowContext;
    private List<ContextSearchResult> apiContext;
    private List<ContextSearchResult> rules;
    private List<ContextSearchResult> evidenceSummary;
}
