package com.enterprise.ai.control.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.http.HttpServlet;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformEmbedTokenOriginFilterTest {
    @Test
    void corsAllowsConfiguredOriginButTheBearerMustMatchIt() throws Exception {
        var properties = new PlatformEmbedTokenProperties(); properties.setSecret("test-origin-signing-key");
        var tokens = new PlatformEmbedTokenService(new ObjectMapper(), properties);
        String token = tokens.issue(PlatformEmbedTokenIssueCommand.builder()
                .projectCode("erp").agentId("bot").pageInstanceId("page").origin("https://erp.example")
                .principal(new PlatformEmbedTokenIssueCommand.BusinessPrincipal("user", "user", "User", List.of(), Map.of()))
                .build()).token();
        var policy = mock(PlatformEmbedOriginPolicy.class);
        when(policy.allows("https://other.example")).thenReturn(true);
        var cors = new PlatformEmbedCorsFilter(new PlatformEmbedCorsProperties(), policy);
        var origin = new PlatformEmbedTokenOriginFilter(tokens);
        var request = new MockHttpServletRequest("GET", "/api/embed/memories");
        request.addHeader("Origin", "https://other.example");
        request.addHeader("Authorization", "Bearer " + token);
        var response = new MockHttpServletResponse();
        new MockFilterChain(new HttpServlet() { }, cors, origin).doFilter(request, response);
        assertEquals(403, response.getStatus());
        assertEquals("https://other.example", response.getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    void enforcesVerifiedBearerOriginAcrossEmbedEndpoints() throws Exception {
        var properties = new PlatformEmbedTokenProperties();
        properties.setSecret("test-origin-signing-key");
        var tokens = new PlatformEmbedTokenService(new ObjectMapper(), properties);
        String token = tokens.issue(PlatformEmbedTokenIssueCommand.builder()
                .tenantId("1").projectCode("erp").agentId("bot").pageInstanceId("page")
                .origin("https://erp.example")
                .principal(new PlatformEmbedTokenIssueCommand.BusinessPrincipal("user", "user", "User", List.of(), Map.of()))
                .build()).token();
        var filter = new PlatformEmbedTokenOriginFilter(tokens);
        for (String path : List.of("/api/embed/chat/sessions", "/api/embed/pages/register",
                "/api/embed/memories", "/api/embed/ai-tool-connections")) {
            assertEquals(200, call(filter, "POST", path, token, "https://erp.example"));
            assertEquals(403, call(filter, "POST", path, token, "https://other.example"));
            assertEquals(401, call(filter, "POST", path, "invalid", "https://erp.example"));
            assertEquals(200, call(filter, "POST", path, token, null));
        }
        assertEquals(200, call(filter, "OPTIONS", "/api/embed/chat/sessions", null, "https://other.example"));
        assertEquals(200, call(filter, "POST", "/api/embed/token/exchange", "not-an-embed-token", "https://other.example"));
        assertEquals(200, call(filter, "POST", "/api/agents", token, "https://other.example"));
    }

    @Test
    void rejectsMultipleOriginHeaders() throws Exception {
        var properties = new PlatformEmbedTokenProperties(); properties.setSecret("test-origin-signing-key");
        var tokens = new PlatformEmbedTokenService(new ObjectMapper(), properties);
        String token = tokens.issue(PlatformEmbedTokenIssueCommand.builder()
                .projectCode("erp").agentId("bot").pageInstanceId("page").origin("https://erp.example")
                .principal(new PlatformEmbedTokenIssueCommand.BusinessPrincipal("user", "user", "User", List.of(), Map.of()))
                .build()).token();
        var request = new MockHttpServletRequest("GET", "/api/embed/memories");
        request.addHeader("Authorization", "Bearer " + token);
        request.addHeader("Origin", "https://erp.example");
        request.addHeader("Origin", "https://other.example");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new PlatformEmbedTokenOriginFilter(tokens).doFilter(request, response, chain);
        assertEquals(403, response.getStatus());
        assertNull(chain.getRequest());
    }

    private static int call(PlatformEmbedTokenOriginFilter filter, String method, String path,
                            String token, String origin) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        if (token != null) request.addHeader("Authorization", "Bearer " + token);
        if (origin != null) request.addHeader("Origin", origin);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        if (response.getStatus() == 200) assertNotNull(chain.getRequest());
        else assertNull(chain.getRequest());
        return response.getStatus();
    }
}
