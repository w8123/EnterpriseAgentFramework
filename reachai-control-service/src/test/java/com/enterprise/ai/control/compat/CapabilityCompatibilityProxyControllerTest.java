package com.enterprise.ai.control.compat;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class CapabilityCompatibilityProxyControllerTest {

    @Test
    void keepsCapabilityRouteFamiliesAheadOfLegacyAgentFallback() throws Exception {
        var method = CapabilityCompatibilityProxyController.class
                .getDeclaredMethod("proxy", RequestEntity.class, HttpServletRequest.class);

        assertArrayEquals(new String[] {
                "/api/registry/{*path}",
                "/api/capabilities",
                "/api/capabilities/{*path}",
                "/api/tools",
                "/api/tools/{*path}",
                "/api/compositions",
                "/api/compositions/{*path}",
                "/api/api-assets",
                "/api/api-assets/{*path}",
                "/api/api-graph",
                "/api/api-graph/{*path}",
                "/api/tool-retrieval",
                "/api/tool-retrieval/{*path}",
                "/api/skill-mining",
                "/api/skill-mining/{*path}",
                "/api/capability-mining",
                "/api/capability-mining/{*path}",
                "/api/scan-projects",
                "/api/scan-projects/{*path}",
                "/api/scan-modules",
                "/api/scan-modules/{*path}",
                "/api/semantic-docs",
                "/api/semantic-docs/{*path}",
                "/api/domains",
                "/api/domains/{*path}"
        }, method.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class).path());
    }

    @Test
    void forwardsCapabilityRoutesToCapabilityService() throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        CapabilityCompatibilityProxyController controller =
                new CapabilityCompatibilityProxyController(restTemplate, "http://capability:18605");
        byte[] requestBody = "{\"enabled\":true}".getBytes(StandardCharsets.UTF_8);
        byte[] responseBody = "{\"items\":[]}".getBytes(StandardCharsets.UTF_8);
        RequestEntity<byte[]> requestEntity = RequestEntity
                .method(HttpMethod.PUT, URI.create("/api/tools/order.query?dryRun=true"))
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "req-cap-1")
                .body(requestBody);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("PUT", "/api/tools/order.query");
        servletRequest.setQueryString("dryRun=true");

        server.expect(requestTo("http://capability:18605/api/tools/order.query?dryRun=true"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("X-Request-Id", "req-cap-1"))
                .andExpect(header("X-ReachAI-Control-Capability-Proxy", "true"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(responseBody));

        ResponseEntity<byte[]> response = controller.proxy(requestEntity, servletRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
        assertArrayEquals(responseBody, response.getBody());
        server.verify();
    }
}
