package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.port.McpPlatformBusinessSystemReadPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformBusinessSystemReadPort.Change;
import com.enterprise.ai.control.mcp.application.port.McpPlatformBusinessSystemReadPort.Operation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class McpPlatformBusinessSystemChangeServiceTest {
    private final McpPlatformBusinessSystemReadPort systems = mock(McpPlatformBusinessSystemReadPort.class);
    private final PlatformAiApprovalService approvals = mock(PlatformAiApprovalService.class);
    private final McpPlatformBusinessSystemChangeService service = new McpPlatformBusinessSystemChangeService(systems, approvals, new ObjectMapper());
    private final PlatformAiConnectionService.ConnectionIdentity actor = new PlatformAiConnectionService.ConnectionIdentity(19L, "CODEX",
            new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "操作员"), "session", null, List.of(), List.of(), List.of()));

    @Test void updateKeepsFieldsTheCallerLeftOutAndRefusesWithoutConsoleAccess() {
        when(systems.admits(actor.session(), Change.UPDATE, 5L)).thenReturn(false);
        assertEquals(403, service.request(actor, Change.UPDATE, 5L, Map.of("name", "订单"), "00000000-0000-0000-0000-000000000001").getStatusCode().value());
        verifyNoInteractions(approvals);

        when(systems.admits(actor.session(), Change.UPDATE, 5L)).thenReturn(true);
        doReturn(ResponseEntity.ok(project("http://orders", false))).when(systems).read(actor.session(), Operation.PROJECT, 5L, null, Map.of());
        service.request(actor, Change.UPDATE, 5L, Map.of("baseUrl", "http://orders-v2"), "00000000-0000-0000-0000-000000000001");
        var frozen = frozen();
        @SuppressWarnings("unchecked") var after = (Map<String, Object>) frozen.get("after");
        assertEquals("订单系统", after.get("name"));
        assertEquals("http://orders-v2", after.get("baseUrl"));
        assertEquals("PROJECT", after.get("visibility"));
        assertEquals(400, service.request(actor, Change.UPDATE, 5L, Map.of("specContent", "openapi: 3"), "00000000-0000-0000-0000-000000000002").getStatusCode().value());
    }

    @Test void changedSystemIsRefusedBeforeDispatchAndApprovedChangeRunsOnce() {
        when(systems.admits(any(), any(), any())).thenReturn(true);
        doReturn(ResponseEntity.ok(project("http://orders", false))).when(systems).read(actor.session(), Operation.PROJECT, 5L, null, Map.of());
        service.request(actor, Change.UPDATE, 5L, Map.of("baseUrl", "http://orders-v2"), "00000000-0000-0000-0000-000000000001");
        claim("BUSINESS_SYSTEM:5", frozen());

        doReturn(ResponseEntity.ok(project("http://changed", false))).when(systems).read(actor.session(), Operation.PROJECT, 5L, null, Map.of());
        assertEquals(409, service.execute(actor, "approval", null).getStatusCode().value());
        verify(approvals).finish(actor, "approval", "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
        verify(systems, never()).change(any(), any(), any(), any());

        doReturn(ResponseEntity.ok(project("http://orders", false))).when(systems).read(actor.session(), Operation.PROJECT, 5L, null, Map.of());
        doReturn(ResponseEntity.ok(Map.of("id", 5, "name", "订单系统"))).when(systems).change(eq(actor.session()), eq(Change.UPDATE), eq(5L), any());
        assertEquals(200, service.execute(actor, "approval", null).getStatusCode().value());
        verify(approvals).finish(actor, "approval", "SUCCEEDED", null);

        doReturn(ResponseEntity.status(503).build()).when(systems).change(eq(actor.session()), eq(Change.UPDATE), eq(5L), any());
        assertEquals(202, service.execute(actor, "approval", null).getStatusCode().value());
        verify(approvals).finish(actor, "approval", "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        verify(systems, times(2)).change(any(), any(), any(), any());
    }

    @Test void credentialApprovalKeepsOnlyAFingerprintAndNeedsTheSameSecretToRun() {
        when(systems.admits(any(), any(), any())).thenReturn(true);
        doReturn(ResponseEntity.ok(project("http://orders", false))).when(systems).read(actor.session(), Operation.PROJECT, 5L, null, Map.of());
        service.requestCredential(actor, 5L, "orders-app", "s3cret-value", "00000000-0000-0000-0000-000000000003");
        var frozen = frozen();
        assertFalse(frozen.toString().contains("s3cret-value"));
        assertTrue(String.valueOf(((Map<?, ?>) frozen.get("after")).get("appSecretSha256")).matches("[0-9a-f]{64}"));
        claim("BUSINESS_SYSTEM:5", frozen);

        assertEquals(400, service.execute(actor, "approval", "another-secret").getStatusCode().value());
        verify(systems, never()).change(any(), any(), any(), any());

        doReturn(ResponseEntity.ok(Map.of("id", 5, "registryCredentialConfigured", true)))
                .when(systems).change(actor.session(), Change.REGISTRY_CREDENTIAL, 5L, Map.of("appKey", "orders-app", "appSecret", "s3cret-value"));
        assertEquals(200, service.execute(actor, "approval", "s3cret-value").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "SUCCEEDED", null);
    }

    @Test void blockedDeleteFailsWithTheStableCode() {
        when(systems.admits(any(), any(), any())).thenReturn(true);
        doReturn(ResponseEntity.ok(project("http://orders", true))).when(systems).read(actor.session(), Operation.PROJECT, 5L, null, Map.of());
        service.request(actor, Change.DELETE, 5L, null, "00000000-0000-0000-0000-000000000004");
        claim("BUSINESS_SYSTEM:5", frozen());
        doReturn(ResponseEntity.status(409).body(Map.of("code", "BUSINESS_SYSTEM_OPERATION_BLOCKED")))
                .when(systems).change(actor.session(), Change.DELETE, 5L, null);
        var blocked = service.execute(actor, "approval", null);
        assertEquals(409, blocked.getStatusCode().value());
        assertEquals(Map.of("code", "BUSINESS_SYSTEM_OPERATION_BLOCKED"), blocked.getBody());
        verify(approvals).finish(actor, "approval", "FAILED", "PLATFORM_APPROVAL_BUSINESS_SYSTEM_REJECTED");
    }

    private Map<String, Object> frozen() {
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("BUSINESS_SYSTEM_CHANGE"), eq("BUSINESS_SYSTEM:5"), frozen.capture());
        return frozen.getValue();
    }

    private void claim(String subject, Map<String, Object> frozen) {
        when(approvals.claim(actor, "approval", "BUSINESS_SYSTEM_CHANGE"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "BUSINESS_SYSTEM_CHANGE", subject, frozen));
    }

    private static Map<String, Object> project(String baseUrl, boolean credential) {
        var project = new LinkedHashMap<String, Object>();
        project.put("id", 5); project.put("name", "订单系统"); project.put("projectCode", "orders"); project.put("projectKind", "SCAN");
        project.put("environment", "prod"); project.put("owner", "张三"); project.put("visibility", "PROJECT"); project.put("baseUrl", baseUrl);
        project.put("contextPath", "/api"); project.put("specFile", "openapi.yaml"); project.put("openapiDocumentUploaded", true);
        project.put("authType", "none"); project.put("registryCredentialConfigured", credential);
        project.put("scanSettings", Map.of("defaultFlags", Map.of("enabled", true)));
        return project;
    }
}
