package com.enterprise.ai.control.mcp.api.management;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.mcp.application.port.McpPlatformPublicationPort.Change;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class McpPublicationPlatformAdapterTest {
    private final McpPublicationPlatformAdapter adapter = new McpPublicationPlatformAdapter(mock(McpPublicationController.class),
            mock(McpClientController.class), mock(McpEmployeeAccessController.class), mock(PlatformDelegatedRequestScope.class));

    @Test void admitsMirrorsTheConsolePermissionsPerChange() {
        var publisher = session("mcp-hub:read", "mcp-hub:publication:manage");
        assertTrue(adapter.admits(publisher, Change.PUBLISH, Map.of()));
        assertFalse(adapter.admits(publisher, Change.PUBLISH, Map.of("irreversibleAcknowledged", true)));
        assertFalse(adapter.admits(publisher, Change.CLIENT_REVOKE, Map.of()));
        var credentials = session("mcp-hub:read", "mcp-hub:credential:manage");
        assertTrue(adapter.admits(credentials, Change.CLIENT_UPDATE, Map.of("enabled", false)));
        assertFalse(adapter.admits(credentials, Change.CLIENT_UPDATE, Map.of("roles", List.of("admin"))));
        assertFalse(adapter.admits(credentials, Change.ARCHIVE, Map.of()));
    }

    private static PlatformAuthenticatedSession session(String... permissions) {
        return new PlatformAuthenticatedSession(new PlatformPrincipal(1L, "u", "U"), "s", null, List.of(), List.of(permissions), List.of());
    }
}
