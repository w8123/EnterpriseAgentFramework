package com.enterprise.ai.reach.spring;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReachAiEmbedTokenClientTest {

    @Test
    void exchangesAndParsesWrappedPlatformResponse() {
        RecordingTransport transport = new RecordingTransport(
                "{\"code\":200,\"message\":\"success\",\"data\":{"
                        + "\"token\":\"embed-jwt\",\"expiresIn\":600,"
                        + "\"sessionHint\":{\"agentId\":\"demo-page-copilot\","
                        + "\"pageInstanceId\":\"page-001\"}}}");
        ReachAiEmbedTokenClient client =
                new ReachAiEmbedTokenClient(properties(), transport);

        ReachAiEmbedTokenResult result = client.exchange(request());

        assertEquals("embed-jwt", result.getToken());
        assertEquals(600L, result.getExpiresIn());
        assertEquals(
                "page-001",
                result.getSessionHint().get("pageInstanceId"));
        assertEquals(
                "https://reachai.example.com/api/embed/token/exchange",
                transport.url);
        assertEquals("POST", transport.method);
        assertEquals(
                "demo-key",
                transport.headers.get("X-ReachAI-App-Key"));
        assertNotNull(transport.headers.get("X-ReachAI-Timestamp"));
        assertNotNull(transport.headers.get("X-ReachAI-Nonce"));
        assertNotNull(transport.headers.get("X-ReachAI-Signature"));
        assertEquals("demo", transport.body.get("projectCode"));
        assertEquals("page-001", transport.body.get("pageInstanceId"));
        assertEquals("/teams", transport.body.get("route"));
        assertEquals(
                "user-001",
                ((ReachAiEmbedPrincipal) transport.body.get("principal"))
                        .getExternalUserId());
    }

    @Test
    void acceptsLegacyTopLevelTokenShape() {
        RecordingTransport transport = new RecordingTransport(
                "{\"token\":\"legacy-jwt\",\"expiresIn\":300}");
        ReachAiEmbedTokenClient client =
                new ReachAiEmbedTokenClient(properties(), transport);

        ReachAiEmbedTokenResult result = client.exchange(request());

        assertEquals("legacy-jwt", result.getToken());
        assertEquals(300L, result.getExpiresIn());
    }

    @Test
    void rejectsFailedBusinessCodeWithoutEchoingResponse() {
        ReachAiEmbedTokenClient client = new ReachAiEmbedTokenClient(
                properties(),
                new RecordingTransport(
                        "{\"code\":403,\"message\":\"secret diagnostic\"}"));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> client.exchange(request()));

        assertEquals(
                "ReachAI Embed Token exchange was rejected",
                error.getMessage());
    }

    @Test
    void requiresSdkOwnedPageIdentityAndBusinessPrincipal() {
        ReachAiEmbedTokenClient client = new ReachAiEmbedTokenClient(
                properties(),
                new RecordingTransport("{}"));
        ReachAiEmbedTokenRequest invalid = new ReachAiEmbedTokenRequest(
                "demo-page-copilot",
                "team.list",
                null,
                "/teams",
                "http://localhost:9200",
                new ReachAiEmbedPrincipal(
                        "user-001",
                        null,
                        "Demo User",
                        Collections.<String>emptyList(),
                        Collections.<String, Object>emptyMap()));

        assertThrows(
                IllegalArgumentException.class,
                () -> client.exchange(invalid));
    }

    @Test
    void embedIdentityAndSessionHintsAreDefensiveImmutableSnapshots() {
        List<String> roles = new ArrayList<String>();
        roles.add("TEAM_ADMIN");
        Map<String, Object> attributes = new LinkedHashMap<String, Object>();
        attributes.put("departmentId", "dept-001");
        ReachAiEmbedPrincipal principal = new ReachAiEmbedPrincipal(
                "user-001",
                "employee-001",
                "Demo User",
                roles,
                attributes);
        Map<String, String> sessionHint = new LinkedHashMap<String, String>();
        sessionHint.put("pageInstanceId", "page-001");
        ReachAiEmbedTokenResult result =
                new ReachAiEmbedTokenResult("embed-jwt", 600L, sessionHint);

        roles.add("UNEXPECTED");
        attributes.put("departmentId", "changed");
        sessionHint.put("pageInstanceId", "changed");

        assertEquals(Collections.singletonList("TEAM_ADMIN"), principal.getRoles());
        assertEquals("dept-001", principal.getAttributes().get("departmentId"));
        assertEquals("page-001", result.getSessionHint().get("pageInstanceId"));
        assertThrows(UnsupportedOperationException.class,
                () -> principal.getRoles().add("UNEXPECTED"));
        assertThrows(UnsupportedOperationException.class,
                () -> principal.getAttributes().put("extra", "value"));
        assertThrows(UnsupportedOperationException.class,
                () -> result.getSessionHint().put("extra", "value"));
    }

    private ReachAiRegistryProperties properties() {
        ReachAiRegistryProperties properties =
                new ReachAiRegistryProperties();
        properties.getRegistry().setUrl("https://reachai.example.com/");
        properties.getRegistry().setAppKey("demo-key");
        properties.getRegistry().setAppSecret("demo-secret");
        properties.getProject().setCode("demo");
        properties.getProject().setName("Demo");
        return properties;
    }

    private ReachAiEmbedTokenRequest request() {
        return new ReachAiEmbedTokenRequest(
                "demo-page-copilot",
                "team.list",
                "page-001",
                "/teams",
                "http://localhost:9200",
                new ReachAiEmbedPrincipal(
                        "user-001",
                        "employee-001",
                        "Demo User",
                        Arrays.asList("TEAM_ADMIN"),
                        Collections.<String, Object>singletonMap(
                                "departmentId",
                                "dept-001")));
    }

    private static final class RecordingTransport
            implements ReachAiRegistryTransport {

        private final String response;
        private String method;
        private String url;
        private Map<String, String> headers;
        private Map<String, Object> body;

        private RecordingTransport(String response) {
            this.response = response;
        }

        @SuppressWarnings("unchecked")
        @Override
        public String exchange(
                String method,
                String url,
                Map<String, String> headers,
                Object body) throws IOException {
            this.method = method;
            this.url = url;
            this.headers = headers;
            this.body = (Map<String, Object>) body;
            return response;
        }
    }
}
