package com.enterprise.ai.agent.platform.control.context;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class ContextOpsSummaryResponse {

    private String tenantId;
    private String projectCode;
    private Long projectId;
    private String memoryLane;
    private int namespaceCount;
    private int itemCount;
    private int activeItemCount;
    private int staleItemCount;
    private int revokedItemCount;
    private int deletedItemCount;
    private int expiringItemCount;
    private int pendingCandidateCount;
    private int expiredCandidateCount;
    private int auditEventCountRecent;
    private int staleDueItemCount;
    private int runtimeUserExcludedCount;
    @Builder.Default
    private List<String> warnings = new ArrayList<>();
}
