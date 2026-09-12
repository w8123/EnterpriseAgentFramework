package com.enterprise.ai.control.runtime;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.cloud.openfeign.support.ResponseEntityDecoder;
import org.springframework.cloud.openfeign.support.SpringDecoder;
import org.springframework.cloud.openfeign.support.SpringEncoder;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeDebugSessionGatewayTest {
    private static final String BASE = "/api/runtime/debug-sessions";
    private static final String SECRET = "debug-session-gateway-test-secret-at-least-32-bytes";
    private final ObjectMapper json = new ObjectMapper();
    private final RuntimeProxyClient runtime = mock(RuntimeProxyClient.class);
    private final RuntimeAgentStreamProxy streams = mock(RuntimeAgentStreamProxy.class);
    private final PlatformRequestAuthorization authorization = mock(PlatformRequestAuthorization.class);
    private final CapabilityProjectOnboardingClient projects = mock(CapabilityProjectOnboardingClient.class);
    private final RuntimeManagementAccess access = new RuntimeManagementAccess(authorization, projects);
    private final RuntimeDebugSessionGateway gateway = gateway(runtime, streams);

    @BeforeEach
    void bindOwner() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        when(authorization.requirePermission(any(), eq(PlatformPermissions.WORKFLOW_DEBUG))).thenReturn(session(42, "orders"));
        when(projects.getProjectById(7L)).thenReturn(Map.of("id", 7L, "projectCode", "orders"));
        when(runtime.getWorkflow("wf-orders")).thenReturn(ResponseEntity.ok(Map.of("projectId", 7L, "projectCode", "orders")));
        when(runtime.getRuntimeDebugSession(eq("session-1"), anyMap())).thenReturn(view());
    }

    @AfterEach
    void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    @Test
    void createSignsSessionIdentityAndAuthorizesTheCanonicalNestedWorkflowProject() throws Exception {
        var request = new LinkedHashMap<String, Object>(createBody());
        request.put("workingCopyDefinition", Map.of("workflowId", "wf-orders", "projectCode", "forged", "graphSpecJson", "{}"));
        request.put("userId", "43");
        request.put("ownerTenantId", "forged-tenant");
        request.put("workflowId", "root-decoy");
        gateway.create(request);
        var body = ArgumentCaptor.forClass(byte[].class);
        var headers = headersCaptor();
        verify(runtime).createRuntimeDebugSession(headers.capture(), body.capture());
        verify(runtime).getWorkflow("wf-orders");
        verify(runtime, never()).getWorkflow("root-decoy");
        verify(projects).getProjectById(7L);
        var sent = json.readTree(body.getValue());
        assertFalse(sent.has("userId"));
        assertFalse(sent.has("ownerTenantId"));
        assertFalse(sent.has("workflowId"));
        assertEquals("中文调试", sent.path("message").asText());
        assertEquals("creation-attempt", sent.path("idempotencyKey").asText());
        assertSignature("POST", BASE, headers.getValue(), body.getValue());
    }

    @Test
    void forgedBodyProjectCannotAuthorizeAnotherSavedWorkflow() {
        when(runtime.getWorkflow("wf-private")).thenReturn(ResponseEntity.ok(Map.of("projectCode", "finance")));
        var request = Map.<String, Object>of("workflowId", "wf-orders", "projectCode", "orders",
                "workingCopyDefinition", Map.of("workflowId", "wf-private", "projectCode", "orders"));
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> gateway.create(request)).getStatusCode().value());
        verify(runtime, never()).createRuntimeDebugSession(anyMap(), any());
        verifyNoInteractions(streams);
    }

    @Test
    void creationLookupSignsOwnerAndStillRequiresReturnedProjectGrant() throws Exception {
        when(runtime.getRuntimeDebugSessionByCreationKey(eq("creation-attempt"),anyMap())).thenReturn(view());
        assertEquals(200,gateway.getByCreationKey("creation-attempt").getStatusCode().value());
        var headers=headersCaptor();
        verify(runtime).getRuntimeDebugSessionByCreationKey(eq("creation-attempt"),headers.capture());
        assertSignature("GET",BASE+"/by-creation-key/creation-attempt",headers.getValue(),new byte[0]);
        when(runtime.getRuntimeDebugSessionByCreationKey(eq("private-attempt"),anyMap()))
                .thenReturn(ResponseEntity.ok(Map.of("projectCode","finance")));
        assertEquals(403,assertThrows(ResponseStatusException.class,()->gateway.getByCreationKey("private-attempt")).getStatusCode().value());
        verify(runtime,never()).createRuntimeDebugSession(anyMap(),any());verifyNoInteractions(streams);
    }

    @Test
    void unsavedWorkingCopyRequiresItsOwnProjectGrantForBothCreateRoutes() {
        var denied = Map.<String, Object>of("projectCode", "orders", "workingCopyDefinition", Map.of("projectCode", "finance"));
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> gateway.create(denied)).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> gateway.streamCreate(denied)).getStatusCode().value());
        verifyNoInteractions(runtime, streams);
    }

    @Test
    void ownerLookupFailureStopsEveryMutationAndResumeStream() {
        when(runtime.getRuntimeDebugSession(eq("session-1"), anyMap())).thenReturn(ResponseEntity.notFound().build());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> gateway.get("session-1")).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> gateway.submit("session-1", Map.of())).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> gateway.cancel("session-1")).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> gateway.streamSubmit("session-1", Map.of())).getStatusCode().value());
        verify(runtime, never()).submitRuntimeDebugSession(anyString(), anyMap(), any());
        verify(runtime, never()).cancelRuntimeDebugSession(anyString(), anyMap());
        verifyNoInteractions(streams);
    }

    @Test
    void ownerMustStillHaveTheSessionSnapshotProjectGrant() {
        when(runtime.getRuntimeDebugSession(eq("session-1"), anyMap())).thenReturn(ResponseEntity.ok(Map.of("projectCode", "finance")));
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> gateway.get("session-1")).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> gateway.cancel("session-1")).getStatusCode().value());
        verify(runtime, never()).cancelRuntimeDebugSession(anyString(), anyMap());
    }

    @Test
    void noRequestContextOrMalformedIdCannotDispatch() {
        RequestContextHolder.resetRequestAttributes();
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> gateway.create(createBody())).getStatusCode().value());
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> gateway.get("session-1")).getStatusCode().value());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        assertThrows(IllegalArgumentException.class, () -> gateway.cancel("../other"));
        verifyNoInteractions(runtime, streams);
    }

    @Test
    void streamingCapturesOwnerAndSerializedCommandBeforeTheRequestThreadEnds() throws Exception {
        var values = new LinkedHashMap<String, Object>(Map.of("confirm", "原始输入"));
        var command = new LinkedHashMap<String, Object>(Map.of("action", "submit", "values", values,
                "interactionId", "question-1", "idempotencyKey", "same-command", "userId", "forged"));
        var response = gateway.streamSubmit("session-1", command);
        verifyNoInteractions(streams);
        values.put("confirm", "late mutation");
        command.put("action", "cancel");
        when(authorization.requirePermission(any(), any())).thenReturn(session(43, "orders"));
        RequestContextHolder.resetRequestAttributes();
        response.getBody().writeTo(new ByteArrayOutputStream());
        var body = ArgumentCaptor.forClass(byte[].class);
        var headers = headersCaptor();
        verify(streams).streamSignedDebugSession(eq(BASE + "/session-1/submit/stream"), body.capture(), headers.capture(), any());
        assertSignature("POST", BASE + "/session-1/submit/stream", headers.getValue(), body.getValue());
        var sent = json.readTree(body.getValue());
        assertEquals("submit", sent.path("action").asText());
        assertEquals("原始输入", sent.path("values").path("confirm").asText());
        assertEquals("same-command", sent.path("idempotencyKey").asText());
        assertFalse(sent.has("userId"));
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }

    @Test
    void realFeignSyncCommandsSendExactlyTheBytesThatWereSigned() throws Exception {
        var requests = new CopyOnWriteArrayList<WireRequest>();
        HttpServer server = server(requests);
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            var feign = Feign.builder().contract(new SpringMvcContract())
                    .encoder(new SpringEncoder(HttpMessageConverters::new))
                    .decoder(new ResponseEntityDecoder(new SpringDecoder(HttpMessageConverters::new)))
                    .target(RuntimeProxyClient.class, url);
            var actual = gateway(feign, streams);
            assertEquals(200, actual.create(createBody()).getStatusCode().value());
            assertEquals(200, actual.get("session-1").getStatusCode().value());
            assertEquals(200, actual.submit("session-1", Map.of("action", "submit", "values", Map.of("确认", true)))
                    .getStatusCode().value());
            assertEquals(200, actual.cancel("session-1").getStatusCode().value());
            assertEquals(200, actual.getByCreationKey("creation-attempt").getStatusCode().value());
            assertEquals(7, requests.size());
            var nonces = new ArrayList<String>();
            for (var request : requests) {
                assertSignature(request.method(), request.path(), request.headers(), request.body());
                if (request.body().length > 0) assertTrue(request.headers().get("Content-Type").startsWith("application/json"));
                nonces.add(request.headers().get(InternalServiceAuthHeaders.NONCE));
            }
            assertEquals(7, nonces.stream().distinct().count());
            assertEquals("中文调试", json.readTree(requests.get(0).body()).path("message").asText());
            assertEquals("creation-attempt", json.readTree(requests.get(0).body()).path("idempotencyKey").asText());
            assertTrue(json.readTree(requests.get(3).body()).path("values").path("确认").asBoolean());
            assertEquals(0, requests.get(5).body().length);
            assertEquals(BASE+"/by-creation-key/creation-attempt",requests.get(6).path());
        } finally { server.stop(0); }
    }

    @Test
    void realHttpStreamRelayKeepsSignedBytesAndDoesNotExposeIdentityHeaders() throws Exception {
        var requests = new CopyOnWriteArrayList<WireRequest>();
        HttpServer server = server(requests);
        try {
            var proxy = new RuntimeAgentStreamProxy(json, HttpClient.newHttpClient(), "http://127.0.0.1:" + server.getAddress().getPort());
            var response = gateway(runtime, proxy).streamCreate(createBody());
            RequestContextHolder.resetRequestAttributes();
            var output = new ByteArrayOutputStream();
            response.getBody().writeTo(output);
            assertEquals(1, requests.size());
            var request = requests.get(0);
            assertSignature("POST", BASE + "/stream", request.headers(), request.body());
            assertEquals("creation-attempt", json.readTree(request.body()).path("idempotencyKey").asText());
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("session.completed"));
            assertFalse(output.toString(StandardCharsets.UTF_8).contains(SECRET));
            assertFalse(output.toString(StandardCharsets.UTF_8).contains(InternalServiceAuthHeaders.SIGNATURE));
        } finally { server.stop(0); }
    }

    private HttpServer server(List<WireRequest> requests) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(BASE, exchange -> {
            var headers = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, values.get(0)));
            String path = exchange.getRequestURI().getPath();
            requests.add(new WireRequest(exchange.getRequestMethod(), path, headers, exchange.getRequestBody().readAllBytes()));
            boolean stream = path.endsWith("/stream");
            byte[] response = (stream ? "event: session.completed\ndata: {\"sessionId\":\"session-1\"}\n\n"
                    : "{\"sessionId\":\"session-1\",\"projectCode\":\"orders\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
            finally { exchange.close(); }
        });
        server.start();
        return server;
    }

    private RuntimeDebugSessionGateway gateway(RuntimeProxyClient client, RuntimeAgentStreamProxy proxy) {
        return new RuntimeDebugSessionGateway(client, proxy, new InternalServiceAuthSigner(SECRET), json, access);
    }

    private Map<String, Object> createBody() {
        return Map.of("targetType", "WORKFLOW_WORKING_COPY", "workingCopyDefinition",
                Map.of("projectCode", "orders", "graphSpecJson", "{}"), "message", "中文调试", "idempotencyKey", "creation-attempt");
    }

    private ResponseEntity<Object> view() { return ResponseEntity.ok(Map.of("sessionId", "session-1", "projectCode", "orders")); }

    private PlatformAuthenticatedSession session(long id, String project) {
        var user = new PlatformUserEntity();
        user.setId(id);
        var grant = new PlatformPermissionGrant(PlatformPermissions.WORKFLOW_DEBUG, "PROJECT", project);
        return new PlatformAuthenticatedSession(PlatformPrincipal.fromUser(user), "session-" + id, null,
                List.of(), List.of(grant.permissionCode()), List.of(grant));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<Map<String, String>> headersCaptor() { return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class); }

    private void assertSignature(String method, String path, Map<String, String> headers, byte[] body) {
        assertEquals("42", headers.get(InternalServiceAuthHeaders.IDENTITY_USER_ID));
        assertEquals("default", headers.get(InternalServiceAuthHeaders.IDENTITY_TENANT_ID));
        assertEquals(InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION, headers.get(InternalServiceAuthHeaders.IDENTITY_SOURCE));
        assertEquals(InternalServiceHmac.bodySha256Hex(body), headers.get(InternalServiceAuthHeaders.BODY_SHA256));
        String canonical = InternalServiceHmac.canonical(method, path, InternalServiceAuthHeaders.CALLER_CONTROL,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION, "default", "42",
                headers.get(InternalServiceAuthHeaders.TIMESTAMP), headers.get(InternalServiceAuthHeaders.NONCE),
                headers.get(InternalServiceAuthHeaders.BODY_SHA256));
        assertEquals(InternalServiceHmac.sign(SECRET, canonical), headers.get(InternalServiceAuthHeaders.SIGNATURE));
    }

    private record WireRequest(String method, String path, Map<String, String> headers, byte[] body) {}
}
