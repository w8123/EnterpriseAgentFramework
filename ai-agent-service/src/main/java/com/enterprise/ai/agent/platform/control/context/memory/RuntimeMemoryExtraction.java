package com.enterprise.ai.agent.platform.control.context.memory;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class RuntimeMemoryExtraction {

    private ContextMemoryCandidateType candidateType;
    private String title;
    private String content;
    private String summary;
    private String reason;
    private BigDecimal confidence;
}
