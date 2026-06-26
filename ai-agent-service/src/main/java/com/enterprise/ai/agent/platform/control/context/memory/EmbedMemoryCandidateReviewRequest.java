package com.enterprise.ai.agent.platform.control.context.memory;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Embed-side review body; identity fields are injected server-side from token/session.
 */
@Data
public class EmbedMemoryCandidateReviewRequest {

    private String reviewReason;
    private BigDecimal confidence;
    private String trustLevel;
}
