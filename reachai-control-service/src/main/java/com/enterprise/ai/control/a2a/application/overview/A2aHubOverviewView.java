package com.enterprise.ai.control.a2a.application.overview;

import java.time.LocalDateTime;
import java.util.List;

public record A2aHubOverviewView(
        String schema,
        LocalDateTime windowStartedAt,
        LocalDateTime generatedAt,
        PublicationSummary publications,
        RemoteAgentSummary remoteAgents,
        TaskSummary tasks,
        TransportSummary transport,
        GovernanceSummary governance,
        List<AttentionItem> attention) {

    public record PublicationSummary(long published, long draft, long suspended) {
    }

    public record RemoteAgentSummary(long trusted, long quarantined, long unhealthy) {
    }

    public record TaskSummary(
            long total,
            long completed,
            long failed,
            long working,
            long inputRequired,
            long authRequired,
            double completionRate) {
    }

    public record TransportSummary(
            long requests,
            long successful,
            double successRate,
            Long averageLatencyMs,
            Long p95LatencyMs,
            String percentileStatus) {
    }

    public record GovernanceSummary(long expiringCredentials, long failedConformanceRuns) {
    }

    public record AttentionItem(String code, String severity, long count, String actionRoute) {
    }
}
