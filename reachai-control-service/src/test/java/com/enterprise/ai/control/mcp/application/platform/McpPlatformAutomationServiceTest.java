package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.platform.McpPlatformAutomationService.Change;
import com.enterprise.ai.control.mcp.application.port.McpPlatformAutomationPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformAutomationPort.Operation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class McpPlatformAutomationServiceTest {
    private static final String KEY = "aut_" + "a".repeat(32);
    private final McpPlatformAutomationPort automations = mock(McpPlatformAutomationPort.class);
    private final PlatformAiApprovalService approvals = mock(PlatformAiApprovalService.class);
    private final McpPlatformAutomationService service = new McpPlatformAutomationService(automations, approvals, new ObjectMapper());
    private final PlatformAiConnectionService.ConnectionIdentity actor = identity(new PlatformPermissionGrant("automation:write", "PROJECT", "orders"));

    @Test void creatingOutsideTheWritableProjectIsRefusedBeforeReview() {
        assertEquals(403, service.request(actor, Change.CREATE, null, Map.of("name", "日报", "projectCode", "finance"),
                "00000000-0000-0000-0000-000000000001").getStatusCode().value());
        assertEquals(403, service.request(actor, Change.CREATE, null, Map.of("name", "日报"),
                "00000000-0000-0000-0000-000000000002").getStatusCode().value());
        verifyNoInteractions(approvals);
    }

    @Test void updateIgnoresFireTimesButRefusesARevisionChangeAndSubmitsTheReviewedRevision() {
        doReturn(ResponseEntity.ok(detail(4, "2026-10-31T09:00"))).when(automations).call(actor.session(), Operation.GET, KEY, null, Map.of(), null);
        service.request(actor, Change.UPDATE, KEY, Map.of("name", "库存日报"), "00000000-0000-0000-0000-000000000003");
        var frozen = frozen();
        claim(frozen);

        doReturn(ResponseEntity.ok(detail(4, "2026-11-01T09:00"))).when(automations).call(actor.session(), Operation.GET, KEY, null, Map.of(), null);
        doReturn(ResponseEntity.ok(Map.of("automation", Map.of("revision", 5))))
                .when(automations).call(eq(actor.session()), eq(Operation.UPDATE), eq(KEY), isNull(), eq(Map.of()), any());
        assertEquals(200, service.execute(actor, "approval").getStatusCode().value());
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(automations).call(eq(actor.session()), eq(Operation.UPDATE), eq(KEY), isNull(), eq(Map.of()), body.capture());
        assertEquals(Map.of("name", "库存日报", "expectedRevision", 4L), body.getValue());

        doReturn(ResponseEntity.ok(detail(5, "2026-11-01T09:00"))).when(automations).call(actor.session(), Operation.GET, KEY, null, Map.of(), null);
        assertEquals(409, service.execute(actor, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
    }

    @Test void runNowThatIsNotConfirmedIsUnknownAndNotResent() {
        doReturn(ResponseEntity.status(503).body(Map.of("code", "AUTOMATION_RUNTIME_UNAVAILABLE")))
                .when(automations).call(actor.session(), Operation.RUN, KEY, null, Map.of(), null);
        assertEquals(202, service.operate(actor, Operation.RUN, KEY, null, null).getStatusCode().value());
        verify(automations, times(1)).call(any(), any(), any(), any(), any(), any());
        assertThrows(IllegalArgumentException.class, () -> service.operate(actor, Operation.CREATE, null, null, Map.of()));
    }

    @Test void approvedResumeThatTheRuntimeRejectsFails() {
        doReturn(ResponseEntity.ok(detail(4, "2026-10-31T09:00"))).when(automations).call(actor.session(), Operation.GET, KEY, null, Map.of(), null);
        service.request(actor, Change.RESUME, KEY, null, "00000000-0000-0000-0000-000000000004");
        claim(frozen());
        doReturn(ResponseEntity.status(409).body(Map.of("code", "AUTOMATION_REVISION_CONFLICT")))
                .when(automations).call(actor.session(), Operation.RESUME, KEY, null, Map.of(), Map.of("expectedRevision", 4L));
        assertEquals(409, service.execute(actor, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "FAILED", "PLATFORM_APPROVAL_AUTOMATION_REJECTED");
    }

    private Map<String, Object> frozen() {
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("AUTOMATION_CHANGE"), eq("AUTOMATION:" + KEY), frozen.capture());
        return frozen.getValue();
    }

    private void claim(Map<String, Object> frozen) {
        when(approvals.claim(actor, "approval", "AUTOMATION_CHANGE"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "AUTOMATION_CHANGE", "AUTOMATION:" + KEY, frozen));
    }

    private static PlatformAiConnectionService.ConnectionIdentity identity(PlatformPermissionGrant grant) {
        return new PlatformAiConnectionService.ConnectionIdentity(19L, "CODEX",
                new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "操作员"), "session", null, List.of(), List.of(), List.of(grant)));
    }

    private static Map<String, Object> detail(int revision, String nextFireAt) {
        var summary = new LinkedHashMap<String, Object>();
        summary.put("automationKey", KEY); summary.put("name", "日报"); summary.put("projectCode", "orders"); summary.put("status", "PAUSED");
        summary.put("revision", revision); summary.put("nextFireAt", nextFireAt);
        return Map.of("automation", summary, "currentVersion", Map.of("id", 1, "versionNo", 1));
    }
}
