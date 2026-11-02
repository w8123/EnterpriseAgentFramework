package com.enterprise.ai.control.mcp.api.management;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.mcp.application.port.McpPlatformOpsReadPort.Read;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class McpHubOpsPlatformAdapterTest {
    private final McpCallLogController callLogs = mock(McpCallLogController.class);
    private final McpPlatformConversationReader conversations = mock(McpPlatformConversationReader.class);
    private final PlatformDelegatedRequestScope scope = mock(PlatformDelegatedRequestScope.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final McpHubOpsPlatformAdapter adapter = new McpHubOpsPlatformAdapter(mock(McpHubOverviewController.class), mock(McpUsageController.class),
            callLogs, mock(McpEmployeeConnectionController.class), mock(McpWriteApprovalController.class), conversations, scope);
    private final PlatformAuthenticatedSession actor = new PlatformAuthenticatedSession(new PlatformPrincipal(1L, "u", "U"), "s", null,
            List.of(), List.of("mcp-hub:read"), List.of());

    @SuppressWarnings("unchecked")
    McpHubOpsPlatformAdapterTest() {
        when(scope.run(eq(actor), any())).thenAnswer(call -> ((Function<HttpServletRequest, ResponseEntity<?>>) call.getArgument(1)).apply(request));
    }

    @Test void callLogDetailUsesTheConsoleEndpointThatGatesBodies() {
        adapter.read(actor, Read.MCP_CALL_LOG, "12", Map.of());
        verify(callLogs).detail(request, 12L);
    }

    @Test void conversationsGoToTheRunCenterReader() {
        doReturn(ResponseEntity.ok(List.of())).when(conversations).read(actor, "sess-1", Map.of());
        assertEquals(200, adapter.read(actor, Read.CONVERSATION, "sess-1", Map.of()).getStatusCode().value());
        verifyNoInteractions(scope);
    }
}
