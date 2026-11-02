package com.enterprise.ai.control.capability;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformAuthorizationService;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import com.enterprise.ai.control.internalauth.InternalServiceAuthRequestAttributes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityMcpPlatformReadAdapterTest {
    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    @Test
    void businessMethodReadUsesTheRealConsoleAndOwnerProjectChecks() {
        var gateway = mock(CapabilityReviewGateway.class);
        var auth = new PlatformRequestAuthorization(new PlatformAuthorizationService(null, null, null, null));
        var catalog = new CapabilityCatalogConsoleController(gateway, auth);
        var adapter = new CapabilityMcpPlatformReadAdapter(catalog, mock(HttpApiConsoleController.class),
                mock(HttpApiReferenceController.class), mock(CapabilityReviewConsoleController.class), new PlatformDelegatedRequestScope());
        var original = new MockHttpServletRequest("POST", "/mcp/platform");
        original.setParameter("groupId", "999");
        original.addHeader("Authorization", "Bearer hidden-token");
        Object previous = new Object();
        original.setAttribute(PlatformConsoleAuthInterceptor.USER_REQUEST_ATTRIBUTE, previous);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(original));
        var actor = actor();
        when(gateway.listBusinessMethods(1, 20, null, true, 7L, "7", null, false,
                CatalogReadAuthorization.body(actor, 7L)))
                .thenReturn(ownerPage(7L, "orders", Map.of("records", List.of("orders.read"))));
        when(gateway.listBusinessMethods(1, 20, null, true, 8L, "7", null, false,
                CatalogReadAuthorization.body(actor, 8L)))
                .thenReturn(ownerPage(8L, "billing", Map.of("records", List.of("billing.secret"))));

        assertEquals(Map.of("records", List.of("orders.read")),
                adapter.listBusinessMethods(actor, 7L, 1, 20, null, true).getBody());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> adapter.listBusinessMethods(actor, 8L, 1, 20, null, true)).getStatusCode());
        assertSame(previous, original.getAttribute(PlatformConsoleAuthInterceptor.USER_REQUEST_ATTRIBUTE));
        assertNull(original.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE));
        assertNull(original.getAttribute(InternalServiceAuthRequestAttributes.PLATFORM_SESSION_ACTOR_ID));
        verify(gateway).listBusinessMethods(1, 20, null, true, 7L, "7", null, false,
                CatalogReadAuthorization.body(actor, 7L));
    }

    @Test
    void scopeDoesNotExposeMcpQueryParametersOrCredentialsToConsoleActions() {
        var original = new MockHttpServletRequest("POST", "/mcp/platform");
        original.setParameter("groupId", "999");
        original.setQueryString("groupId=999");
        original.addHeader("Authorization", "Bearer hidden-token");
        original.addHeader("Cookie", "session=hidden");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(original));
        var actor = actor();
        var scope = new PlatformDelegatedRequestScope();
        scope.run(actor, request -> {
            assertNull(request.getParameter("groupId"));
            assertNull(request.getQueryString());
            assertNull(request.getHeader("Authorization"));
            assertNull(request.getHeader("Cookie"));
            assertSame(actor, request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE));
            return ResponseEntity.ok().build();
        });
        assertNull(original.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE));
    }

    private static ResponseEntity<Object> ownerPage(long id, String code, Map<String, Object> body) {
        return ResponseEntity.ok().header(CatalogReadAuthorization.SCOPE, "PROJECT")
                .header(CatalogReadAuthorization.PROJECT_ID, String.valueOf(id))
                .header(CatalogReadAuthorization.PROJECT_CODE, code).body(body);
    }

    private static PlatformAuthenticatedSession actor() {
        var user = new PlatformUserEntity();
        user.setId(7L);
        var grant = new PlatformPermissionGrant("platform:read", "PROJECT", "orders");
        return new PlatformAuthenticatedSession(PlatformPrincipal.fromUser(user), "pai_19",
                LocalDateTime.now().plusDays(1), List.of(), List.of("platform:read"), List.of(grant));
    }
}
