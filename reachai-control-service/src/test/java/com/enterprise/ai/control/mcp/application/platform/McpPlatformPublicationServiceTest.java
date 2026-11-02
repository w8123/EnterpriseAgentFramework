package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.port.McpPlatformPublicationPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformPublicationPort.Change;
import com.enterprise.ai.control.mcp.application.port.McpPlatformPublicationPort.Read;
import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class McpPlatformPublicationServiceTest {
    private final McpPlatformPublicationPort publications = mock(McpPlatformPublicationPort.class);
    private final PlatformAiApprovalService approvals = mock(PlatformAiApprovalService.class);
    private final McpPlatformPublicationService service = new McpPlatformPublicationService(publications, approvals, new ObjectMapper());
    private final PlatformAiConnectionService.ConnectionIdentity actor = new PlatformAiConnectionService.ConnectionIdentity(19L, "CODEX",
            new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "操作员"), "session", null, List.of(), List.of(), List.of()));

    @Test void withoutTheConsolePermissionNothingIsFrozen() {
        when(publications.admits(actor.session(), Change.PUBLISH, Map.of("irreversibleAcknowledged", true))).thenReturn(false);
        assertEquals(403, service.request(actor, Change.PUBLISH, 3L, null, Map.of("irreversibleAcknowledged", true),
                "00000000-0000-0000-0000-000000000001").getStatusCode().value());
        verifyNoInteractions(approvals);
        verify(publications, never()).read(any(), any(), any(), any());
    }

    @Test void publishFreezesItemsAndPrecheckAndRefusesADraftEditedAfterReview() {
        when(publications.admits(any(), any(), any())).thenReturn(true);
        doReturn(ResponseEntity.ok(detail("2026-10-30T10:00:00"))).when(publications).read(actor.session(), Read.DETAIL, 3L, Map.of());
        doReturn(ResponseEntity.ok(Map.of("ok", true, "toolCount", 1))).when(publications).read(actor.session(), Read.PRECHECK, 3L, Map.of());
        service.request(actor, Change.PUBLISH, 3L, null, Map.of(), "00000000-0000-0000-0000-000000000002");
        var frozen = frozen();
        assertEquals(1, ((Map<?, ?>) frozen.get("precheck")).get("toolCount"));
        claim(frozen);

        doReturn(ResponseEntity.ok(detail("2026-10-30T11:00:00"))).when(publications).read(actor.session(), Read.DETAIL, 3L, Map.of());
        assertEquals(409, service.execute(actor, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
        verify(publications, never()).change(any(), any(), anyLong(), any(), any());

        doReturn(ResponseEntity.ok(detail("2026-10-30T10:00:00"))).when(publications).read(actor.session(), Read.DETAIL, 3L, Map.of());
        doReturn(ResponseEntity.ok(Map.of("publication", Map.of("state", "PUBLISHED"))))
                .when(publications).change(eq(actor.session()), eq(Change.PUBLISH), eq(3L), isNull(), any());
        assertEquals(200, service.execute(actor, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "SUCCEEDED", null);
    }

    @Test void domainRejectionFailsWithItsCodeAndUnexpectedFailureIsUnknown() {
        when(publications.admits(any(), any(), any())).thenReturn(true);
        doReturn(ResponseEntity.ok(detail("2026-10-30T10:00:00"))).when(publications).read(actor.session(), Read.DETAIL, 3L, Map.of());
        service.request(actor, Change.SUSPEND, 3L, null, null, "00000000-0000-0000-0000-000000000003");
        claim(frozen());
        doThrow(new McpDomainException("MCP_PUBLICATION_TRANSITION_INVALID", "not published"))
                .when(publications).change(eq(actor.session()), eq(Change.SUSPEND), eq(3L), isNull(), any());
        var rejected = service.execute(actor, "approval");
        assertEquals(Map.of("code", "MCP_PUBLICATION_TRANSITION_INVALID"), rejected.getBody());
        verify(approvals).finish(actor, "approval", "FAILED", "PLATFORM_APPROVAL_MCP_PUBLICATION_REJECTED");

        doThrow(new IllegalStateException("lost")).when(publications).change(eq(actor.session()), eq(Change.SUSPEND), eq(3L), isNull(), any());
        assertEquals(202, service.execute(actor, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
    }

    @Test void clientChangesIgnoreLastUseButNeedAKnownRevisionOrClient() {
        when(publications.admits(any(), any(), any())).thenReturn(true);
        doReturn(ResponseEntity.ok(detail("2026-10-30T10:00:00"))).when(publications).read(actor.session(), Read.DETAIL, 3L, Map.of());
        assertEquals(404, service.request(actor, Change.ROLLBACK, 3L, 99L, null, "00000000-0000-0000-0000-000000000004").getStatusCode().value());
        doReturn(ResponseEntity.ok(List.of(client("2026-10-30T09:00:00")))).when(publications).read(actor.session(), Read.CLIENTS, 3L, Map.of());
        service.request(actor, Change.CLIENT_UPDATE, 3L, 8L, Map.of("enabled", false), "00000000-0000-0000-0000-000000000005");
        claim(frozen());
        doReturn(ResponseEntity.ok(List.of(client("2026-10-30T12:00:00")))).when(publications).read(actor.session(), Read.CLIENTS, 3L, Map.of());
        doReturn(ResponseEntity.ok(Map.of("id", 8, "enabled", false)))
                .when(publications).change(actor.session(), Change.CLIENT_UPDATE, 3L, 8L, Map.of("enabled", false));
        assertEquals(200, service.execute(actor, "approval").getStatusCode().value());
    }

    private Map<String, Object> frozen() {
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("MCP_PUBLICATION_CHANGE"), eq("MCP_PUBLICATION:3"), frozen.capture());
        return frozen.getValue();
    }

    private void claim(Map<String, Object> frozen) {
        when(approvals.claim(actor, "approval", "MCP_PUBLICATION_CHANGE"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "MCP_PUBLICATION_CHANGE", "MCP_PUBLICATION:3", frozen));
    }

    private static Map<String, Object> detail(String itemUpdatedAt) {
        return Map.of("publication", Map.of("id", 3, "name", "订单工具", "state", "DRAFT"),
                "items", List.of(Map.of("id", 5, "sourceKind", "BUSINESS_METHOD", "sourceRef", "orders.create", "enabled", true, "updatedAt", itemUpdatedAt)),
                "revisions", List.of(Map.of("id", 11, "revisionNo", 1)), "clients", List.of(), "availableToolNames", List.of());
    }

    private static Map<String, Object> client(String lastUsedAt) {
        var client = new LinkedHashMap<String, Object>();
        client.put("id", 8); client.put("name", "ERP"); client.put("state", "ACTIVE"); client.put("enabled", true);
        client.put("roles", List.of("sales")); client.put("lastUsedAt", lastUsedAt);
        return client;
    }
}
