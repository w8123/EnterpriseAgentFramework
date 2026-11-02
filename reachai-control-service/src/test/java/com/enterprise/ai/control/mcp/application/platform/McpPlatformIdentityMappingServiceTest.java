package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.port.McpPlatformIdentityPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformIdentityPort.Operation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class McpPlatformIdentityMappingServiceTest {
    private final McpPlatformIdentityPort identities = mock(McpPlatformIdentityPort.class);
    private final PlatformAiApprovalService approvals = mock(PlatformAiApprovalService.class);
    private final McpPlatformIdentityMappingService service = new McpPlatformIdentityMappingService(identities, approvals, new ObjectMapper());
    private final PlatformAiConnectionService.ConnectionIdentity actor = new PlatformAiConnectionService.ConnectionIdentity(19L, "CODEX",
            new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "操作员"), "session", null, List.of(),
                    List.of(PlatformPermissions.RUNTIME_USER_MAPPING_MANAGE), List.of()));

    @Test void mappingTheOwnersOwnAccountIsRefused() {
        var response = service.request(actor, McpPlatformIdentityMappingService.Change.MAPPING_CREATE, null,
                Map.of("tenantId", "default", "platformUserId", 42, "runtimeUserId", "admin"), "inv");
        assertEquals(403, response.getStatusCode().value());
        assertEquals(Map.of("code", McpPlatformIdentityMappingService.SELF_CHANGE), response.getBody());
        verifyNoInteractions(approvals, identities);
    }

    @Test void createRunsOnceAndRefusesWhenAMappingAppearedSinceReview() {
        stubActive(List.of());
        Map<String, Object> fields = Map.of("tenantId", "default", "platformUserId", 7, "runtimeUserId", "staff01");
        service.request(actor, McpPlatformIdentityMappingService.Change.MAPPING_CREATE, null, fields, "inv");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("IDENTITY_MAPPING_CHANGE"), eq("RUNTIME_USER_MAPPING:NEW:7"), frozen.capture());
        when(approvals.claim(actor, "approval", "IDENTITY_MAPPING_CHANGE")).thenReturn(new PlatformAiApprovalService.Claim(
                "approval", "IDENTITY_MAPPING_CHANGE", "RUNTIME_USER_MAPPING:NEW:7", frozen.getValue()));

        stubActive(List.of(Map.of("id", 3, "platformUserId", 7, "runtimeUserId", "other", "status", "ACTIVE")));
        assertEquals(409, service.execute(actor, "approval").getStatusCode().value());
        verify(identities, never()).call(any(), eq(Operation.MAPPING_CREATE), any(), any(), any());

        stubActive(List.of());
        doReturn(ResponseEntity.ok(Map.of("id", 9))).when(identities).call(eq(actor.session()), eq(Operation.MAPPING_CREATE), isNull(), any(), any());
        assertEquals(200, service.execute(actor, "approval").getStatusCode().value());
        verify(identities).call(eq(actor.session()), eq(Operation.MAPPING_CREATE), isNull(), eq(Map.of()), eq(fields));
        verify(approvals).finish(actor, "approval", "SUCCEEDED", null);
    }

    @Test void deletingAnAlreadyDeletedMappingIsRefused() {
        doReturn(ResponseEntity.ok(List.of(Map.of("id", 5, "status", "DELETED")))).when(identities)
                .call(eq(actor.session()), eq(Operation.RUNTIME_MAPPINGS), isNull(), any(), isNull());
        assertEquals(409, service.request(actor, McpPlatformIdentityMappingService.Change.MAPPING_DELETE, 5L,
                Map.of("tenantId", "default"), "inv").getStatusCode().value());
        verifyNoInteractions(approvals);
    }

    @Test void lostDeleteIsUnknownAndNotResent() {
        doReturn(ResponseEntity.ok(List.of(Map.of("id", 5, "status", "ACTIVE")))).when(identities)
                .call(eq(actor.session()), eq(Operation.RUNTIME_MAPPINGS), isNull(), any(), isNull());
        service.request(actor, McpPlatformIdentityMappingService.Change.MAPPING_DELETE, 5L, Map.of("tenantId", "default"), "inv");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("IDENTITY_MAPPING_CHANGE"), eq("RUNTIME_USER_MAPPING:5"), frozen.capture());
        when(approvals.claim(actor, "approval", "IDENTITY_MAPPING_CHANGE")).thenReturn(new PlatformAiApprovalService.Claim(
                "approval", "IDENTITY_MAPPING_CHANGE", "RUNTIME_USER_MAPPING:5", frozen.getValue()));
        doThrow(new IllegalStateException("socket closed")).when(identities).call(eq(actor.session()), eq(Operation.MAPPING_DELETE), eq(5L), any(), isNull());
        assertEquals(202, service.execute(actor, "approval").getStatusCode().value());
        verify(identities, times(1)).call(eq(actor.session()), eq(Operation.MAPPING_DELETE), eq(5L), any(), isNull());
        verify(approvals).finish(actor, "approval", "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
    }

    private void stubActive(List<Map<String, Object>> rows) {
        doReturn(ResponseEntity.ok(rows)).when(identities).call(actor.session(), Operation.RUNTIME_MAPPINGS, null,
                Map.of("tenantId", "default", "platformUserId", 7L, "status", "ACTIVE"), null);
    }
}
