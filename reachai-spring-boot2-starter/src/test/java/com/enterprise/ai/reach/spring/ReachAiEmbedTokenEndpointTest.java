package com.enterprise.ai.reach.spring;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReachAiEmbedTokenEndpointTest {

    private final AtomicReference<ReachAiEmbedTokenRequest> exchanged = new AtomicReference<ReachAiEmbedTokenRequest>();

    @Test
    void exchangesLoggedInUserForTokenUsingTheBrowserOrigin() {
        ReachAiEmbedTokenEndpoint endpoint = new ReachAiEmbedTokenEndpoint(agentId -> user(), client(false));

        ResponseEntity<Map<String, Object>> response = endpoint.token("https://erp.example.com", request(null));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("embed-token", response.getBody().get("token"));
        assertEquals(600L, response.getBody().get("expiresIn"));
        assertEquals("https://erp.example.com", exchanged.get().getOrigin());
        assertEquals("erp-assistant", exchanged.get().getAgentId());
        assertEquals("u-1", exchanged.get().getPrincipal().getExternalUserId());
    }

    @Test
    void anonymousRequestGets401AndNoExchange() {
        ReachAiEmbedTokenEndpoint endpoint = new ReachAiEmbedTokenEndpoint(agentId -> null, client(false));

        ResponseEntity<Map<String, Object>> response = endpoint.token("https://erp.example.com", request(null));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNull(exchanged.get());
    }

    @Test
    void rejectsBodyOriginThatDiffersFromOriginHeader() {
        ReachAiEmbedTokenEndpoint endpoint = new ReachAiEmbedTokenEndpoint(agentId -> user(), client(false));

        ResponseEntity<Map<String, Object>> response =
                endpoint.token("https://erp.example.com", request("https://evil.example.com"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNull(exchanged.get());
    }

    @Test
    void reachAiRejectionBecomesBadGatewayWithoutUpstreamDetails() {
        ReachAiEmbedTokenEndpoint endpoint = new ReachAiEmbedTokenEndpoint(agentId -> user(), client(true));

        ResponseEntity<Map<String, Object>> response = endpoint.token("https://erp.example.com", request(null));

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
    }

    private static ReachAiEmbedTokenEndpoint.TokenRequest request(String origin) {
        ReachAiEmbedTokenEndpoint.TokenRequest request = new ReachAiEmbedTokenEndpoint.TokenRequest();
        request.setAgentId("erp-assistant");
        request.setPageInstanceId("page-1");
        request.setRoute("/erp/stock");
        request.setOrigin(origin);
        return request;
    }

    private static ReachAiEmbedPrincipal user() {
        return new ReachAiEmbedPrincipal("u-1", "u-1", "admin",
                Collections.singletonList("super_admin"), Collections.<String, Object>emptyMap());
    }

    private ReachAiEmbedTokenClient client(boolean reject) {
        return new ReachAiEmbedTokenClient(new ReachAiRegistryProperties(), (method, url, headers, body) -> "{}") {
            @Override
            public ReachAiEmbedTokenResult exchange(ReachAiEmbedTokenRequest request) {
                if (reject) {
                    throw new IllegalStateException("ReachAI Embed Token exchange request failed");
                }
                exchanged.set(request);
                return new ReachAiEmbedTokenResult("embed-token", 600L, null);
            }
        };
    }
}
