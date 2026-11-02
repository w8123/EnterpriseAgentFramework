package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.port.McpPlatformDebugSessionPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformHttpApiAcceptPort;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class McpPlatformDebugSessionServiceTest {
    private final PlatformAiConnectionService.ConnectionIdentity actor = new PlatformAiConnectionService.ConnectionIdentity(19L, "CODEX",
            new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "操作员"), "session", null, List.of(),
                    List.of("workflow:debug", "platform:write"), List.of()));

    @Test void lostCancelIsUnknownAndRunsOnce() {
        var port = mock(McpPlatformDebugSessionPort.class);
        doThrow(new IllegalStateException("lost")).when(port).cancel(actor.session(), "s1");
        assertEquals(202, new McpPlatformDebugSessionService(port).cancel(actor, "s1").getStatusCode().value());
        verify(port, times(1)).cancel(actor.session(), "s1");
    }

    @Test void acceptNeedsTheRevisionTheCallerRead() {
        var port = mock(McpPlatformHttpApiAcceptPort.class);
        var service = new McpPlatformHttpApiAcceptService(port);
        assertThrows(IllegalArgumentException.class, () -> service.accept(actor, 5L, "latest"));
        verifyNoInteractions(port);
        String revision = "a".repeat(64);
        doReturn(ResponseEntity.ok(Map.of("id", 5))).when(port).accept(actor.session(), 5L, revision);
        assertEquals(200, service.accept(actor, 5L, revision).getStatusCode().value());
    }
}
