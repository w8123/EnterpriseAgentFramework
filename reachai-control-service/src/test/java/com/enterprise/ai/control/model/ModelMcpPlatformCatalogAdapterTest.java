package com.enterprise.ai.control.model;

import com.enterprise.ai.control.client.model.ControlModelCatalogClient;
import com.enterprise.ai.control.identity.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelMcpPlatformCatalogAdapterTest {
    private final ControlModelCatalogClient owner = mock(ControlModelCatalogClient.class);
    private final ModelMcpPlatformCatalogAdapter adapter = new ModelMcpPlatformCatalogAdapter(
            new ControlModelInstanceController(owner, new PlatformRequestAuthorization(new PlatformAuthorizationService(null, null, null, null))),
            new PlatformDelegatedRequestScope());
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @BeforeEach void request() {
        request.addParameter("projectCode", "finance");
        request.addHeader("Authorization", "Bearer caller-token");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }

    @Test void paginationAndTotalFollowRealGrantsWithoutUsingTheMcpQueryString() {
        var allowed = Map.of("id", "orders-model", "projectCode", "orders", "connection", Map.of("apiKey", "******"));
        when(owner.list(null, "LLM", null, false)).thenReturn(ResponseEntity.ok(Map.of("data", List.of(
                Map.of("id", "global-model"), Map.of("id", "finance-model", "projectCode", "finance"), allowed))));
        var body = (Map<?, ?>) adapter.list(actor(), null, "LLM", null, false, 0, 1).getBody();
        assertEquals(List.of(allowed), body.get("items"));
        assertEquals(1, body.get("total"));
        assertEquals(List.of(), ((Map<?, ?>) adapter.list(actor(), null, "LLM", null, false, 1, 1).getBody()).get("items"));
        assertNull(request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE));
        assertEquals("Bearer caller-token", request.getHeader("Authorization"));
    }

    @Test void outOfProjectFiltersFailBeforeOwnerAndDetailsUseOwnerScope() {
        assertThrows(ResponseStatusException.class, () -> adapter.list(actor(), "finance", null, null, false, 0, 50));
        verifyNoInteractions(owner);
        when(owner.get("finance-model")).thenReturn(ResponseEntity.ok(Map.of("data", Map.of("projectCode", "finance"))));
        assertThrows(ResponseStatusException.class, () -> adapter.get(actor(), "finance-model"));
        assertNull(request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE));
        when(owner.get("orders-model")).thenReturn(ResponseEntity.ok(Map.of("data", Map.of("projectCode", "orders"))));
        assertEquals(200, adapter.get(actor(), "orders-model").getStatusCode().value());
    }

    @Test void malformedOwnerResultCannotBecomeAnEmptySuccessfulPage() {
        when(owner.list(null, null, null, false)).thenReturn(ResponseEntity.ok(Map.of("code", 200)));
        assertEquals(502, adapter.list(actor(), null, null, null, false, 0, 50).getStatusCode().value());
    }

    private PlatformAuthenticatedSession actor() {
        var user = new PlatformUserEntity(); user.setId(7L); user.setUsername("builder");
        return new PlatformAuthenticatedSession(PlatformPrincipal.fromUser(user), "pai-7", LocalDateTime.now().plusHours(1),
                List.of("BUILDER"), List.of("model:read"), List.of(new PlatformPermissionGrant("model:read", "PROJECT", "orders")));
    }
}
