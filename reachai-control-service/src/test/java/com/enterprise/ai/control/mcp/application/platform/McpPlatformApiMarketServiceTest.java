package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.port.McpPlatformApiMarketPort;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class McpPlatformApiMarketServiceTest {
    private final McpPlatformApiMarketPort market = mock(McpPlatformApiMarketPort.class);
    private final McpPlatformApiMarketService service = new McpPlatformApiMarketService(market);
    private final PlatformAiConnectionService.ConnectionIdentity actor = new PlatformAiConnectionService.ConnectionIdentity(19L, "CODEX",
            new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "操作员"), "session", null, List.of(),
                    List.of("platform:read", "platform:write"), List.of()));

    @Test void lostSelectionIsUnknownAndNotResent() {
        Map<String, Object> body = Map.of("projectId", 3);
        doThrow(new IllegalStateException("lost")).when(market).select(actor.session(), "erp.customer", body);
        assertEquals(202, service.select(actor, "erp.customer", body).getStatusCode().value());
        verify(market, times(1)).select(actor.session(), "erp.customer", body);

        doReturn(ResponseEntity.status(503).build()).when(market).updateStatus(actor.session(), 7L, Map.of("status", "DISABLED"));
        assertEquals(202, service.updateStatus(actor, 7L, Map.of("status", "DISABLED")).getStatusCode().value());
    }

    @Test void definiteRefusalsAreReturnedAsTheyAre() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(market).select(actor.session(), "erp.customer", Map.of());
        assertThrows(ResponseStatusException.class, () -> service.select(actor, "erp.customer", Map.of()));
    }
}
