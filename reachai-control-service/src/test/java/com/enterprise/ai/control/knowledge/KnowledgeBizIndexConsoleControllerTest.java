package com.enterprise.ai.control.knowledge;

import com.enterprise.ai.control.identity.PlatformPrincipal;

import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformAuthorizationService;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBizIndexConsoleControllerTest {

    private final KnowledgeBizIndexGateway gateway = mock(KnowledgeBizIndexGateway.class);
    private final PlatformAuthorizationService authorization = mock(PlatformAuthorizationService.class);
    private final PlatformAuthAuditService audit = mock(PlatformAuthAuditService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KnowledgeBizIndexConsoleController controller = new KnowledgeBizIndexConsoleController(
            gateway, authorization, audit, objectMapper, "default");

    @Test
    void searchRequiresReadPermissionAndUsesSignedInternalPath() throws Exception {
        PlatformAuthenticatedSession session = session(42L);
        MockHttpServletRequest request = request(session);
        byte[] downstream = "{\"code\":200,\"data\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(gateway.exchange(eq("POST"),
                eq(KnowledgeBizIndexGateway.INTERNAL_ROOT + "/orders/search"),
                eq("default"), eq("42"), any(byte[].class), eq("application/json")))
                .thenReturn(new KnowledgeBizIndexGateway.GatewayResponse(
                        200, "application/json", downstream));

        var response = controller.search(
                request,
                "orders",
                objectMapper.readTree("{\"query\":\"sensitive query is not audited\"}"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        verify(authorization).requireGlobalPermission(session,
                KnowledgeBizIndexConsoleController.READ_PERMISSION);
        verify(audit).record(eq(session), eq("KNOWLEDGE_BIZ_INDEX_SEARCH_REQUESTED"),
                eq("KNOWLEDGE_BUSINESS_INDEX"), any(), eq(java.util.Map.of("indexCode", "orders")));
    }

    @Test
    void createRequiresWritePermission() throws Exception {
        PlatformAuthenticatedSession session = session(7L);
        MockHttpServletRequest request = request(session);
        when(gateway.exchange(eq("POST"), eq(KnowledgeBizIndexGateway.INTERNAL_ROOT),
                eq("default"), eq("7"), any(byte[].class), eq("application/json")))
                .thenReturn(new KnowledgeBizIndexGateway.GatewayResponse(
                        200, "application/json", new byte[0]));

        controller.create(request, objectMapper.readTree("{\"indexCode\":\"contracts\"}"));

        verify(authorization).requireGlobalPermission(session,
                KnowledgeBizIndexConsoleController.WRITE_PERMISSION);
    }

    @Test
    void doesNotTrustControllerCallWithoutAuthenticatedSessionAttribute() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();

        org.springframework.web.server.ResponseStatusException failure = assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> controller.search(request, "orders", objectMapper.readTree("{}")));

        assertEquals(401, failure.getStatusCode().value());
    }

    private static MockHttpServletRequest request(PlatformAuthenticatedSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session);
        return request;
    }

    private static PlatformAuthenticatedSession session(long userId) {
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(userId);
        return new PlatformAuthenticatedSession(
                PlatformPrincipal.fromUser(user),
                "pls_test",
                LocalDateTime.now().plusHours(1),
                List.of("admin"),
                List.of("*"),
                List.of());
    }
}
