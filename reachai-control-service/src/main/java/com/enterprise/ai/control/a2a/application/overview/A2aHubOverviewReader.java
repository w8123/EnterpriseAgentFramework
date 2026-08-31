package com.enterprise.ai.control.a2a.application.overview;

import java.time.LocalDateTime;

/** Read port kept independent from MyBatis and the management API. */
public interface A2aHubOverviewReader {

    Snapshot read(LocalDateTime since, LocalDateTime credentialExpiryCutoff);

    record Snapshot(
            long publishedPublications,
            long draftPublications,
            long suspendedPublications,
            long trustedRemoteAgents,
            long quarantinedRemoteAgents,
            long unhealthyRemoteAgents,
            long totalTasks,
            long completedTasks,
            long failedTasks,
            long workingTasks,
            long inputRequiredTasks,
            long authRequiredTasks,
            long expiringCredentials,
            long failedConformanceRuns,
            long transportRequests,
            long successfulTransportRequests,
            Long averageTransportLatencyMs) {
    }
}
