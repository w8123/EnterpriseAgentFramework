package com.enterprise.ai.control.knowledge;

import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformAuthorizationService;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeMcpPlatformAdapterTest {
    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    @Test
    void usesGlobalConsoleAuthorizationAndReturnsStructuredJson() {
        var gateway = mock(KnowledgeBizIndexGateway.class);
        var audit = mock(PlatformAuthAuditService.class);
        var json = new ObjectMapper();
        var controller = new KnowledgeBizIndexConsoleController(gateway,
                new PlatformAuthorizationService(null, null, null, null), audit, json, "default");
        var adapter = new KnowledgeMcpPlatformAdapter(controller, new PlatformDelegatedRequestScope(), json);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        byte[] data = "{\"code\":200,\"data\":[{\"indexCode\":\"orders\"}]}".getBytes(StandardCharsets.UTF_8);
        when(gateway.exchange("GET", KnowledgeBizIndexGateway.INTERNAL_ROOT + "/list", "default", "42", null, null))
                .thenReturn(new KnowledgeBizIndexGateway.GatewayResponse(200, "application/json", data));

        var scoped = actor("PROJECT", "orders");
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> adapter.listBusinessIndexes(scoped)).getStatusCode());
        verifyNoInteractions(gateway);
        var global = actor("GLOBAL", "*");
        var response = adapter.listBusinessIndexes(global);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("orders", ((JsonNode) response.getBody()).path("data").get(0).path("indexCode").asText());
        verify(gateway).exchange("GET", KnowledgeBizIndexGateway.INTERNAL_ROOT + "/list", "default", "42", null, null);
    }

    @Test
    void searchDoesNotPutTheSearchTextInAuditAndRejectsMalformedOwnerResponses() {
        var gateway = mock(KnowledgeBizIndexGateway.class);
        var audit = mock(PlatformAuthAuditService.class);
        var json = new ObjectMapper();
        var controller = new KnowledgeBizIndexConsoleController(gateway,
                new PlatformAuthorizationService(null, null, null, null), audit, json, "default");
        var adapter = new KnowledgeMcpPlatformAdapter(controller, new PlatformDelegatedRequestScope(), json);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        when(gateway.exchange(eq("POST"), eq(KnowledgeBizIndexGateway.INTERNAL_ROOT + "/orders/search"),
                eq("default"), eq("42"), any(byte[].class), eq("application/json")))
                .thenReturn(new KnowledgeBizIndexGateway.GatewayResponse(200, "application/json", "not-json".getBytes(StandardCharsets.UTF_8)));
        var actor = actor("GLOBAL", "*");
        assertEquals(HttpStatus.BAD_GATEWAY, adapter.searchBusinessIndex(actor, "orders", Map.of("query", "private query")).getStatusCode());
        verify(audit).record(eq(actor), eq("KNOWLEDGE_BIZ_INDEX_SEARCH_REQUESTED"),
                eq("KNOWLEDGE_BUSINESS_INDEX"), any(), eq(Map.of("indexCode", "orders")));
    }

    private static PlatformAuthenticatedSession actor(String type, String value) {
        return new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "Operator"), "pai_19",
                LocalDateTime.now().plusDays(1), List.of("OPERATOR"), List.of("knowledge:read"),
                List.of(new PlatformPermissionGrant("knowledge:read", type, value)));
    }
}
