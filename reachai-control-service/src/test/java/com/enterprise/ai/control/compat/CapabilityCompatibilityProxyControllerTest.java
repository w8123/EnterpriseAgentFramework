package com.enterprise.ai.control.compat;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
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
import java.util.List;

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
        var retiredCapabilities = CapabilityCompatibilityProxyController.class
                .getDeclaredMethod("retiredCapabilities");
        var registryMethod = CapabilityCompatibilityProxyController.class
                .getDeclaredMethod("proxyRegistry", RequestEntity.class, HttpServletRequest.class);

        assertArrayEquals(new String[] {
                "/api/scan-projects",
                "/api/scan-projects/{*path}"
        }, method.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class).path());
        assertArrayEquals(new String[] {
                "/api/capabilities",
                "/api/capabilities/{*path}"
        }, retiredCapabilities.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class).path());
        assertArrayEquals(new String[] {"/api/registry/{*path}"},
                registryMethod.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class).path());
    }

    @Test
    void retiresPublicCapabilityKernelWithoutProxying() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        CapabilityCompatibilityProxyController controller =
                new CapabilityCompatibilityProxyController(restTemplate, "http://capability:18605");

        ResponseEntity<byte[]> response = controller.retiredCapabilities();

        assertEquals(HttpStatus.GONE, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
        assertEquals("{\"code\":\"CAPABILITY_KERNEL_ROUTE_RETIRED\","
                        + "\"message\":\"The public /api/capabilities product entry has been retired\"}",
                new String(response.getBody(), StandardCharsets.UTF_8));
        server.verify();
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
                .method(HttpMethod.PUT, URI.create("/api/scan-projects/7?dryRun=true"))
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "req-cap-1")
                .body(requestBody);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("PUT", "/api/scan-projects/7");
        servletRequest.setQueryString("dryRun=true");
        servletRequest.setAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE,
                actor("platform:write", "GLOBAL", "*"));

        server.expect(requestTo("http://capability:18605/api/scan-projects/7"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":7,\"projectCode\":\"yudao-erp\"}"));
        server.expect(requestTo("http://capability:18605/api/scan-projects/7?dryRun=true"))
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

    @Test
    void projectListDropsStoredKeysAndTrimsRowsForCallersWithoutPlatformRead() {
        String upstream = "[{\"id\":7,\"name\":\"芋道\",\"projectCode\":\"yudao-erp\","
                + "\"baseUrl\":\"http://erp\",\"authApiKeyValue\":\"stored-key\"}]";
        String manager = listProjectsAs(upstream, List.of("agent:read"));
        String developer = listProjectsAs(upstream, List.of("platform:read"));

        assertEquals("[{\"id\":7,\"name\":\"芋道\",\"projectCode\":\"yudao-erp\"}]", manager);
        assertEquals("[{\"id\":7,\"name\":\"芋道\",\"projectCode\":\"yudao-erp\","
                + "\"baseUrl\":\"http://erp\"}]", developer);
    }

    private String listProjectsAs(String upstream, List<String> permissions) {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        CapabilityCompatibilityProxyController controller =
                new CapabilityCompatibilityProxyController(restTemplate, "http://capability:18605");
        server.expect(requestTo("http://capability:18605/api/scan-projects"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                        .body(upstream.getBytes(StandardCharsets.UTF_8)));
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/api/scan-projects");
        servletRequest.setAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE,
                actor(permissions.get(0), "GLOBAL", "*"));

        ResponseEntity<byte[]> response = controller.proxy(
                new RequestEntity<byte[]>(HttpMethod.GET, URI.create("/api/scan-projects")), servletRequest);

        server.verify();
        return new String(response.getBody(), StandardCharsets.UTF_8);
    }

    @Test
    void projectScopedReaderOnlySeesGrantedProjectAndCannotReadOtherProjectOrChildren() {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(http);
        var controller = new CapabilityCompatibilityProxyController(http, "http://capability:18605");
        var session = actor("platform:read", "PROJECT", "allowed");
        server.expect(requestTo("http://capability:18605/api/scan-projects"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                        .body("[{\"id\":1,\"projectCode\":\"allowed\",\"baseUrl\":\"visible\"},{\"id\":2,\"projectCode\":\"private\",\"baseUrl\":\"secret\"}]"));
        for (int i = 0; i < 2; i++) {
            server.expect(requestTo("http://capability:18605/api/scan-projects/2"))
                    .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                            .body("{\"id\":2,\"projectCode\":\"private\"}"));
        }
        server.expect(requestTo("http://capability:18605/api/scan-projects/1"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":1,\"projectCode\":\"allowed\"}"));
        server.expect(requestTo("http://capability:18605/api/scan-projects/1"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":1,\"projectCode\":\"allowed\",\"baseUrl\":\"visible\"}"));

        var list = call(controller, session, "GET", "/api/scan-projects");
        assertEquals("[{\"id\":1,\"projectCode\":\"allowed\",\"baseUrl\":\"visible\"}]", new String(list.getBody(), StandardCharsets.UTF_8));
        assertEquals(HttpStatus.FORBIDDEN, call(controller, session, "GET", "/api/scan-projects/2").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, call(controller, session, "GET", "/api/scan-projects/2/tools").getStatusCode());
        assertEquals(HttpStatus.OK, call(controller, session, "GET", "/api/scan-projects/1").getStatusCode());
        server.verify();
    }

    @Test
    void scopedWriteCannotCreateOrMutateOtherProject() {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(http);
        var controller = new CapabilityCompatibilityProxyController(http, "http://capability:18605");
        var session = actor("platform:write", "PROJECT", "allowed");
        server.expect(requestTo("http://capability:18605/api/scan-projects/2"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":2,\"projectCode\":\"private\"}"));
        assertEquals(HttpStatus.FORBIDDEN, call(controller, session, "POST", "/api/scan-projects").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, call(controller, session, "DELETE", "/api/scan-projects/2").getStatusCode());
        server.verify();
    }

    @Test
    void rejectsMissingOrMismatchedOwnerIdentityBeforeForwarding() {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(http);
        var controller = new CapabilityCompatibilityProxyController(http, "http://capability:18605");
        var session = actor("platform:read", "PROJECT", "allowed");
        server.expect(requestTo("http://capability:18605/api/scan-projects/3"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":4,\"projectCode\":\"allowed\"}"));
        server.expect(requestTo("http://capability:18605/api/scan-projects/3"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":3}"));
        assertEquals(HttpStatus.FORBIDDEN, call(controller, session, "GET", "/api/scan-projects/3").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, call(controller, session, "GET", "/api/scan-projects/3/tools").getStatusCode());
        server.verify();
    }

    private static PlatformAuthenticatedSession actor(String permission, String scope, String value) {
        return new PlatformAuthenticatedSession(null, "s", null, List.of(), List.of(permission),
                List.of(new PlatformPermissionGrant(permission, scope, value)));
    }

    private static ResponseEntity<byte[]> call(CapabilityCompatibilityProxyController controller,
                                                PlatformAuthenticatedSession session, String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session);
        return controller.proxy(new RequestEntity<byte[]>(HttpMethod.valueOf(method), URI.create(path)), request);
    }

    @Test
    void rejectsRetiredRegistryManagementRoutesBeforeProxying() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        CapabilityCompatibilityProxyController controller =
                new CapabilityCompatibilityProxyController(restTemplate, "http://capability:18605");
        RequestEntity<byte[]> requestEntity = RequestEntity
                .method(HttpMethod.POST, URI.create("/api/registry/capability-diff-items/12/review"))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"action\":\"APPLY\"}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest servletRequest = new MockHttpServletRequest(
                "POST", "/api/registry/capability-diff-items/12/review");

        ResponseEntity<byte[]> response = controller.proxyRegistry(requestEntity, servletRequest);

        assertEquals(HttpStatus.GONE, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
        server.verify();
    }

    @Test
    void rejectsProjectScopedRegistryManagementRoutesBeforeProxying() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        CapabilityCompatibilityProxyController controller =
                new CapabilityCompatibilityProxyController(restTemplate, "http://capability:18605");
        RequestEntity<byte[]> requestEntity = RequestEntity
                .method(HttpMethod.POST,
                        URI.create("/api/registry/projects/orders/capability-diff-items/12/review"))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"action\":\"APPLY\"}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest servletRequest = new MockHttpServletRequest(
                "POST", "/api/registry/projects/orders/capability-diff-items/12/review");

        ResponseEntity<byte[]> response = controller.proxyRegistry(requestEntity, servletRequest);

        assertEquals(HttpStatus.GONE, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
        server.verify();
    }
}
