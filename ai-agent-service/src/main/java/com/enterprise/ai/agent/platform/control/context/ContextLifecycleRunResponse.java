package com.enterprise.ai.agent.platform.control.context;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class ContextLifecycleRunResponse {

    private String tenantId;
    private String projectCode;
    private Long projectId;
    private boolean dryRun;
    private int expiredCandidateCount;
    private int staleItemCount;
    private int skippedRuntimeUserItemCount;
    private int scannedItemCount;
    @Builder.Default
    private List<String> warnings = new ArrayList<>();
}
