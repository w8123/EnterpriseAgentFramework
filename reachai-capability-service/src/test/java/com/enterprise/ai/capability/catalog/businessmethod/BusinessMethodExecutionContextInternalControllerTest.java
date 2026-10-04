package com.enterprise.ai.capability.catalog.businessmethod;

import com.enterprise.ai.capability.internalauth.CapabilityVerifiedInternalServiceAuth;
import com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts;
import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessMethodExecutionContextInternalControllerTest {
    private final BusinessMethodInvocationContextService owners = mock(BusinessMethodInvocationContextService.class);
    private final BusinessMethodInvocationContextInternalController controller = new BusinessMethodInvocationContextInternalController(owners);
    private MockHttpServletRequest request(String caller, String source, String project) {
        var request = new MockHttpServletRequest();
        request.setAttribute(CapabilityVerifiedInternalServiceAuth.REQUEST_ATTR,
                new CapabilityVerifiedInternalServiceAuth(caller, source, project, null));
        return request;
    }
    private Map<String, Object> body() { return Map.of("qualifiedName", "orders:normalize", "context", Map.of("tenantId", "orders")); }
    private ConsoleCapabilityInvocationContracts.InvocationContext owner(String project) {
        return new ConsoleCapabilityInvocationContracts.InvocationContext(1, "orders_normalize", "orders:normalize", "orders:normalize",
                "BUSINESS_METHOD", 41L, project, "a".repeat(64), "a".repeat(64), "a".repeat(64), "READY", true, "READ_ONLY",
                List.of(), null, "String", null, null, "UNKNOWN", true, false, true, null, null, 30_000);
    }
    @Test void onlyVerifiedRuntimeProjectIdentityAndExactBodyCanReadExecutionFacts() {
        assertEquals(401, controller.executionContext(new MockHttpServletRequest(), "orders:normalize", body()).getStatusCode().value());
        for (var request : List.of(request(InternalServiceAuthHeaders.CALLER_CONTROL, "PLATFORM_SESSION", "orders"),
                request(InternalServiceAuthHeaders.CALLER_RUNTIME, InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_UNTRUSTED, "orders"),
                request(InternalServiceAuthHeaders.CALLER_RUNTIME, InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_TENANT_TRUSTED, "other"))) {
            assertEquals(401, controller.executionContext(request, "orders:normalize", body()).getStatusCode().value());
        }
        var trusted = request(InternalServiceAuthHeaders.CALLER_RUNTIME, InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_TENANT_TRUSTED, "orders");
        assertEquals(401, controller.executionContext(trusted, "orders:other", body()).getStatusCode().value());
        assertEquals(401, controller.executionContext(trusted, "orders:normalize", Map.of("qualifiedName", "orders:normalize",
                "context", Map.of("tenantId", "orders", "externalUserId", "platform:42"))).getStatusCode().value());
        verifyNoInteractions(owners);
    }
    @Test void ownerProjectCannotBeReplacedBySignedCallerScope() {
        var trusted = request(InternalServiceAuthHeaders.CALLER_RUNTIME, InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_TENANT_TRUSTED, "orders");
        when(owners.get("orders:normalize")).thenReturn(owner("other"));
        assertEquals(403, controller.executionContext(trusted, "orders:normalize", body()).getStatusCode().value());
        when(owners.get("orders:normalize")).thenReturn(owner("orders"));
        assertEquals(200, controller.executionContext(trusted, "orders:normalize", body()).getStatusCode().value());
    }
}
