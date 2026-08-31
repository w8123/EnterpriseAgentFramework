package com.enterprise.ai.control.mcp.domain.publication;

import com.enterprise.ai.control.mcp.domain.McpDomainException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpPublicationTest {

    private static final LocalDateTime T1 = LocalDateTime.of(2026, 8, 28, 10, 0);
    private static final LocalDateTime T2 = T1.plusMinutes(1);

    @Test
    void publicationRequiresValidationAndReadyBeforePublishing() {
        McpPublication draft = publication(McpPublicationStatus.DRAFT, null);

        McpDomainException directPublish = assertThrows(
                McpDomainException.class, () -> draft.publish(11L, T1));
        assertEquals("MCP_PUBLICATION_TRANSITION_INVALID", directPublish.code());

        McpPublication published = draft.validating().ready().publish(11L, T1);

        assertEquals(McpPublicationStatus.PUBLISHED, published.state());
        assertEquals(11L, published.currentRevisionId());
        assertEquals(T1, published.updatedAt());
    }

    @Test
    void suspendedPublicationOnlyResumesTheSameRevisionOrArchives() {
        McpPublication published = publication(McpPublicationStatus.PUBLISHED, 11L);
        McpPublication suspended = published.suspend(T1);

        McpDomainException republish = assertThrows(
                McpDomainException.class, suspended::validating);
        assertEquals("MCP_PUBLICATION_TRANSITION_INVALID", republish.code());

        McpPublication resumed = suspended.resume(T2);
        assertEquals(McpPublicationStatus.PUBLISHED, resumed.state());
        assertEquals(11L, resumed.currentRevisionId());
        assertEquals(T2, resumed.updatedAt());
    }

    @Test
    void rollbackChangesOnlyTheCurrentRevisionPointer() {
        McpPublication published = publication(McpPublicationStatus.PUBLISHED, 12L);

        McpPublication rolledBack = published.rollbackTo(7L, T2);

        assertEquals(published.id(), rolledBack.id());
        assertEquals(published.name(), rolledBack.name());
        assertEquals(published.description(), rolledBack.description());
        assertEquals(published.state(), rolledBack.state());
        assertEquals(published.createdAt(), rolledBack.createdAt());
        assertEquals(7L, rolledBack.currentRevisionId());
        assertEquals(T2, rolledBack.updatedAt());
    }

    @Test
    void draftAndArchivedPublicationsCannotRollBack() {
        McpDomainException draftFailure = assertThrows(
                McpDomainException.class,
                () -> publication(McpPublicationStatus.DRAFT, null).rollbackTo(1L, T1));
        assertEquals("MCP_PUBLICATION_ROLLBACK_NOT_ALLOWED", draftFailure.code());

        McpDomainException archivedFailure = assertThrows(
                McpDomainException.class,
                () -> publication(McpPublicationStatus.ARCHIVED, 1L).rollbackTo(1L, T1));
        assertEquals("MCP_PUBLICATION_ROLLBACK_NOT_ALLOWED", archivedFailure.code());
    }

    private McpPublication publication(McpPublicationStatus state, Long revisionId) {
        return new McpPublication(
                1L, "orders", "Orders MCP", state, revisionId,
                LocalDateTime.of(2026, 8, 1, 9, 0),
                LocalDateTime.of(2026, 8, 2, 9, 0));
    }
}
