package com.enterprise.ai.control.knowledge;

import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformAuthorizationService;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeDocumentImportControllerTest {

    private final KnowledgeBizIndexGateway gateway = mock(KnowledgeBizIndexGateway.class);
    private final PlatformAuthorizationService authorization = mock(PlatformAuthorizationService.class);
    private final PlatformAuthAuditService audit = mock(PlatformAuthAuditService.class);
    private final KnowledgeDocumentImportController controller = new KnowledgeDocumentImportController(
            gateway, authorization, audit, new ObjectMapper(), "default");

    @Test
    void submitResolvesScopeRequiresPermissionAndForwardsSignedAssertion() {
        MockHttpServletRequest request = authenticatedRequest();
        String scopePath = KnowledgeBizIndexGateway.DOCUMENT_IMPORT_INTERNAL_ROOT
                + "/knowledge-bases/contracts/scope";
        when(gateway.exchange("GET", scopePath, "default", "42", null, null))
                .thenReturn(json("{\"code\":200,\"data\":{\"workspaceId\":\"workspace-a\","
                        + "\"projectCode\":\"orders\",\"scope\":\"PROJECT\"}}"));
        when(gateway.exchangeDocumentMultipart(anyString(), eq("default"), eq("42"), any(), any()))
                .thenReturn(json("{\"code\":200,\"data\":{\"jobId\":\"dij_test\"}}"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "contract.pdf", "application/pdf", "pdf".getBytes(StandardCharsets.UTF_8));

        var response = controller.submit(
                request, file, "contracts", "fixed_length", 500, 50, "{}");

        assertEquals(200, response.getStatusCode().value());
        verify(authorization).requireResourcePermission(
                any(), eq("platform:write"), eq("PROJECT"), eq("workspace-a"), eq("orders"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> fields = ArgumentCaptor.forClass(Map.class);
        verify(gateway).exchangeDocumentMultipart(
                eq(KnowledgeBizIndexGateway.DOCUMENT_IMPORT_INTERNAL_ROOT + "/jobs"),
                eq("default"), eq("42"), eq(file), fields.capture());
        assertEquals("workspace-a", fields.getValue().get("workspaceId"));
        assertEquals("orders", fields.getValue().get("projectCode"));
        assertEquals("PROJECT", fields.getValue().get("resourceScope"));
        assertEquals("false", fields.getValue().get("autoCommit"));
    }

    @Test
    void unsignedControllerCallCannotReachKnowledge() {
        ResponseStatusException failure = assertThrows(ResponseStatusException.class, () -> controller.get(
                new MockHttpServletRequest(), "dij_test", false));

        assertEquals(401, failure.getStatusCode().value());
        verify(gateway, never()).exchange(anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    private static KnowledgeBizIndexGateway.GatewayResponse json(String body) {
        return new KnowledgeBizIndexGateway.GatewayResponse(
                200, "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    private static MockHttpServletRequest authenticatedRequest() {
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(42L);
        PlatformAuthenticatedSession session = new PlatformAuthenticatedSession(
                user,
                "pls_test",
                LocalDateTime.now().plusHours(1),
                List.of("PROJECT_OWNER"),
                List.of("platform:read", "platform:write"),
                List.of(new PlatformPermissionGrant("platform:write", "PROJECT", "orders")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session);
        return request;
    }
}
