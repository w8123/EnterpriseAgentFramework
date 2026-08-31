package com.enterprise.ai.control.mcp.domain.publication;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McpPublicationStatusTest {

    @Test
    void transitionMatrixRejectsEveryUndeclaredLifecycleEdge() {
        Map<McpPublicationStatus, Set<McpPublicationStatus>> allowed = Map.of(
                McpPublicationStatus.DRAFT,
                EnumSet.of(McpPublicationStatus.VALIDATING, McpPublicationStatus.ARCHIVED),
                McpPublicationStatus.VALIDATING,
                EnumSet.of(McpPublicationStatus.DRAFT, McpPublicationStatus.READY,
                        McpPublicationStatus.ARCHIVED),
                McpPublicationStatus.READY,
                EnumSet.of(McpPublicationStatus.DRAFT, McpPublicationStatus.PUBLISHED,
                        McpPublicationStatus.ARCHIVED),
                McpPublicationStatus.PUBLISHED,
                EnumSet.of(McpPublicationStatus.VALIDATING, McpPublicationStatus.SUSPENDED,
                        McpPublicationStatus.ARCHIVED),
                McpPublicationStatus.SUSPENDED,
                EnumSet.of(McpPublicationStatus.PUBLISHED, McpPublicationStatus.ARCHIVED),
                McpPublicationStatus.ARCHIVED,
                EnumSet.noneOf(McpPublicationStatus.class));

        for (McpPublicationStatus current : McpPublicationStatus.values()) {
            for (McpPublicationStatus next : McpPublicationStatus.values()) {
                assertEquals(allowed.get(current).contains(next), current.canTransitionTo(next),
                        () -> current + " -> " + next);
            }
            assertEquals(false, current.canTransitionTo(null), current + " -> null");
        }
    }
}
