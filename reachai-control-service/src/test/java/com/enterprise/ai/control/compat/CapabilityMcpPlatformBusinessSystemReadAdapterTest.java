package com.enterprise.ai.control.compat;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformBusinessSystemReadPort.Action;
import com.enterprise.ai.control.mcp.application.port.McpPlatformBusinessSystemReadPort.Change;
import com.enterprise.ai.control.mcp.application.port.McpPlatformBusinessSystemReadPort.Operation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class CapabilityMcpPlatformBusinessSystemReadAdapterTest {
    private final RestTemplate http = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.createServer(http);
    private final CapabilityCompatibilityProxyController proxy = spy(
            new CapabilityCompatibilityProxyController(http, "http://capability:18605"));
    private final CapabilityMcpPlatformBusinessSystemReadAdapter adapter = new CapabilityMcpPlatformBusinessSystemReadAdapter(
            proxy, new ObjectMapper());

    CapabilityMcpPlatformBusinessSystemReadAdapterTest() {
        // The proxy owns the verified id-to-projectCode lookup; these adapter tests exercise
        // its result and confirm no protected owner call is forwarded on denial.
        doAnswer(inv -> {
            PlatformAuthenticatedSession actor = inv.getArgument(0);
            long id = inv.getArgument(1);
            String permission = inv.getArgument(2);
            return actor.hasResourcePermission(permission, "PROJECT", null, id == 5 ? "orders" : "other");
        }).when(proxy).hasProjectPermission(any(), anyLong(), anyString());
    }

    @Test
    void projectDetailUsesTheConsoleSanitizationAndNeverReturnsTheStoredKey() {
        server.expect(requestTo("http://capability:18605/api/scan-projects/5"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":5,\"projectCode\":\"orders\",\"authApiKeyValue\":\"secret\"}"));
        var body = adapter.read(actor("platform:read"), Operation.PROJECT, 5L, null, Map.of()).getBody();
        assertTrue(body.toString().contains("orders"));
        assertFalse(body.toString().contains("secret"));
        server.verify();
    }

    @Test
    void deniesWithoutTheConsolePermissionAndRejectsUndeclaredQueries() {
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> adapter.read(actor("workflow:read"), Operation.TOOLS, 5L, null, Map.of("view", "summary"))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> adapter.read(actor("platform:read"), Operation.TOOLS, 5L, null, Map.of("projectCode", "other"))).getStatusCode());
        server.expect(requestTo("http://capability:18605/api/scan-projects/5/operation-blockers?operation=DELETE"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertEquals(404, adapter.read(actor("platform:read"), Operation.OPERATION_BLOCKERS, 5L, null, Map.of("operation", "DELETE"))
                .getStatusCode().value());
        server.verify();
    }

    @Test
    void scanActionsNeedPlatformWriteHideOwnerErrorsAndReportLostResultsAsUnknown() {
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> adapter.act(actor("platform:read"), Action.RESCAN, 5L, null, Map.of())).getStatusCode());
        server.expect(requestTo("http://capability:18605/api/scan-projects/5/rescan"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"/srv/scanner/private path\"}"));
        server.expect(requestTo("http://capability:18605/api/scan-projects/5/tools/reconcile"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo("http://capability:18605/api/scan-projects/5/sdk-access-check"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{\"ready\":true}"));
        var blocked = adapter.act(actor("platform:write"), Action.RESCAN, 5L, null, Map.of());
        assertEquals(409, blocked.getStatusCode().value());
        assertEquals(Map.of("code", "BUSINESS_SYSTEM_OPERATION_BLOCKED"), blocked.getBody());
        var lost = adapter.act(actor("platform:write"), Action.RECONCILE_TOOLS, 5L, null, Map.of());
        assertEquals(202, lost.getStatusCode().value());
        assertTrue(lost.getBody().toString().contains("UNKNOWN"));
        assertTrue(adapter.act(actor("platform:write"), Action.SDK_ACCESS_CHECK, 5L, null, Map.of()).getBody().toString().contains("ready"));
        server.verify();
    }

    @Test
    void sourceRescanAndSensitiveScanCarryOnlyTheirDeclaredArguments() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> adapter.act(actor("platform:write"), Action.TOOL_RESCAN_FROM_SOURCE, 5L, null, Map.of())).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> adapter.act(actor("platform:write"), Action.SENSITIVE_DATA_SCAN, 5L, null, Map.of("projectCode", "other"))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> adapter.act(actor("platform:write"), Action.RESCAN, 5L, 9L, Map.of())).getStatusCode());
        server.expect(requestTo("http://capability:18605/api/scan-projects/5/scan-tools/9/rescan-from-source"))
                .andRespond(withStatus(HttpStatus.CONFLICT));
        server.expect(requestTo("http://capability:18605/api/scan-projects/5/sensitive-data/scan?modelInstanceId=m-1"))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body("{\"taskId\":\"t-1\"}"));
        assertEquals(Map.of("code", "BUSINESS_SYSTEM_OPERATION_BLOCKED"),
                adapter.act(actor("platform:write"), Action.TOOL_RESCAN_FROM_SOURCE, 5L, 9L, Map.of()).getBody());
        assertTrue(adapter.act(actor("platform:write"), Action.SENSITIVE_DATA_SCAN, 5L, null, Map.of("modelInstanceId", "m-1"))
                .getBody().toString().contains("t-1"));
        server.verify();
    }

    @Test
    void configurationWritesUseTheConsoleMethodAndStripTheStoredKey() {
        assertFalse(adapter.admits(actor("platform:read"), Change.UPDATE, 5L));
        assertTrue(adapter.admits(actor("platform:write"), Change.CREATE, null));
        server.expect(requestTo("http://capability:18605/api/scan-projects/5"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":5,\"name\":\"orders\",\"authApiKeyValue\":\"secret\"}"));
        server.expect(requestTo("http://capability:18605/api/scan-projects/5"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body("{\"blocked\":true}"));
        var updated = adapter.change(actor("platform:write"), Change.UPDATE, 5L, Map.of("name", "orders"));
        assertTrue(updated.getBody().toString().contains("orders"));
        assertFalse(updated.getBody().toString().contains("secret"));
        assertEquals(Map.of("code", "BUSINESS_SYSTEM_OPERATION_BLOCKED"), adapter.change(actor("platform:write"), Change.DELETE, 5L, null).getBody());
        server.verify();
    }

    @Test
    void projectScopedActorCannotReadActOrChangeAnotherProjectOrCreateGlobalProject() {
        var reader = scopedActor("platform:read", "orders");
        var writer = scopedActor("platform:write", "orders");
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> adapter.read(reader, Operation.TOOLS, 6L, null, Map.of())).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> adapter.act(writer, Action.RESCAN, 6L, null, Map.of())).getStatusCode());
        assertFalse(adapter.admits(writer, Change.UPDATE, 6L));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> adapter.change(writer, Change.DELETE, 6L, null)).getStatusCode());
        assertFalse(adapter.admits(writer, Change.CREATE, null));
        server.expect(requestTo("http://capability:18605/api/scan-projects/5/tools"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("[]"));
        assertEquals(HttpStatus.OK, adapter.read(reader, Operation.TOOLS, 5L, null, Map.of()).getStatusCode());
        server.verify();
    }

    private static PlatformAuthenticatedSession actor(String permission) {
        var user = new PlatformUserEntity();
        user.setId(7L);
        var grants = List.of(new PlatformPermissionGrant(permission, "GLOBAL", "*"));
        return new PlatformAuthenticatedSession(PlatformPrincipal.fromUser(user), "pai_19", LocalDateTime.now().plusDays(1),
                List.of(), List.of(permission), grants);
    }

    private static PlatformAuthenticatedSession scopedActor(String permission, String projectCode) {
        var user = new PlatformUserEntity();
        user.setId(7L);
        return new PlatformAuthenticatedSession(PlatformPrincipal.fromUser(user), "pai_19", LocalDateTime.now().plusDays(1),
                List.of(), List.of(permission), List.of(new PlatformPermissionGrant(permission, "PROJECT", projectCode)));
    }
}
