package com.enterprise.ai.control.mcp.domain.publication;

import com.enterprise.ai.control.mcp.domain.McpDomainException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpPublicationRevisionTest {

    @Test
    void frozenToolSnapshotCannotBeMutatedThroughTheCallerOrAccessor() {
        List<McpToolProjection> source = new ArrayList<>();
        source.add(tool("lookup"));
        McpPublicationRevision revision = new McpPublicationRevision(
                9L, 1L, 1, source, "{}", LocalDateTime.of(2026, 8, 28, 10, 0));

        source.add(tool("create"));

        assertEquals(List.of(tool("lookup")), revision.tools());
        assertThrows(UnsupportedOperationException.class,
                () -> revision.tools().add(tool("delete")));
    }

    @Test
    void duplicateExternalToolNamesAreRejectedAtFreezeTime() {
        McpDomainException failure = assertThrows(McpDomainException.class,
                () -> new McpPublicationRevision(
                        null, 1L, 1, List.of(tool("lookup"), tool("lookup")), "{}",
                        LocalDateTime.of(2026, 8, 28, 10, 0)));

        assertEquals("MCP_TOOL_NAME_DUPLICATED", failure.code());
    }

    private McpToolProjection tool(String name) {
        return new McpToolProjection(
                name, name, "{\"type\":\"object\"}",
                McpPublicationItemKind.CAPABILITY, "orders." + name, null, "READ");
    }
}
