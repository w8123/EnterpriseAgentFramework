package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.port.McpPlatformSkillCatalogPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformSkillGovernancePort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformSkillGovernancePort.Operation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class McpPlatformSkillGovernanceServiceTest {
    private final McpPlatformSkillCatalogPort catalog = mock(McpPlatformSkillCatalogPort.class);
    private final McpPlatformSkillGovernancePort governance = mock(McpPlatformSkillGovernancePort.class);
    private final PlatformAiApprovalService approvals = mock(PlatformAiApprovalService.class);
    private final McpPlatformSkillGovernanceService service = new McpPlatformSkillGovernanceService(catalog, governance, approvals, new ObjectMapper());
    private final PlatformAiConnectionService.ConnectionIdentity actor = new PlatformAiConnectionService.ConnectionIdentity(19L, "CODEX",
            new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "操作员"), "session", null, List.of(), List.of(), List.of()));

    @Test void requestFreezesThePackageAndRefusesWithoutConsoleAccess() {
        doReturn(ResponseEntity.ok(detail("REVIEW_PENDING", "a", true))).when(catalog).get(actor.session(), 3L);
        service.request(actor, Operation.REVIEW, 3L, 8L, "APPROVE", "脚本已检查", "00000000-0000-0000-0000-000000000001");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("SKILL_VERSION_GOVERNANCE"), eq("SKILL:3:VERSION:8"), frozen.capture());
        assertEquals("a".repeat(64), frozen.getValue().get("sourceSha256"));
        assertEquals("APPROVE", frozen.getValue().get("decision"));

        doReturn(ResponseEntity.ok(detail("APPROVED", "a", false))).when(catalog).get(actor.session(), 3L);
        assertEquals(403, service.request(actor, Operation.PUBLISH, 3L, 8L, null, null, "00000000-0000-0000-0000-000000000002").getStatusCode().value());
        doReturn(ResponseEntity.ok(detail("REVIEW_PENDING", "a", true))).when(catalog).get(actor.session(), 3L);
        assertEquals(409, service.request(actor, Operation.PUBLISH, 3L, 8L, null, null, "00000000-0000-0000-0000-000000000003").getStatusCode().value());
        verify(approvals, times(1)).prepare(any(), any(), any(), any());
        verifyNoInteractions(governance);
    }

    @Test void changedPackageIsRefusedBeforeDispatch() {
        var frozen = freeze(Operation.PUBLISH, "APPROVED");
        doReturn(ResponseEntity.ok(detail("APPROVED", "b", true))).when(catalog).get(actor.session(), 3L);
        assertEquals(409, service.execute(actor, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
        verifyNoInteractions(governance);
        assertNotNull(frozen);
    }

    @Test void approvedTransitionRunsOnceAndAnUnconfirmedStateIsUnknown() {
        freeze(Operation.REVOKE, "PUBLISHED");
        doReturn(ResponseEntity.ok(Map.of("id", 8, "status", "REVOKED"))).when(governance).change(actor.session(), 3L, 8L, Operation.REVOKE, null, null, null);
        assertEquals(200, service.execute(actor, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "SUCCEEDED", null);

        doReturn(ResponseEntity.ok(Map.of("id", 8, "status", "PUBLISHED"))).when(governance).change(actor.session(), 3L, 8L, Operation.REVOKE, null, null, null);
        assertEquals(202, service.execute(actor, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        verify(governance, times(2)).change(any(), anyLong(), anyLong(), any(), any(), any(), any());
    }

    private Map<String, Object> freeze(Operation operation, String status) {
        doReturn(ResponseEntity.ok(detail(status, "a", true))).when(catalog).get(actor.session(), 3L);
        service.request(actor, operation, 3L, 8L, null, null, "00000000-0000-0000-0000-000000000001");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(any(), any(), any(), frozen.capture());
        when(approvals.claim(actor, "approval", "SKILL_VERSION_GOVERNANCE"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "SKILL_VERSION_GOVERNANCE", "SKILL:3:VERSION:8", frozen.getValue()));
        return frozen.getValue();
    }

    private static Map<String, Object> detail(String status, String digest, boolean allowed) {
        var skill = new LinkedHashMap<String, Object>();
        skill.put("id", 3); skill.put("publisher", "acme"); skill.put("name", "orders"); skill.put("displayName", "订单技能");
        skill.put("visibility", "PROJECT"); skill.put("projectCode", "orders"); skill.put("defaultVersionId", 7); skill.put("defaultVersion", "1.0.0");
        var version = new LinkedHashMap<String, Object>();
        version.put("id", 8); version.put("version", "1.1.0"); version.put("status", status); version.put("sourceType", "UPLOAD");
        version.put("sourceSha256", digest.repeat(64)); version.put("contentTreeSha256", "c".repeat(64)); version.put("hasScripts", true);
        version.put("riskReport", Map.of("level", "MEDIUM"));
        return Map.of("skill", skill, "versions", List.of(version), "actions", Map.of("canReview", allowed, "canPublish", allowed));
    }
}
