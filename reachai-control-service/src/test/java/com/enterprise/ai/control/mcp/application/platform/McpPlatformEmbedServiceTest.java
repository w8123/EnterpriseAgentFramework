package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.port.McpPlatformEmbedPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformEmbedPort.Change;
import com.enterprise.ai.control.mcp.application.port.McpPlatformEmbedPort.Read;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class McpPlatformEmbedServiceTest {
    private final McpPlatformEmbedPort embed = mock(McpPlatformEmbedPort.class);
    private final PlatformAiApprovalService approvals = mock(PlatformAiApprovalService.class);
    private final McpPlatformEmbedService service = new McpPlatformEmbedService(embed, approvals, new ObjectMapper());
    private final PlatformAiConnectionService.ConnectionIdentity actor = new PlatformAiConnectionService.ConnectionIdentity(19L, "CODEX",
            new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "操作员"), "session", null, List.of(),
                    List.of("embed:read", "embed:manage"), List.of()));

    @Test void policyUpdateKeepsUntouchedFieldsAndRefusesWhenThePolicyMoved() {
        stubPolicy("[\"https://erp.example.com\"]");
        service.request(actor, Change.UPDATE_POLICY, 3L, Map.of("allowedOrigins", List.of("https://erp.example.com", "https://new.example.com")), "inv");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("EMBED_CHANGE"), eq("EMBED_POLICY:3"), frozen.capture());
        @SuppressWarnings("unchecked") Map<String, Object> after = (Map<String, Object>) frozen.getValue().get("after");
        assertEquals(List.of("erp"), after.get("allowedAgentIds"));
        assertEquals(600, after.get("tokenTtlSeconds"));
        when(approvals.claim(actor, "approval", "EMBED_CHANGE"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "EMBED_CHANGE", "EMBED_POLICY:3", frozen.getValue()));

        stubPolicy("[\"https://other.example.com\"]");
        assertEquals(409, service.execute(actor, "approval").getStatusCode().value());
        verify(embed, never()).change(any(), any(), anyLong(), any());

        stubPolicy("[\"https://erp.example.com\"]");
        doReturn(ResponseEntity.ok(Map.of("id", 3))).when(embed).change(eq(actor.session()), eq(Change.UPDATE_POLICY), eq(3L), any());
        assertEquals(200, service.execute(actor, "approval").getStatusCode().value());
        verify(embed).change(actor.session(), Change.UPDATE_POLICY, 3L, after);
        verify(approvals).finish(actor, "approval", "SUCCEEDED", null);
    }

    @Test void pageDeleteFreezesItsActionsButNotHeartbeats() {
        stubPage("2026-10-30T10:00:00");
        service.request(actor, Change.DELETE_PAGE, 8L, null, "inv");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("EMBED_CHANGE"), eq("EMBED_PAGE:8"), frozen.capture());
        when(approvals.claim(actor, "approval", "EMBED_CHANGE"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "EMBED_CHANGE", "EMBED_PAGE:8", frozen.getValue()));
        stubPage("2026-10-30T11:00:00");
        doThrow(new IllegalStateException("lost")).when(embed).change(actor.session(), Change.DELETE_PAGE, 8L, null);
        assertEquals(202, service.execute(actor, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
    }

    private void stubPolicy(String origins) {
        doReturn(ResponseEntity.ok(List.of(Map.of("id", 3, "projectCode", "orders", "appKey", "ak", "allowedOriginsJson", origins,
                "allowedAgentIdsJson", "[\"erp\"]", "tokenTtlSeconds", 600, "status", "ACTIVE"))))
                .when(embed).read(actor.session(), Read.CREDENTIAL_POLICIES, Map.of("limit", 1000));
    }

    private void stubPage(String lastSeen) {
        doReturn(ResponseEntity.ok(List.of(Map.of("id", 8, "projectCode", "orders", "appId", "erp", "pageKey", "customers",
                "name", "客户", "routePattern", "/customers", "origin", "https://erp.example.com", "lastSeenAt", lastSeen))))
                .when(embed).read(actor.session(), Read.PAGES, Map.of("limit", 1000));
        doReturn(ResponseEntity.ok(List.of(Map.of("actionKey", "create", "title", "新建客户"))))
                .when(embed).read(actor.session(), Read.PAGE_ACTIONS, Map.of("projectCode", "orders", "pageKey", "customers", "limit", 1000));
    }
}
