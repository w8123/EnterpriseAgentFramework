package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.port.McpPlatformWorkflowCredentialPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class McpPlatformWorkflowCredentialServiceTest {
    private final McpPlatformWorkflowCredentialPort credentials = mock(McpPlatformWorkflowCredentialPort.class);
    private final PlatformAiApprovalService approvals = mock(PlatformAiApprovalService.class);
    private final McpPlatformWorkflowCredentialService service = new McpPlatformWorkflowCredentialService(credentials, approvals, new ObjectMapper());
    private final PlatformAiConnectionService.ConnectionIdentity actor = new PlatformAiConnectionService.ConnectionIdentity(19L, "CODEX",
            new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "操作员"), "session", null, List.of(),
                    List.of("workflow:credential:manage"), List.of()));

    @Test void secretRotationKeepsOnlyAFingerprintAndNeedsTheSameSecretAgain() {
        stub("2026-10-30T10:00:00");
        service.request(actor, McpPlatformWorkflowCredentialService.Change.UPDATE, 4L, null, "orders", null, Map.of("token", "new-secret"), "inv");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("WORKFLOW_CREDENTIAL_CHANGE"), eq("WORKFLOW_CREDENTIAL:4"), frozen.capture());
        assertFalse(new ObjectMapper().valueToTree(frozen.getValue()).toString().contains("new-secret"));
        assertEquals(true, frozen.getValue().get("secretChanged"));
        when(approvals.claim(actor, "approval", "WORKFLOW_CREDENTIAL_CHANGE"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "WORKFLOW_CREDENTIAL_CHANGE", "WORKFLOW_CREDENTIAL:4", frozen.getValue()));

        assertEquals(400, service.execute(actor, "approval", Map.of("token", "other")).getStatusCode().value());
        verify(credentials, never()).update(any(), anyLong(), any());

        reset(approvals);
        when(approvals.claim(actor, "approval", "WORKFLOW_CREDENTIAL_CHANGE"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "WORKFLOW_CREDENTIAL_CHANGE", "WORKFLOW_CREDENTIAL:4", frozen.getValue()));
        doReturn(ResponseEntity.ok(Map.of("id", 4))).when(credentials).update(eq(actor.session()), eq(4L), any());
        assertEquals(200, service.execute(actor, "approval", Map.of("token", "new-secret")).getStatusCode().value());
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
        verify(credentials).update(eq(actor.session()), eq(4L), sent.capture());
        assertEquals("ERP 令牌", sent.getValue().get("name"));
        assertEquals("BEARER", sent.getValue().get("type"));
        assertEquals(Map.of("token", "new-secret"), sent.getValue().get("secret"));
        verify(approvals).finish(actor, "approval", "SUCCEEDED", null);
    }

    @Test void disableIsRefusedWhenTheCredentialChangedSinceReview() {
        stub("2026-10-30T10:00:00");
        service.request(actor, McpPlatformWorkflowCredentialService.Change.DISABLE, 4L, null, "orders", null, null, "inv");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("WORKFLOW_CREDENTIAL_CHANGE"), eq("WORKFLOW_CREDENTIAL:4"), frozen.capture());
        when(approvals.claim(actor, "approval", "WORKFLOW_CREDENTIAL_CHANGE"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "WORKFLOW_CREDENTIAL_CHANGE", "WORKFLOW_CREDENTIAL:4", frozen.getValue()));
        stub("2026-10-30T11:00:00");
        assertEquals(409, service.execute(actor, "approval", null).getStatusCode().value());
        verify(credentials, never()).disable(any(), anyLong());
    }

    @Test void typeChangeWithoutANewSecretIsRefused() {
        assertEquals(400, service.request(actor, McpPlatformWorkflowCredentialService.Change.UPDATE, 4L, null, "orders",
                Map.of("type", "BASIC"), null, "inv").getStatusCode().value());
        verifyNoInteractions(approvals);
    }

    @Test void projectIdOnlyCredentialIsFoundAndRevalidatedByItsProjectId() {
        doReturn(ResponseEntity.ok(List.of(Map.of("id", 5, "credentialRef", "cred_id_only", "name", "仅编号", "type", "BEARER",
                "projectId", 3, "scope", "PROJECT", "status", "ACTIVE", "secretPreview", Map.of("token", "ab****yz"), "updatedAt", "t1"))))
                .when(credentials).list(actor.session(), 3L, null);
        service.request(actor, McpPlatformWorkflowCredentialService.Change.DISABLE, 5L, 3L, null, null, null, "inv");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("WORKFLOW_CREDENTIAL_CHANGE"), eq("WORKFLOW_CREDENTIAL:5"), frozen.capture());
        when(approvals.claim(actor, "approval", "WORKFLOW_CREDENTIAL_CHANGE"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "WORKFLOW_CREDENTIAL_CHANGE", "WORKFLOW_CREDENTIAL:5", frozen.getValue()));
        doReturn(ResponseEntity.ok(Map.of())).when(credentials).disable(actor.session(), 5L);
        assertEquals(200, service.execute(actor, "approval", null).getStatusCode().value());
        verify(credentials, times(2)).list(actor.session(), 3L, null);
    }

    private void stub(String updatedAt) {
        doReturn(ResponseEntity.ok(List.of(Map.of("id", 4, "credentialRef", "cred_erp", "name", "ERP 令牌", "type", "BEARER",
                "projectId", 3, "projectCode", "orders", "scope", "PROJECT", "status", "ACTIVE",
                "secretPreview", Map.of("token", "ab****yz"), "updatedAt", updatedAt))))
                .when(credentials).list(eq(actor.session()), any(), eq("orders"));
    }
}
