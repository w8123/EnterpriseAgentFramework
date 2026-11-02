package com.enterprise.ai.control.knowledge;

import com.enterprise.ai.control.compat.KnowledgeModelConsoleProxyController;
import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeCatalogPort.Operation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KnowledgeConsoleCatalogServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final KnowledgeBizIndexGateway gateway = mock(KnowledgeBizIndexGateway.class);
    private final KnowledgeConsoleCatalogService service = new KnowledgeConsoleCatalogService(gateway, json);

    @Test void catalogCountsAndRowsFollowActualProjectAndWorkspaceScopes() throws Exception {
        owner(List.of(resource("PROJECT", "orders"), resource("PROJECT", "finance"), resource("WORKSPACE", null), resource("SHARED", null)), null);
        JsonNode visible = (JsonNode) service.read(actor("PROJECT", "orders"), Operation.BASES, Map.of()).getBody();
        assertEquals(1, visible.size());
        assertEquals("orders", visible.get(0).path("projectCode").asText());
        assertEquals(3, ((JsonNode) service.read(actor("WORKSPACE", "team"), Operation.BASES, Map.of()).getBody()).size());
        assertEquals(4, ((JsonNode) service.read(actor("GLOBAL", "*"), Operation.BASES, Map.of()).getBody()).size());
    }

    @Test void ownerScopeControlsContentAndCallerCannotSupplyAnOperationOrIdentity() throws Exception {
        owner(List.of(Map.of("content", "private-document")), resource("PROJECT", "finance"));
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> service.read(actor("PROJECT", "orders"), Operation.FILES, Map.of("kbCode", "orders"))).getStatusCode().value());
        assertThrows(ResponseStatusException.class, () -> service.read(actor("GLOBAL", "*"), Operation.BASES, Map.of("operation", "FILES")));
        assertThrows(ResponseStatusException.class, () -> service.read(actor("PROJECT", "orders"), Operation.DEPENDENCIES, Map.of()));
        owner(List.of(Map.of("fileId", "f1")), resource("PROJECT", "orders"));
        assertEquals(200, service.read(actor("PROJECT", "orders"), Operation.FILES, Map.of("kbCode", "orders")).getStatusCode().value());
        var body = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(gateway, times(2)).exchange(eq("POST"), eq(KnowledgeBizIndexGateway.CATALOG_INTERNAL_ROOT + "/read"), eq("default"), eq("7"), body.capture(), eq("application/json"));
        assertEquals(Map.of("operation", "FILES", "kbCode", "orders"), json.readValue(body.getValue(), Map.class));
    }

    @Test void missingInvalidOrLegacyOwnerScopeCannotBeReportedAsSuccessfulEmptyContent() throws Exception {
        owner(List.of(), Map.of("scope", "PROJECT", "workspaceId", "team"));
        assertEquals(502, service.read(actor("GLOBAL", "*"), Operation.FILES, Map.of("kbCode", "orders")).getStatusCode().value());
        owner(List.of(Map.of("name", "legacy-without-scope")), null);
        assertEquals(502, service.read(actor("GLOBAL", "*"), Operation.BASES, Map.of()).getStatusCode().value());
        when(gateway.exchange(any(), any(), any(), any(), any(), any())).thenReturn(
                new KnowledgeBizIndexGateway.GatewayResponse(200, "application/json", "{\"code\":200,\"data\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(502, service.read(actor("GLOBAL", "*"), Operation.BASES, Map.of()).getStatusCode().value());
    }

    @Test void browserExactMappingsShareTheBoundaryAndKeepTheExistingApiEnvelope() throws Exception {
        owner(List.of(Map.of("fileId", "f1")), resource("PROJECT", "orders"));
        var compatibility = mock(KnowledgeModelConsoleProxyController.class);
        var controller = new KnowledgeConsoleCatalogController(service,
                new PlatformRequestAuthorization(new PlatformAuthorizationService(null, null, null, null)));
        var mvc = MockMvcBuilders.standaloneSetup(controller, compatibility).build();
        mvc.perform(get("/ai/knowledge/kb/orders/files").requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, actor("PROJECT", "orders")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data[0].fileId").value("f1"));
        mvc.perform(get("/ai/knowledge/kb/orders/files").requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, actor("PROJECT", "finance")))
                .andExpect(status().isForbidden()).andExpect(content().string(""));
        mvc.perform(get("/ai/knowledge/kb/orders/files")).andExpect(status().isUnauthorized());
        verifyNoInteractions(compatibility);
    }

    @Test void chunkWritesNeedKnowledgeWriteOnTheOwnerSignedResource() throws Exception {
        owner(List.of(Map.of("id", 7, "content", "第一段")), resource("PROJECT", "orders"));
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> service.fileChunksForWrite(actor("PROJECT", "orders"), "f1")).getStatusCode().value());
        var writer = new PlatformAuthenticatedSession(new PlatformPrincipal(7L, "builder", "Builder"), "pai-fixture", LocalDateTime.now().plusHours(1),
                List.of("BUILDER"), List.of("knowledge:read", "knowledge:write"),
                List.of(new PlatformPermissionGrant("knowledge:read", "PROJECT", "orders"), new PlatformPermissionGrant("knowledge:write", "PROJECT", "orders")));
        var body = (Map<?, ?>) service.fileChunksForWrite(writer, "f1").getBody();
        assertEquals("orders", ((Map<?, ?>) body.get("resource")).get("projectCode"));
        assertEquals(1, ((JsonNode) body.get("chunks")).size());
        owner(List.of(Map.of("id", 7)), resource("PROJECT", "finance"));
        assertThrows(ResponseStatusException.class, () -> service.fileChunksForWrite(writer, "f1"));
    }

    private void owner(Object data, Map<String, Object> resource) throws Exception {
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("contractVersion", 1); payload.put("data", data); payload.put("resource", resource);
        when(gateway.exchange(any(), any(), any(), any(), any(), any())).thenReturn(
                new KnowledgeBizIndexGateway.GatewayResponse(200, "application/json", json.writeValueAsBytes(Map.of("code", 200, "data", payload))));
    }
    private Map<String, Object> resource(String scope, String project) {
        var resource = new java.util.LinkedHashMap<String, Object>();
        resource.put("scope", scope); resource.put("workspaceId", "team");
        if (project != null) resource.put("projectCode", project);
        return resource;
    }
    private PlatformAuthenticatedSession actor(String type, String value) {
        return new PlatformAuthenticatedSession(new PlatformPrincipal(7L, "builder", "Builder"), "pai-fixture", LocalDateTime.now().plusHours(1),
                List.of("BUILDER"), List.of("knowledge:read"), List.of(new PlatformPermissionGrant("knowledge:read", type, value)));
    }
}
