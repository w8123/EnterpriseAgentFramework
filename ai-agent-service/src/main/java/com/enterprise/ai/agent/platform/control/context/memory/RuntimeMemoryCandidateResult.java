package com.enterprise.ai.agent.platform.control.context.memory;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RuntimeMemoryCandidateResult {

    private boolean created;
    private Long candidateId;
    private List<Long> candidateIds;
    private int createdCount;
    private String extractionMode;
    private String skippedReason;
}
