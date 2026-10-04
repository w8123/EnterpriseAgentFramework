package com.enterprise.ai.control.capability;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeConsoleCapabilityInvocationGatewayTest {
    private static final String ID = "13513f8e-0682-45ed-98c8-6683887d5564";
    private static final String PATH = "/internal/runtime/console-capability-invocations/" + ID;

    @Test
    void preservesActorScopedNotFoundWithoutForwardingAnUpstreamBody() {
        RuntimeProxyClient runtime = mock(RuntimeProxyClient.class);
        when(runtime.getConsoleCapabilityInvocation(eq(ID), anyMap())).thenThrow(new FeignException.NotFound(
                "upstream-private-address", request(), "private-upstream-body".getBytes(StandardCharsets.UTF_8), Map.of()));

        ResponseEntity<Object> response = gateway(runtime).get(ID, "3");

        assertEquals(404, response.getStatusCode().value());
        assertNull(response.getBody());
    }

    @Test
    void doesNotMisclassifyAServiceFailureAsAnActorScopedNotFound() {
        RuntimeProxyClient runtime = mock(RuntimeProxyClient.class);
        FeignException failure = new FeignException.InternalServerError("unavailable", request(), new byte[0], Map.of());
        when(runtime.getConsoleCapabilityInvocation(eq(ID), anyMap())).thenThrow(failure);

        assertSame(failure, assertThrows(FeignException.class, () -> gateway(runtime).get(ID, "3")));
    }

    @Test
    void keepsTheVerifiedActorInTheSignedReadAndPassesAnOwnedOutcomeThrough() {
        RuntimeProxyClient runtime = mock(RuntimeProxyClient.class);
        ResponseEntity<Object> outcome = ResponseEntity.ok(Map.of("invocationId", ID));
        when(runtime.getConsoleCapabilityInvocation(eq(ID), anyMap())).thenReturn(outcome);

        assertSame(outcome, gateway(runtime).get(ID, "3"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(runtime).getConsoleCapabilityInvocation(eq(ID), headers.capture());
        assertEquals("3", headers.getValue().get(InternalServiceAuthHeaders.IDENTITY_USER_ID));
        assertEquals(InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                headers.getValue().get(InternalServiceAuthHeaders.IDENTITY_SOURCE));
    }

    private RuntimeConsoleCapabilityInvocationGateway gateway(RuntimeProxyClient runtime) {
        return new RuntimeConsoleCapabilityInvocationGateway(runtime,
                new InternalServiceAuthSigner("bmapi5b-unit-test-only-secret"), new ObjectMapper());
    }

    private Request request() {
        return Request.create(Request.HttpMethod.GET, PATH, Map.of(), null, StandardCharsets.UTF_8, null);
    }
}
