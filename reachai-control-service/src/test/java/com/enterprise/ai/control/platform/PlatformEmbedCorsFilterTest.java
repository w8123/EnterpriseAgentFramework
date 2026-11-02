package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.client.capability.CapabilityProxyClient;
import com.enterprise.ai.control.mcp.infrastructure.McpHubProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformEmbedCorsFilterTest {

    @Test
    void preflightFromAPolicyOriginIsAnsweredAndOthersAreRejected() throws Exception {
        CapabilityProxyClient capability = capabilityWith(List.of(
                policy("[\"https://yudao.w8123.com\",\"https://*.example.com\"]")));
        PlatformEmbedCorsFilter filter = new PlatformEmbedCorsFilter(
                new PlatformEmbedCorsProperties(), new PlatformEmbedOriginPolicy(capability, new ObjectMapper()));

        MockHttpServletResponse allowed = preflight(filter, "https://yudao.w8123.com");
        assertEquals(200, allowed.getStatus());
        assertEquals("https://yudao.w8123.com", allowed.getHeader("Access-Control-Allow-Origin"));

        MockHttpServletResponse wildcard = preflight(filter, "https://erp.example.com");
        assertEquals("https://erp.example.com", wildcard.getHeader("Access-Control-Allow-Origin"));

        MockHttpServletResponse rejected = preflight(filter, "https://evil.test");
        assertEquals(403, rejected.getStatus());
        assertNull(rejected.getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    void memoryDeletePreflightIsAllowedForPolicyOrigin() throws Exception {
        PlatformEmbedCorsFilter filter = new PlatformEmbedCorsFilter(new PlatformEmbedCorsProperties(),
                new PlatformEmbedOriginPolicy(capabilityWith(List.of(policy("[\"https://yudao.w8123.com\"]"))),
                        new ObjectMapper()));
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/embed/memories/7");
        request.addHeader("Origin", "https://yudao.w8123.com");
        request.addHeader("Access-Control-Request-Method", "DELETE");
        request.addHeader("Access-Control-Request-Headers", "authorization");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        assertEquals("https://yudao.w8123.com", response.getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    void localhostIsOnlyAllowedWhileSomeProjectHasNoOriginList() {
        PlatformEmbedOriginPolicy withEmpty = new PlatformEmbedOriginPolicy(
                capabilityWith(List.of(policy("[]"))), new ObjectMapper());
        PlatformEmbedOriginPolicy configured = new PlatformEmbedOriginPolicy(
                capabilityWith(List.of(policy("[\"https://yudao.w8123.com\"]"))), new ObjectMapper());

        assertTrue(withEmpty.allows("http://localhost:5173"));
        assertFalse(configured.allows("http://localhost:5173"));
        assertFalse(configured.allows("*"));
    }

    @Test
    void policyIsCachedUntilInvalidated() {
        CapabilityProxyClient capability = capabilityWith(List.of(policy("[\"https://a.test\"]")));
        PlatformEmbedOriginPolicy policy = new PlatformEmbedOriginPolicy(capability, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-10-17T00:00:00Z"), ZoneOffset.UTC));

        assertTrue(policy.allows("https://a.test"));
        assertTrue(policy.allows("https://a.test"));
        verify(capability, times(1)).listEmbedCredentialPolicies(null, "ACTIVE", 1000);

        policy.invalidate();
        assertTrue(policy.allows("https://a.test"));
        verify(capability, times(2)).listEmbedCredentialPolicies(null, "ACTIVE", 1000);
    }

    @Test
    void reachAiOwnPagesBehindAProxyAreTreatedAsSameOrigin() throws Exception {
        PlatformEmbedCorsFilter filter = new PlatformEmbedCorsFilter(new PlatformEmbedCorsProperties(),
                new PlatformEmbedOriginPolicy(capabilityWith(List.of(policy("[\"https://yudao.w8123.com\"]"))),
                        new ObjectMapper()));
        McpHubProperties hub = new McpHubProperties();
        hub.setPublicBaseUrl("https://reachai.w8123.com/");
        filter.setHubProperties(hub);

        // Behind Nginx, Control sees http://127.0.0.1:18603 while the browser sends the public origin.
        MockHttpServletRequest own = new MockHttpServletRequest("POST", "/api/embed/chat/sessions");
        own.setServerName("127.0.0.1");
        own.setServerPort(18603);
        own.addHeader("Origin", "https://reachai.w8123.com");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(own, new MockHttpServletResponse(), chain);
        assertEquals(own, chain.getRequest());

        MockHttpServletRequest other = new MockHttpServletRequest("POST", "/api/embed/chat/sessions");
        other.setServerName("127.0.0.1");
        other.setServerPort(18603);
        other.addHeader("Origin", "https://evil.test");
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        MockFilterChain otherChain = new MockFilterChain();
        filter.doFilter(other, rejected, otherChain);
        assertEquals(403, rejected.getStatus());
        assertNull(otherChain.getRequest());
    }

    @Test
    void nonEmbedPathsAreNotTouched() throws Exception {
        PlatformEmbedCorsFilter filter = new PlatformEmbedCorsFilter(new PlatformEmbedCorsProperties(),
                new PlatformEmbedOriginPolicy(capabilityWith(List.of()), new ObjectMapper()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agents");
        request.addHeader("Origin", "https://evil.test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(request, chain.getRequest());
        assertNull(response.getHeader("Access-Control-Allow-Origin"));
    }

    private static MockHttpServletResponse preflight(PlatformEmbedCorsFilter filter, String origin) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/embed/chat/sessions");
        request.addHeader("Origin", origin);
        request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader("Access-Control-Request-Headers", "authorization,content-type");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static Map<String, Object> policy(String originsJson) {
        return Map.of("projectCode", "yudao-erp", "status", "ACTIVE", "allowedOriginsJson", originsJson);
    }

    private static CapabilityProxyClient capabilityWith(List<Map<String, Object>> policies) {
        CapabilityProxyClient capability = mock(CapabilityProxyClient.class);
        when(capability.listEmbedCredentialPolicies(null, "ACTIVE", 1000)).thenReturn(ResponseEntity.ok(policies));
        return capability;
    }
}
