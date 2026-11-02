package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.port.McpPlatformMemoryErasurePort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class McpPlatformMemoryErasureServiceTest {
    private final McpPlatformMemoryErasurePort erasures = mock(McpPlatformMemoryErasurePort.class);
    private final PlatformAiApprovalService approvals = mock(PlatformAiApprovalService.class);
    private final McpPlatformMemoryErasureService service = new McpPlatformMemoryErasureService(erasures, approvals);
    private final PlatformAiConnectionService.ConnectionIdentity actor = new PlatformAiConnectionService.ConnectionIdentity(19L, "CODEX",
            new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "compliance", "合规"), "session", null, List.of(),
                    List.of("context:memory:erasure:manage"), List.of()));

    @Test void createGoesThroughReviewAndSendsTheFrozenTargetOnce() {
        service.request(actor, command("ERASE_ALL_AGENT_MEMORY_DOMAINS"), "inv");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("MEMORY_ERASURE_REQUEST"), eq("MEMORY_ERASURE:req-1"), frozen.capture());
        assertEquals("user-7", frozen.getValue().get("runtimeUserId"));
        verifyNoInteractions(erasures);
        when(approvals.claim(actor, "approval", "MEMORY_ERASURE_REQUEST"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "MEMORY_ERASURE_REQUEST", "MEMORY_ERASURE:req-1", frozen.getValue()));
        doReturn(ResponseEntity.accepted().body(Map.of("requestId", "r"))).when(erasures).create(eq(actor.session()), any());

        assertEquals(202, service.execute(actor, "approval").getStatusCode().value());
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
        verify(erasures, times(1)).create(eq(actor.session()), sent.capture());
        assertEquals("ERASE_ALL_AGENT_MEMORY_DOMAINS", sent.getValue().get("confirmation"));
        assertEquals("tenant-a", sent.getValue().get("tenantId"));
        verify(approvals).finish(actor, "approval", "SUCCEEDED", null);
    }

    @Test void missingConfirmationIsRefusedBeforeReview() {
        assertEquals(400, service.request(actor, command("yes"), "inv").getStatusCode().value());
        verifyNoInteractions(approvals);
    }

    @Test void lostResultIsUnknownAndNotResent() {
        Map<String, Object> frozen = new LinkedHashMap<>(command(null));
        frozen.remove("confirmation");
        frozen.put("contractVersion", 1);
        when(approvals.claim(actor, "approval", "MEMORY_ERASURE_REQUEST"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "MEMORY_ERASURE_REQUEST", "MEMORY_ERASURE:req-1", frozen));
        when(erasures.create(any(), any())).thenThrow(new IllegalStateException("socket closed"));
        assertEquals(202, service.execute(actor, "approval").getStatusCode().value());
        verify(erasures, times(1)).create(any(), any());
        verify(approvals).finish(actor, "approval", "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
    }

    private static Map<String, Object> command(String confirmation) {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("confirmation", confirmation);
        command.put("tenantId", "tenant-a"); command.put("runtimeUserId", "user-7"); command.put("clientRequestId", "req-1");
        command.put("reasonCode", "GDPR_ART17"); command.put("referenceId", "ticket-9");
        return command;
    }
}
