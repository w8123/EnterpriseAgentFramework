package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.platform.McpPlatformModelAccountService.Operation;
import com.enterprise.ai.control.mcp.application.port.McpPlatformModelWritePort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformModelWritePort.AccountOperation;
import com.enterprise.ai.control.mcp.application.port.McpPlatformModelWritePort.CatalogOperation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class McpPlatformModelAccountServiceTest {
    private final McpPlatformModelWritePort models = mock(McpPlatformModelWritePort.class);
    private final PlatformAiApprovalService approvals = mock(PlatformAiApprovalService.class);
    private final McpPlatformModelAccountService service = new McpPlatformModelAccountService(models, approvals, new ObjectMapper());
    private final PlatformAiConnectionService.ConnectionIdentity actor = identity(new PlatformPermissionGrant("model:manage", "GLOBAL", "*"));

    @Test void projectManagersCannotTouchPlatformAccountsOrTheCatalog() {
        var projectManager = identity(new PlatformPermissionGrant("model:manage", "PROJECT", "orders"));
        assertEquals(403, service.request(projectManager, Operation.DELETE, "acc-1", null, null, "00000000-0000-0000-0000-000000000001").getStatusCode().value());
        assertEquals(403, service.discoverSaved(projectManager, "acc-1").getStatusCode().value());
        assertEquals(403, service.catalogSync(projectManager).getStatusCode().value());
        assertEquals(403, service.catalogSettings(projectManager, true, null).getStatusCode().value());
        verifyNoInteractions(models, approvals);
    }

    @Test void newKeyIsFrozenAsAFingerprintAndMustBePresentedAgain() {
        doReturn(ResponseEntity.ok(account(0))).when(models).account(AccountOperation.GET, "acc-1", Map.of(), null);
        service.request(actor, Operation.UPDATE, "acc-1", Map.of("connection", Map.of("baseUrl", "https://api.example.com", "apiKey", "sk-rotated")),
                null, "00000000-0000-0000-0000-000000000002");
        var frozen = frozen();
        assertFalse(frozen.toString().contains("sk-rotated"));
        claim(frozen);

        assertEquals(400, service.execute(actor, "approval", Map.of("apiKey", "sk-wrong")).getStatusCode().value());
        verify(models, never()).account(eq(AccountOperation.UPDATE), any(), any(), any());

        doReturn(ResponseEntity.ok(Map.of("id", "acc-1", "name", "DeepSeek", "connection", Map.of("apiKey", "sk-rotated"))))
                .when(models).account(eq(AccountOperation.UPDATE), eq("acc-1"), eq(Map.of()), any());
        var done = service.execute(actor, "approval", Map.of("apiKey", "sk-rotated"));
        assertEquals(200, done.getStatusCode().value());
        assertFalse(String.valueOf(done.getBody()).contains("sk-rotated"));
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(models).account(eq(AccountOperation.UPDATE), eq("acc-1"), eq(Map.of()), body.capture());
        assertEquals(Map.of("baseUrl", "https://api.example.com", "apiKey", "sk-rotated"), ((Map<?, ?>) body.getValue()).get("connection"));
    }

    @Test void accountInUseCannotBeDeletedAndDriftIsRefused() {
        doReturn(ResponseEntity.ok(account(2))).when(models).account(AccountOperation.GET, "acc-1", Map.of(), null);
        var refused = service.request(actor, Operation.DELETE, "acc-1", null, null, "00000000-0000-0000-0000-000000000003");
        assertEquals(Map.of("code", "MODEL_PROVIDER_ACCOUNT_IN_USE"), refused.getBody());
        verifyNoInteractions(approvals);

        service.request(actor, Operation.CREATE_INSTANCES, "acc-1", null,
                List.of(Map.of("modelName", "deepseek-chat", "modelType", "LLM")), "00000000-0000-0000-0000-000000000004");
        claim(frozen());
        doReturn(ResponseEntity.ok(account(3))).when(models).account(AccountOperation.GET, "acc-1", Map.of(), null);
        assertEquals(409, service.execute(actor, "approval", null).getStatusCode().value());
        verify(approvals).finish(actor, "approval", "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
        verify(models, never()).account(eq(AccountOperation.CREATE_INSTANCES), any(), any(), any());
    }

    @Test void unconfirmedCreationIsUnknownAndSyncDoesNotRetry() {
        doReturn(ResponseEntity.ok(account(0))).when(models).account(AccountOperation.GET, "acc-1", Map.of(), null);
        service.request(actor, Operation.CREATE_INSTANCES, "acc-1", null,
                List.of(Map.of("modelName", "deepseek-chat", "modelType", "LLM")), "00000000-0000-0000-0000-000000000005");
        claim(frozen());
        doReturn(ResponseEntity.status(503).build()).when(models).account(eq(AccountOperation.CREATE_INSTANCES), eq("acc-1"), eq(Map.of()), any());
        assertEquals(202, service.execute(actor, "approval", null).getStatusCode().value());
        verify(approvals).finish(actor, "approval", "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");

        doReturn(ResponseEntity.status(503).build()).when(models).catalog(CatalogOperation.SYNC, null);
        assertEquals(202, service.catalogSync(actor).getStatusCode().value());
        verify(models, times(1)).catalog(CatalogOperation.SYNC, null);
        assertEquals(400, service.request(actor, Operation.CREATE_INSTANCES, "acc-1", null,
                List.of(Map.of("modelName", "x", "modelType", "IMAGE")), "00000000-0000-0000-0000-000000000006").getStatusCode().value());
    }

    private Map<String, Object> frozen() {
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("MODEL_PROVIDER_ACCOUNT_CHANGE"), eq("MODEL_PROVIDER_ACCOUNT:acc-1"), frozen.capture());
        return frozen.getValue();
    }

    private void claim(Map<String, Object> frozen) {
        when(approvals.claim(actor, "approval", "MODEL_PROVIDER_ACCOUNT_CHANGE"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "MODEL_PROVIDER_ACCOUNT_CHANGE", "MODEL_PROVIDER_ACCOUNT:acc-1", frozen));
    }

    private static PlatformAiConnectionService.ConnectionIdentity identity(PlatformPermissionGrant grant) {
        return new PlatformAiConnectionService.ConnectionIdentity(19L, "CODEX",
                new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "操作员"), "session", null, List.of(), List.of(), List.of(grant)));
    }

    private static Map<String, Object> account(int instances) {
        var account = new LinkedHashMap<String, Object>();
        account.put("id", "acc-1"); account.put("name", "DeepSeek"); account.put("provider", "deepseek");
        account.put("connection", Map.of("baseUrl", "https://api.deepseek.com", "apiKey", "******")); account.put("apiKeyConfigured", true);
        account.put("status", "ACTIVE"); account.put("instanceCount", instances);
        return account;
    }
}
