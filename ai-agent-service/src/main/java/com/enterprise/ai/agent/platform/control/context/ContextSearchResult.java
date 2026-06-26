package com.enterprise.ai.agent.platform.control.context;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ContextSearchResult {

    private ContextItemResponse item;
    private double rankScore;
    /** Primary human-readable hit reason. */
    private String hitReason;
    /** Compact scoring breakdown, e.g. title=3.0, trust=1.0, confidence=0.8 */
    private String scoreBreakdown;
}
