package com.enterprise.ai.capability.internalauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class CapabilityInternalAuthFilterTest {

    @Test
    void toolExecutionRouteIncludingEncodedQualifiedNameIsProtected() {
        CapabilityInternalAuthFilter filter = filter();

        MockHttpServletRequest plain = new MockHttpServletRequest(
                "POST", "/internal/capability/tools/bzjs20:team.memory.resolve/execute");
        MockHttpServletRequest encoded = new MockHttpServletRequest(
                "POST", "/internal/capability/tools/bzjs20%3Ateam.memory.resolve/execute");
        MockHttpServletRequest lookup = new MockHttpServletRequest(
                "GET", "/internal/capability/tools/bzjs20:team.memory.resolve");
        MockHttpServletRequest projectVerification = new MockHttpServletRequest(
                "POST", "/internal/capability/registry/project-requests/verify");

        assertFalse(filter.shouldNotFilter(plain));
        assertFalse(filter.shouldNotFilter(encoded));
        assertTrue(filter.shouldNotFilter(lookup));
        assertFalse(filter.shouldNotFilter(projectVerification));
    }

    @Test
    void unsignedToolExecutionIsRejectedBeforeController() throws Exception {
        CapabilityInternalAuthFilter filter = filter();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/internal/capability/tools/bzjs20:team.memory.resolve/execute");
        request.setContentType("application/json");
        request.setContent("{\"input\":{}}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("CAPABILITY_INTERNAL_AUTH_REQUIRED"));
        verifyNoInteractions(chain);
    }

    private CapabilityInternalAuthFilter filter() {
        CapabilityInternalAuthProperties properties = new CapabilityInternalAuthProperties(
                "filter-contract-secret-32bytes!!", 300, 600, 10_000, 1_048_576);
        properties.validate();
        CapabilityInternalAuthNonceStore nonceStore =
                (caller, nonce, nowMillis, ttlSeconds, maxEntries) -> true;
        CapabilityInternalAuthVerifier verifier = new CapabilityInternalAuthVerifier(
                properties, nonceStore, new ObjectMapper());
        return new CapabilityInternalAuthFilter(verifier, properties);
    }
}
