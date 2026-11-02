package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.port.McpPlatformModelCatalogPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformModelWritePort;
import com.enterprise.ai.control.mcp.application.platform.McpPlatformModelChangeService.Operation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class McpPlatformModelChangeServiceTest {
    private final McpPlatformModelCatalogPort catalog = mock(McpPlatformModelCatalogPort.class);
    private final McpPlatformModelWritePort models = mock(McpPlatformModelWritePort.class);
    private final PlatformAiApprovalService approvals = mock(PlatformAiApprovalService.class);
    private final McpPlatformModelChangeService service = new McpPlatformModelChangeService(catalog, models, approvals, new ObjectMapper());
    private final PlatformAiConnectionService.ConnectionIdentity actor = identity(List.of(new PlatformPermissionGrant("model:manage", "GLOBAL", "*")));

    @Test void draftsNeverNameASavedInstanceAndNeedManageOnTheirProject() {
        var draft = new java.util.HashMap<String, Object>(Map.of("provider", "openai", "modelType", "LLM", "modelName", "gpt",
                "connection", Map.of("baseUrl", "https://api.example", "apiKey", "sk-test")));
        draft.put("id", "m1");
        assertEquals(400, service.draft(actor, McpPlatformModelWritePort.DraftOperation.TEST, draft).getStatusCode().value());
        draft.remove("id");
        draft.put("projectCode", "orders");
        var projectReader = identity(List.of(new PlatformPermissionGrant("model:manage", "PROJECT", "finance")));
        assertEquals(403, service.draft(projectReader, McpPlatformModelWritePort.DraftOperation.DISCOVER, draft).getStatusCode().value());
        verify(models, never()).draft(any(), any());
        doReturn(ResponseEntity.ok(Map.of("success", true))).when(models).draft(eq(McpPlatformModelWritePort.DraftOperation.TEST), argThat(body -> !body.containsKey("projectCode")));
        assertEquals(200, service.draft(actor, McpPlatformModelWritePort.DraftOperation.TEST, draft).getStatusCode().value());
    }

    @Test void readersWithoutManageCannotTestOrRequest() {
        var reader = identity(List.of());
        doReturn(ResponseEntity.ok(Map.of("data", model("http://a", "ACTIVE")))).when(catalog).get(reader.session(), "m1");
        assertEquals(403, service.test(reader, "m1").getStatusCode().value());
        assertEquals(403, service.request(reader, Operation.ARCHIVE, "m1", null, "00000000-0000-0000-0000-000000000001").getStatusCode().value());
        assertEquals(403, service.request(reader, Operation.CREATE, null, Map.of("name", "新模型"), "00000000-0000-0000-0000-000000000002").getStatusCode().value());
        verifyNoInteractions(approvals, models);
    }

    @Test void newSecretIsFrozenAsAFingerprintAndMustBePresentedAgain() {
        doReturn(ResponseEntity.ok(Map.of("data", model("http://a", "ACTIVE")))).when(catalog).get(actor.session(), "m1");
        service.request(actor, Operation.UPDATE, "m1", Map.of("connection", Map.of("baseUrl", "http://b", "apiKey", "sk-new-value")),
                "00000000-0000-0000-0000-000000000003");
        var frozen = frozen();
        assertFalse(frozen.toString().contains("sk-new-value"));
        var after = (Map<?, ?>) frozen.get("after");
        assertEquals("对话模型", after.get("name"));
        assertTrue(String.valueOf(((Map<?, ?>) ((Map<?, ?>) after.get("connection")).get("apiKey")).get("secretSha256")).matches("[0-9a-f]{64}"));
        claim(frozen);

        assertEquals(400, service.execute(actor, "approval", Map.of("apiKey", "sk-other")).getStatusCode().value());
        verify(models, never()).change(any(), any(), any());

        doReturn(ResponseEntity.ok(Map.of("id", "m1", "name", "对话模型", "connection", Map.of("apiKey", "sk-new-value"))))
                .when(models).change(eq(McpPlatformModelWritePort.Operation.UPDATE), eq("m1"), any());
        var done = service.execute(actor, "approval", Map.of("apiKey", "sk-new-value"));
        assertEquals(200, done.getStatusCode().value());
        assertFalse(String.valueOf(done.getBody()).contains("sk-new-value"));
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(models).change(eq(McpPlatformModelWritePort.Operation.UPDATE), eq("m1"), body.capture());
        assertEquals(Map.of("baseUrl", "http://b", "apiKey", "sk-new-value"), body.getValue().get("connection"));
        verify(approvals).finish(actor, "approval", "SUCCEEDED", null);
    }

    @Test void maskedSecretKeepsTheStoredKeyAndDriftIsRefused() {
        doReturn(ResponseEntity.ok(Map.of("data", model("http://a", "ACTIVE")))).when(catalog).get(actor.session(), "m1");
        service.request(actor, Operation.UPDATE, "m1", Map.of("remark", "换个备注"), "00000000-0000-0000-0000-000000000004");
        var frozen = frozen();
        assertEquals("******", ((Map<?, ?>) ((Map<?, ?>) frozen.get("after")).get("connection")).get("apiKey"));
        claim(frozen);

        doReturn(ResponseEntity.ok(Map.of("data", model("http://changed", "ACTIVE")))).when(catalog).get(actor.session(), "m1");
        assertEquals(409, service.execute(actor, "approval", null).getStatusCode().value());
        verify(approvals).finish(actor, "approval", "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
        verify(models, never()).change(any(), any(), any());
        assertEquals(400, service.request(actor, Operation.UPDATE, "m1", Map.of("projectCode", "other"), "00000000-0000-0000-0000-000000000005")
                .getStatusCode().value());
    }

    @Test void archiveThatIsNotConfirmedIsUnknownAndNotRetried() {
        doReturn(ResponseEntity.ok(Map.of("data", model("http://a", "ACTIVE")))).when(catalog).get(actor.session(), "m1");
        service.request(actor, Operation.ARCHIVE, "m1", null, "00000000-0000-0000-0000-000000000006");
        claim(frozen());
        doReturn(ResponseEntity.status(503).build()).when(models).change(McpPlatformModelWritePort.Operation.ARCHIVE, "m1", Map.of());
        assertEquals(202, service.execute(actor, "approval", null).getStatusCode().value());
        verify(approvals).finish(actor, "approval", "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        verify(models, times(1)).change(any(), any(), any());
    }

    private Map<String, Object> frozen() {
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("MODEL_INSTANCE_CHANGE"), eq("MODEL_INSTANCE:m1"), frozen.capture());
        return frozen.getValue();
    }

    private void claim(Map<String, Object> frozen) {
        when(approvals.claim(actor, "approval", "MODEL_INSTANCE_CHANGE"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "MODEL_INSTANCE_CHANGE", "MODEL_INSTANCE:m1", frozen));
    }

    private static PlatformAiConnectionService.ConnectionIdentity identity(List<PlatformPermissionGrant> grants) {
        return new PlatformAiConnectionService.ConnectionIdentity(19L, "CODEX",
                new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "操作员"), "session", null, List.of(), List.of(), grants));
    }

    private static Map<String, Object> model(String baseUrl, String status) {
        var model = new LinkedHashMap<String, Object>();
        model.put("id", "m1"); model.put("name", "对话模型"); model.put("provider", "deepseek"); model.put("modelType", "LLM");
        model.put("modelName", "deepseek-chat"); model.put("protocol", "openai"); model.put("projectCode", null);
        model.put("connection", Map.of("baseUrl", baseUrl, "apiKey", "******")); model.put("status", status);
        return model;
    }
}
