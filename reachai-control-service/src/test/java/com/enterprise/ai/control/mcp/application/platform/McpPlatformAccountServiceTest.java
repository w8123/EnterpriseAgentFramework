package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.port.McpPlatformAccountPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformAccountPort.Operation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class McpPlatformAccountServiceTest {
    private final McpPlatformAccountPort port = mock(McpPlatformAccountPort.class);
    private final PlatformAiApprovalService approvals = mock(PlatformAiApprovalService.class);
    private final McpPlatformAccountService service = new McpPlatformAccountService(port, approvals, new ObjectMapper());
    private final PlatformAiConnectionService.ConnectionIdentity admin = identity(new PlatformPermissionGrant("platform:admin", "GLOBAL", "*"));

    @Test void readsAndChangesNeedGlobalPlatformAdmin() {
        var projectAdmin = identity(new PlatformPermissionGrant("platform:admin", "PROJECT", "orders"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.users(projectAdmin, null, 10));
        assertEquals(403, service.request(projectAdmin, McpPlatformAccountService.Change.REVOKE_SESSIONS, 7L, Map.of(), "u").getStatusCode().value());
        verifyNoInteractions(port, approvals);
    }

    @Test void neverChangesItsOwnersAccountOrARoleItsOwnerHolds() {
        stubDirectory("张三");
        var self = service.request(admin, McpPlatformAccountService.Change.REPLACE_GRANTS, 42L,
                Map.of("grants", List.of(Map.of("roleId", 1, "scopeType", "GLOBAL"))), "u");
        assertEquals(403, self.getStatusCode().value());
        assertEquals(Map.of("code", "PLATFORM_ACCOUNT_SELF_CHANGE_FORBIDDEN"), self.getBody());
        assertEquals(403, service.request(admin, McpPlatformAccountService.Change.UPDATE_ROLE, 1L, Map.of("roleName", "新名"), "u").getStatusCode().value());
        verifyNoInteractions(approvals);
    }

    @Test void updateFreezesTheAccountAndRefusesDriftBeforeDispatch() {
        stubDirectory("张三");
        service.request(admin, McpPlatformAccountService.Change.UPDATE_USER, 7L, Map.of("status", "DISABLED"), "inv");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(admin), eq("ACCOUNT_CHANGE"), eq("PLATFORM_USER:7"), frozen.capture());
        var before = (Map<?, ?>) frozen.getValue().get("before");
        assertFalse(before.containsKey("lastLoginAt"));
        assertFalse(before.containsKey("activeSessionCount"));
        assertEquals("DISABLED", ((Map<?, ?>) frozen.getValue().get("after")).get("status"));
        assertEquals("张三", ((Map<?, ?>) frozen.getValue().get("after")).get("displayName"));
        when(approvals.claim(admin, "approval", "ACCOUNT_CHANGE"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "ACCOUNT_CHANGE", "PLATFORM_USER:7", frozen.getValue()));

        stubDirectory("别人改过");
        assertEquals(409, service.execute(admin, "approval").getStatusCode().value());
        verify(approvals).finish(admin, "approval", "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
        verify(port, never()).call(any(), eq(Operation.UPDATE_USER), any(), any(), any());

        stubDirectory("张三");
        doReturn(ResponseEntity.ok(Map.of("id", 7))).when(port).call(any(), eq(Operation.UPDATE_USER), eq(7L), any(), any());
        assertEquals(200, service.execute(admin, "approval").getStatusCode().value());
        verify(approvals).finish(admin, "approval", "SUCCEEDED", null);

        doThrow(new RuntimeException("lost")).when(port).call(any(), eq(Operation.UPDATE_USER), eq(7L), any(), any());
        assertEquals(202, service.execute(admin, "approval").getStatusCode().value());
        verify(approvals).finish(admin, "approval", "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
    }

    @Test void grantsAreLabelledForReviewButDispatchedAsCommands() {
        stubDirectory("张三");
        service.request(admin, McpPlatformAccountService.Change.REPLACE_GRANTS, 7L,
                Map.of("grants", List.of(Map.of("roleId", 2, "scopeType", "PROJECT", "scopeValue", "orders"))), "inv");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(admin), eq("ACCOUNT_CHANGE"), eq("PLATFORM_USER:7"), frozen.capture());
        var grant = (Map<?, ?>) ((List<?>) ((Map<?, ?>) frozen.getValue().get("after")).get("grants")).get(0);
        assertEquals("DEVELOPER", grant.get("roleCode"));
        when(approvals.claim(admin, "approval", "ACCOUNT_CHANGE"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "ACCOUNT_CHANGE", "PLATFORM_USER:7", frozen.getValue()));
        doReturn(ResponseEntity.ok(List.of())).when(port).call(any(), eq(Operation.REPLACE_GRANTS), eq(7L), any(), any());
        service.execute(admin, "approval");
        verify(port).call(any(), eq(Operation.REPLACE_GRANTS), eq(7L), any(),
                eq(List.of(Map.of("roleId", 2, "scopeType", "PROJECT", "scopeValue", "orders"))));
        assertEquals(400, service.request(admin, McpPlatformAccountService.Change.REPLACE_GRANTS, 7L,
                Map.of("grants", List.of(Map.of("roleId", 99))), "u").getStatusCode().value());
    }

    private void stubDirectory(String displayName) {
        var other = new LinkedHashMap<String, Object>();
        other.put("id", 7); other.put("username", "dev01"); other.put("displayName", displayName); other.put("email", null);
        other.put("mobile", null); other.put("status", "ACTIVE"); other.put("lastLoginAt", UUID.randomUUID().toString());
        other.put("activeSessionCount", new Random().nextInt(5)); other.put("grants", List.of());
        var self = Map.of("id", 42, "username", "operator", "displayName", "操作员", "status", "ACTIVE",
                "grants", List.of(Map.of("roleId", 1, "roleCode", "PLATFORM_ADMIN", "scopeType", "GLOBAL", "scopeValue", "*")));
        doReturn(ResponseEntity.ok(List.of(self, other))).when(port).call(any(), eq(Operation.USERS), isNull(), any(), isNull());
        doReturn(ResponseEntity.ok(List.of(
                Map.of("id", 1, "roleCode", "PLATFORM_ADMIN", "roleName", "平台管理员", "status", "ACTIVE", "userCount", 1),
                Map.of("id", 2, "roleCode", "DEVELOPER", "roleName", "开发人员", "status", "ACTIVE", "userCount", 3))))
                .when(port).call(any(), eq(Operation.ROLES), isNull(), any(), isNull());
    }

    private static PlatformAiConnectionService.ConnectionIdentity identity(PlatformPermissionGrant grant) {
        return new PlatformAiConnectionService.ConnectionIdentity(19L, "CODEX", new PlatformAuthenticatedSession(
                new PlatformPrincipal(42L, "operator", "操作员"), "session", null, List.of(), List.of(), List.of(grant)));
    }
}
