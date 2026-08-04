package com.enterprise.ai.control.aicoding.domain;

import java.util.List;

/**
 * Provider-neutral delivery evidence reported by an AI Coding client.
 *
 * <p>These values are delivery material for user review. They never replace
 * server-observed readiness or grant a client permission to complete a task.</p>
 */
public final class AiCodingDeliveryEvidence {

    private AiCodingDeliveryEvidence() {
    }

    public record ReportedCheck(
            String name,
            String status,
            String command,
            String evidence) {
    }

    public record BrowserVerification(
            boolean passed,
            String browser,
            String url,
            List<String> scenarios,
            List<String> screenshots,
            String evidence) {
    }
}
