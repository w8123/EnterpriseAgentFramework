package com.enterprise.ai.control.a2a.application.overview;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class A2aHubOverviewApplicationService {

    private final A2aHubOverviewReader reader;
    private final Clock clock;

    public A2aHubOverviewView overview() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime since = now.minusHours(24);
        A2aHubOverviewReader.Snapshot snapshot = reader.read(since, now.plusDays(30));

        List<A2aHubOverviewView.AttentionItem> attention = new ArrayList<>();
        addAttention(attention, "REMOTE_AGENT_QUARANTINED", "CRITICAL",
                snapshot.quarantinedRemoteAgents(), "/a2a-hub/remote-agents?status=QUARANTINED");
        addAttention(attention, "CREDENTIAL_EXPIRING", "WARNING",
                snapshot.expiringCredentials(), "/a2a-hub/trust?filter=EXPIRING");
        addAttention(attention, "CONFORMANCE_FAILED", "CRITICAL",
                snapshot.failedConformanceRuns(), "/a2a-hub/developer?status=FAILED");
        addAttention(attention, "TASK_AUTH_REQUIRED", "WARNING",
                snapshot.authRequiredTasks(), "/a2a-hub/tasks?state=TASK_STATE_AUTH_REQUIRED");
        addAttention(attention, "TASK_INPUT_REQUIRED", "INFO",
                snapshot.inputRequiredTasks(), "/a2a-hub/tasks?state=TASK_STATE_INPUT_REQUIRED");

        return new A2aHubOverviewView(
                "reachai.a2a-hub.overview.v1",
                since,
                now,
                new A2aHubOverviewView.PublicationSummary(
                        snapshot.publishedPublications(),
                        snapshot.draftPublications(),
                        snapshot.suspendedPublications()),
                new A2aHubOverviewView.RemoteAgentSummary(
                        snapshot.trustedRemoteAgents(),
                        snapshot.quarantinedRemoteAgents(),
                        snapshot.unhealthyRemoteAgents()),
                new A2aHubOverviewView.TaskSummary(
                        snapshot.totalTasks(),
                        snapshot.completedTasks(),
                        snapshot.failedTasks(),
                        snapshot.workingTasks(),
                        snapshot.inputRequiredTasks(),
                        snapshot.authRequiredTasks(),
                        percentage(snapshot.completedTasks(), snapshot.totalTasks())),
                new A2aHubOverviewView.TransportSummary(
                        snapshot.transportRequests(),
                        snapshot.successfulTransportRequests(),
                        percentage(snapshot.successfulTransportRequests(), snapshot.transportRequests()),
                        snapshot.averageTransportLatencyMs(),
                        null,
                        "P95_PENDING_TIME_SERIES"),
                new A2aHubOverviewView.GovernanceSummary(
                        snapshot.expiringCredentials(),
                        snapshot.failedConformanceRuns()),
                List.copyOf(attention));
    }

    private static void addAttention(
            List<A2aHubOverviewView.AttentionItem> items,
            String code,
            String severity,
            long count,
            String route) {
        if (count > 0) {
            items.add(new A2aHubOverviewView.AttentionItem(code, severity, count, route));
        }
    }

    private static double percentage(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0;
        }
        return Math.round((numerator * 10000.0) / denominator) / 100.0;
    }
}
