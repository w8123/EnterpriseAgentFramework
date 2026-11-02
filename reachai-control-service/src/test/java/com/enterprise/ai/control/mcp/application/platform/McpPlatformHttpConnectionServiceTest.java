package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.port.McpPlatformHttpConnectionPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformHttpConnectionPort.Change;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class McpPlatformHttpConnectionServiceTest {
    private final McpPlatformHttpConnectionPort port = mock(McpPlatformHttpConnectionPort.class);
    private final PlatformAiApprovalService approvals = mock(PlatformAiApprovalService.class);
    private final McpPlatformHttpConnectionService service = new McpPlatformHttpConnectionService(port, approvals, new ObjectMapper());
    private final PlatformAiConnectionService.ConnectionIdentity actor = new PlatformAiConnectionService.ConnectionIdentity(19L, "CODEX",
            new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "操作员"), "session", null, List.of(), List.of("platform:write"), List.of()));

    @Test void savingTheOverrideFreezesWhereCallsGoAndWritesAtTheReviewedRevision() {
        stub(4L, "HEALTHY");
        service.request(actor, Change.SAVE_OVERRIDE, 9L, Map.of("origin", "https://erp-new.example"), "inv");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("HTTP_API_CONNECTION_CHANGE"), eq("HTTP_API:9"), frozen.capture());
        var after = (Map<?, ?>) frozen.getValue().get("after");
        assertEquals("https://erp-new.example", after.get("origin"));
        assertEquals("cred-1", after.get("credentialRef"));
        when(approvals.claim(actor, "approval", "HTTP_API_CONNECTION_CHANGE"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "HTTP_API_CONNECTION_CHANGE", "HTTP_API:9", frozen.getValue()));

        stub(5L, "HEALTHY");
        assertEquals(409, service.execute(actor, "approval").getStatusCode().value());
        verify(port, never()).change(any(), any(), anyLong(), any());

        stub(4L, "DEGRADED");
        doReturn(ResponseEntity.ok(Map.of())).when(port).change(eq(actor.session()), eq(Change.SAVE_OVERRIDE), eq(9L), any());
        assertEquals(200, service.execute(actor, "approval").getStatusCode().value());
        verify(port).change(actor.session(), Change.SAVE_OVERRIDE, 9L,
                Map.of("origin", "https://erp-new.example", "authMode", "BEARER", "credentialRef", "cred-1", "expectedRevision", 4));
    }

    @Test void removingAnOverrideNeedsOne() {
        var view = new LinkedHashMap<String, Object>(Map.of("qualifiedName", "erp.customers", "overrideActive", false, "status", "HEALTHY"));
        doReturn(ResponseEntity.ok(view)).when(port).connection(actor.session(), 9L);
        assertEquals(400, service.request(actor, Change.REMOVE_OVERRIDE, 9L, null, "inv").getStatusCode().value());
        verifyNoInteractions(approvals);
    }

    private void stub(long overrideRevision, String status) {
        var view = new LinkedHashMap<String, Object>();
        view.put("qualifiedName", "erp.customers"); view.put("projectCode", "erp"); view.put("origin", "https://erp.example");
        view.put("authMode", "BEARER"); view.put("credentialRef", "cred-1"); view.put("overrideActive", true);
        view.put("overrideRevision", overrideRevision); view.put("status", status); view.put("connectionSource", "OVERRIDE");
        doReturn(ResponseEntity.ok(view)).when(port).connection(actor.session(), 9L);
    }
}
