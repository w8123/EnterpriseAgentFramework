package com.enterprise.ai.control.a2a.application.overview;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class A2aHubOverviewApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-23T01:02:03Z");

    @Test
    void buildsAWindowedOverviewWithoutInventingUnavailablePercentiles() {
        A2aHubOverviewReader reader = (since, credentialExpiryCutoff) -> {
            assertEquals(LocalDateTime.of(2026, 8, 22, 1, 2, 3), since);
            assertEquals(LocalDateTime.of(2026, 9, 22, 1, 2, 3), credentialExpiryCutoff);
            return new A2aHubOverviewReader.Snapshot(
                    3, 2, 1,
                    5, 1, 2,
                    8, 6, 1, 1, 2, 1,
                    4, 1,
                    10, 9, 125L);
        };
        A2aHubOverviewApplicationService service = new A2aHubOverviewApplicationService(
                reader,
                Clock.fixed(NOW, ZoneOffset.UTC));

        A2aHubOverviewView view = service.overview();

        assertEquals("reachai.a2a-hub.overview.v1", view.schema());
        assertEquals(75.0, view.tasks().completionRate());
        assertEquals(90.0, view.transport().successRate());
        assertEquals(125L, view.transport().averageLatencyMs());
        assertNull(view.transport().p95LatencyMs());
        assertEquals("P95_PENDING_TIME_SERIES", view.transport().percentileStatus());
        assertEquals(5, view.attention().size());
    }

    @Test
    void returnsZeroRatesAndNoAttentionWhenThereIsNoTrafficOrRisk() {
        A2aHubOverviewReader reader = (since, credentialExpiryCutoff) ->
                new A2aHubOverviewReader.Snapshot(
                        0, 0, 0,
                        0, 0, 0,
                        0, 0, 0, 0, 0, 0,
                        0, 0,
                        0, 0, null);
        A2aHubOverviewApplicationService service = new A2aHubOverviewApplicationService(
                reader,
                Clock.fixed(NOW, ZoneOffset.UTC));

        A2aHubOverviewView view = service.overview();

        assertEquals(0.0, view.tasks().completionRate());
        assertEquals(0.0, view.transport().successRate());
        assertEquals(0, view.attention().size());
    }
}
