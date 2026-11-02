package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.governance.ControlToolAclPlatformOperations;
import com.enterprise.ai.control.governance.ControlToolAclPlatformOperations.Operation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class McpPlatformToolAclServiceTest {
    private final ControlToolAclPlatformOperations port = mock(ControlToolAclPlatformOperations.class);
    private final PlatformAiApprovalService approvals = mock(PlatformAiApprovalService.class);
    private final McpPlatformToolAclService service = new McpPlatformToolAclService(port, approvals, new ObjectMapper());
    private final PlatformAiConnectionService.ConnectionIdentity actor = new PlatformAiConnectionService.ConnectionIdentity(19L, "CODEX",
            new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "操作员"), "session", null, List.of(), List.of(), List.of()));

    @Test void createNormalizesTheRuleAndRefusesUnknownFields() {
        service.request(actor, Operation.CREATE, null, Map.of("roleCode", " sales ", "targetName", "create_customer", "permission", "allow"),
                "00000000-0000-0000-0000-000000000001");
        ArgumentCaptor<Map<String, Object>> frozen = captor();
        verify(approvals).prepare(eq(actor), eq("TOOL_ACL_CHANGE"), eq("TOOL_ACL:NEW"), frozen.capture());
        Map<?, ?> after = (Map<?, ?>) frozen.getValue().get("after");
        assertEquals("sales", after.get("roleCode"));
        assertEquals("ALLOW", after.get("permission"));
        assertEquals("TOOL", after.get("targetKind"));
        assertEquals(true, after.get("enabled"));
        assertEquals(400, service.request(actor, Operation.CREATE, null, Map.of("roleCode", "sales", "targetName", "x", "id", 9),
                "00000000-0000-0000-0000-000000000002").getStatusCode().value());
        verifyNoMoreInteractions(approvals);
    }

    @Test void batchGrantFreezesTheRulesItWouldOverwriteAndRunsOnce() {
        var existing = new ArrayList<Map<String, Object>>(List.of(Map.of("id", 3, "roleCode", "sales", "targetName", "create_customer", "permission", "DENY")));
        doAnswer(call -> existing).when(port).batchExisting(eq(actor.session()), eq("sales"), isNull(), isNull(), any());
        service.requestBatch(actor, Map.of("roleCode", "sales", "targets", List.of(Map.of("name", "create_customer"), Map.of("kind", "tool", "name", "query_stock"))),
                "00000000-0000-0000-0000-000000000003");
        ArgumentCaptor<Map<String, Object>> frozen = captor();
        verify(approvals).prepare(eq(actor), eq("TOOL_ACL_CHANGE"), eq("TOOL_ACL:BATCH"), frozen.capture());
        assertEquals(1, ((List<?>) frozen.getValue().get("overwritten")).size());
        assertEquals("ALLOW", ((Map<?, ?>) frozen.getValue().get("after")).get("permission"));
        when(approvals.claim(actor, "approval", "TOOL_ACL_CHANGE"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "TOOL_ACL_CHANGE", "TOOL_ACL:BATCH", frozen.getValue()));

        existing.add(Map.of("id", 4, "roleCode", "sales", "targetName", "query_stock", "permission", "DENY"));
        assertEquals(409, service.execute(actor, "approval").getStatusCode().value());
        verify(port, never()).grantBatch(any(), any());

        existing.remove(1);
        doReturn(ResponseEntity.ok(Map.of("ok", true, "count", 2))).when(port).grantBatch(eq(actor.session()), any());
        assertEquals(200, service.execute(actor, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "SUCCEEDED", null);
        assertEquals(400, service.requestBatch(actor, Map.of("roleCode", "sales", "targets", List.of(Map.of("name", "a"), Map.of("name", "a"))),
                "00000000-0000-0000-0000-000000000004").getStatusCode().value());
    }

    @Test void aRuleChangedByAnotherPersonIsRefusedBeforeDispatch() {
        doReturn(ResponseEntity.ok(stored(true, "2026-10-30T10:00:00"))).when(port).find(actor.session(), 5L);
        service.request(actor, Operation.TOGGLE, 5L, Map.of("enabled", false), "00000000-0000-0000-0000-000000000001");
        ArgumentCaptor<Map<String, Object>> frozen = captor();
        verify(approvals).prepare(any(), any(), eq("TOOL_ACL:5"), frozen.capture());
        when(approvals.claim(actor, "approval", "TOOL_ACL_CHANGE"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "TOOL_ACL_CHANGE", "TOOL_ACL:5", frozen.getValue()));
        doReturn(ResponseEntity.ok(stored(true, "2026-10-30T10:05:00"))).when(port).find(actor.session(), 5L);
        assertEquals(409, service.execute(actor, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
        verify(port, never()).change(any(), any(), any(), any());
    }

    @Test void approvedDeleteRunsOnceAndALostResultIsUnknown() {
        doReturn(ResponseEntity.ok(stored(true, "2026-10-30T10:00:00"))).when(port).find(actor.session(), 5L);
        service.request(actor, Operation.DELETE, 5L, null, "00000000-0000-0000-0000-000000000001");
        ArgumentCaptor<Map<String, Object>> frozen = captor();
        verify(approvals).prepare(any(), any(), any(), frozen.capture());
        when(approvals.claim(actor, "approval", "TOOL_ACL_CHANGE"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "TOOL_ACL_CHANGE", "TOOL_ACL:5", frozen.getValue()));
        doThrow(new IllegalStateException("db")).when(port).change(actor.session(), Operation.DELETE, 5L, Map.of());
        assertEquals(202, service.execute(actor, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        verify(port, times(1)).change(any(), any(), any(), any());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Map<String, Object>> captor() { return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class); }

    private static Map<String, Object> stored(boolean enabled, String updatedAt) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 5); row.put("roleCode", "sales"); row.put("projectId", null); row.put("projectCode", null);
        row.put("targetKind", "TOOL"); row.put("targetName", "create_customer"); row.put("permission", "ALLOW"); row.put("note", null);
        row.put("enabled", enabled); row.put("updatedAt", updatedAt);
        return row;
    }
}
