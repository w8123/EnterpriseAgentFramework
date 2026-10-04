package com.enterprise.ai.runtime.internalauth;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class InternalServiceAuthFilterTest {

    @Test void workflowReleaseRoutesRequirePlatformHmacAndRejectTamperedCommands() throws Exception {
        String secret = "workflow-release-test-secret-at-least-32-bytes";
        var properties = new InternalServiceAuthProperties(secret, "", 300, 600, 1000, 1024 * 1024);
        var filter = new InternalServiceAuthFilter(new InternalServiceAuthVerifier(properties,
                new InMemoryInternalAuthNonceStore()), properties);
        byte[] body = "{\"baseRevision\":\"revision\"}".getBytes(StandardCharsets.UTF_8);
        for (String path : java.util.List.of("/api/workflows/wf-1/versions/publish",
                "/api/workflows/wf-1/versions/7/rollback",
                "/internal/runtime/workflows/studio/read-only-trials")) {
            var unsigned = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("POST", path), unsigned, new MockFilterChain());
            assertEquals(401, unsigned.getStatus());
            var signed = signedRequest(secret, path, body, "valid-" + path, "POST",
                    InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION, "default", "42");
            var accepted = new MockHttpServletResponse();
            filter.doFilter(signed, accepted, new MockFilterChain());
            assertEquals(200, accepted.getStatus());
            var replay = new MockHttpServletResponse();
            filter.doFilter(signedRequest(secret, path, body, "valid-" + path, "POST",
                    InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION, "default", "42"),
                    replay, new MockFilterChain());
            assertEquals(401, replay.getStatus());
            var tampered = signedRequest(secret, path, body, "tampered-" + path, "POST",
                    InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION, "default", "42");
            tampered.setContent("{\"baseRevision\":\"forged\"}".getBytes(StandardCharsets.UTF_8));
            var rejected = new MockHttpServletResponse();
            filter.doFilter(tampered, rejected, new MockFilterChain());
            assertEquals(401, rejected.getStatus());
            var remote = new MockHttpServletResponse();
            filter.doFilter(signedRequest(secret, path, body, "remote-" + path, "POST",
                    InternalServiceAuthHeaders.IDENTITY_SOURCE_A2A_REMOTE_AGENT, "default", "remote"),
                    remote, new MockFilterChain());
            assertEquals(401, remote.getStatus());
        }
    }

    @Test void capabilityReferencesRequireAttestedPlatformSession() throws Exception {
        String secret = "runtime-capability-reference-secret-at-least-32bytes";
        var properties = new InternalServiceAuthProperties(secret, "", 300, 600, 1000, 1024 * 1024);
        properties.validate();
        var filter = new InternalServiceAuthFilter(new InternalServiceAuthVerifier(properties, new InMemoryInternalAuthNonceStore()), properties);
        String path = "/internal/runtime/capability-references";
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        var valid = new MockHttpServletResponse();
        filter.doFilter(signedRequest(secret, path, body, "references-platform"), valid, new MockFilterChain());
        assertEquals(200, valid.getStatus());
        var invalid = new MockHttpServletResponse();
        filter.doFilter(signedRequest(secret, path, body, "references-remote", "POST",
                InternalServiceAuthHeaders.IDENTITY_SOURCE_A2A_REMOTE_AGENT, "tenant-a", "remote"), invalid, new MockFilterChain());
        assertEquals(401, invalid.getStatus());
    }

    @Test
    void retentionPrefixIsFailClosedAndForwardsVerifiedPlatformIdentity() throws Exception {
        String secret = "runtime-session-retention-filter-secret-32bytes";
        InternalServiceAuthProperties properties = new InternalServiceAuthProperties(
                secret, "", 300, 600, 1000, 1024 * 1024);
        properties.validate();
        InternalServiceAuthFilter filter = new InternalServiceAuthFilter(
                new InternalServiceAuthVerifier(properties, new InMemoryInternalAuthNonceStore()),
                properties);
        String path = "/internal/runtime/session-retention/tenants/tenant-a/sessions/session-a/legal-hold";
        byte[] body = "{\"enabled\":true,\"reasonCode\":\"LEGAL_CASE\"}"
                .getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = signedRequest(secret, path, body, "nonce-a");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        Object verified = chain.getRequest().getAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR);
        VerifiedInternalServiceAuth identity =
                assertInstanceOf(VerifiedInternalServiceAuth.class, verified);
        assertEquals(InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                identity.identitySource());
        assertEquals("tenant-a", identity.identityTenantId());
        assertEquals("42", identity.identityUserId());

        MockHttpServletResponse replayResponse = new MockHttpServletResponse();
        filter.doFilter(signedRequest(secret, path, body, "nonce-a"),
                replayResponse, new MockFilterChain());
        assertEquals(401, replayResponse.getStatus());
    }

    @Test
    void a2aExecutionPathAcceptsControlAttestedRemotePrincipal() throws Exception {
        String secret = "runtime-a2a-filter-secret-at-least-32bytes";
        InternalServiceAuthProperties properties = new InternalServiceAuthProperties(
                secret, "", 300, 600, 1000, 1024 * 1024);
        properties.validate();
        InternalServiceAuthFilter filter = new InternalServiceAuthFilter(
                new InternalServiceAuthVerifier(properties, new InMemoryInternalAuthNonceStore()),
                properties);
        String path = "/internal/runtime/a2a/executions";
        byte[] body = "{\"executionId\":\"exec-1\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = signedRequest(
                secret, path, body, "nonce-a2a", "POST",
                InternalServiceAuthHeaders.IDENTITY_SOURCE_A2A_REMOTE_AGENT,
                "tenant-a", "principal-a");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        VerifiedInternalServiceAuth identity = assertInstanceOf(
                VerifiedInternalServiceAuth.class,
                chain.getRequest().getAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR));
        assertEquals(InternalServiceAuthHeaders.IDENTITY_SOURCE_A2A_REMOTE_AGENT,
                identity.identitySource());
        assertEquals("tenant-a", identity.identityTenantId());
        assertEquals("principal-a", identity.identityUserId());
    }

    @Test
    void consoleCapabilityRoutesRequireAnExactPlatformHmacForPathActorAndBody() throws Exception {
        String secret = "runtime-console-capability-filter-secret-32bytes";
        InternalServiceAuthProperties properties = new InternalServiceAuthProperties(
                secret, "", 300, 600, 1000, 1024 * 1024);
        properties.validate();
        InternalServiceAuthFilter filter = new InternalServiceAuthFilter(
                new InternalServiceAuthVerifier(properties, new InMemoryInternalAuthNonceStore()), properties);
        String root = "/internal/runtime/console-capability-invocations";
        String id = "00000000-0000-0000-0000-000000000042";
        byte[] body = "{\"invocationId\":\"".concat(id).concat("\"}").getBytes(StandardCharsets.UTF_8);

        MockHttpServletResponse unsigned = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", root), unsigned, new MockFilterChain());
        assertEquals(401, unsigned.getStatus());

        MockHttpServletResponse accepted = new MockHttpServletResponse();
        filter.doFilter(signedRequest(secret, root, body, "console-post", "POST",
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION, "default", "42"), accepted,
                new MockFilterChain());
        assertEquals(200, accepted.getStatus());

        MockHttpServletRequest changedBody = signedRequest(secret, root, body, "console-body", "POST",
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION, "default", "42");
        changedBody.setContent("{\"invocationId\":\"forged\"}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse changedBodyResponse = new MockHttpServletResponse();
        filter.doFilter(changedBody, changedBodyResponse, new MockFilterChain());
        assertEquals(401, changedBodyResponse.getStatus());

        MockHttpServletRequest changedPath = signedRequest(secret, root, body, "console-path", "POST",
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION, "default", "42");
        changedPath.setRequestURI(root + "/" + id);
        MockHttpServletResponse changedPathResponse = new MockHttpServletResponse();
        filter.doFilter(changedPath, changedPathResponse, new MockFilterChain());
        assertEquals(401, changedPathResponse.getStatus());

        MockHttpServletRequest signedActor = signedRequest(secret, root, body, "console-actor", "POST",
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION, "default", "42");
        MockHttpServletRequest changedActor = new MockHttpServletRequest("POST", root);
        java.util.Collections.list(signedActor.getHeaderNames()).forEach(name -> {
            if (!InternalServiceAuthHeaders.IDENTITY_USER_ID.equals(name)) {
                changedActor.addHeader(name, signedActor.getHeader(name));
            }
        });
        changedActor.setContent(body);
        changedActor.addHeader(InternalServiceAuthHeaders.IDENTITY_USER_ID, "43");
        MockHttpServletResponse changedActorResponse = new MockHttpServletResponse();
        filter.doFilter(changedActor, changedActorResponse, new MockFilterChain());
        assertEquals(401, changedActorResponse.getStatus());

        MockHttpServletResponse queryAccepted = new MockHttpServletResponse();
        filter.doFilter(signedRequest(secret, root + "/" + id, new byte[0], "console-get", "GET",
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION, "default", "42"), queryAccepted,
                new MockFilterChain());
        assertEquals(200, queryAccepted.getStatus());
    }

    @Test
    void httpApiCatalogStateRouteRequiresPlatformHmac() throws Exception {
        String secret = "runtime-http-api-catalog-filter-secret-32bytes";
        var properties = new InternalServiceAuthProperties(secret, "", 300, 600, 1000, 1024 * 1024);
        var filter = new InternalServiceAuthFilter(new InternalServiceAuthVerifier(properties,
                new InMemoryInternalAuthNonceStore()), properties);
        String path = "/internal/runtime/http-api-catalog-states";
        byte[] body = "{\"projectCode\":\"orders\"}".getBytes(StandardCharsets.UTF_8);

        var unsigned = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", path), unsigned, new MockFilterChain());
        assertEquals(401, unsigned.getStatus());
        var accepted = new MockHttpServletResponse();
        filter.doFilter(signedRequest(secret, path, body, "api-catalog-valid", "POST",
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION, "orders", "42"),
                accepted, new MockFilterChain());
        assertEquals(200, accepted.getStatus());
        var remote = new MockHttpServletResponse();
        filter.doFilter(signedRequest(secret, path, body, "api-catalog-remote", "POST",
                InternalServiceAuthHeaders.IDENTITY_SOURCE_A2A_REMOTE_AGENT, "orders", "remote"),
                remote, new MockFilterChain());
        assertEquals(401, remote.getStatus());
    }

    private static MockHttpServletRequest signedRequest(
            String secret, String path, byte[] body, String nonce) {
        return signedRequest(
                secret, path, body, nonce, "PUT",
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                "tenant-a", "42");
    }

    private static MockHttpServletRequest signedRequest(
            String secret,
            String path,
            byte[] body,
            String nonce,
            String method,
            String identitySource,
            String tenantId,
            String userId) {
        long now = System.currentTimeMillis();
        String timestamp = String.valueOf(now);
        String digest = InternalServiceHmac.bodySha256Hex(body);
        String canonical = InternalServiceHmac.canonical(
                method,
                path,
                InternalServiceAuthHeaders.CALLER_CONTROL,
                identitySource,
                tenantId,
                userId,
                timestamp,
                nonce,
                digest);
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setContent(body);
        request.addHeader(InternalServiceAuthHeaders.CALLER,
                InternalServiceAuthHeaders.CALLER_CONTROL);
        request.addHeader(InternalServiceAuthHeaders.IDENTITY_SOURCE,
                identitySource);
        request.addHeader(InternalServiceAuthHeaders.IDENTITY_TENANT_ID, tenantId);
        request.addHeader(InternalServiceAuthHeaders.IDENTITY_USER_ID, userId);
        request.addHeader(InternalServiceAuthHeaders.TIMESTAMP, timestamp);
        request.addHeader(InternalServiceAuthHeaders.NONCE, nonce);
        request.addHeader(InternalServiceAuthHeaders.BODY_SHA256, digest);
        request.addHeader(InternalServiceAuthHeaders.SIGNATURE,
                InternalServiceHmac.sign(secret, canonical));
        return request;
    }
}
