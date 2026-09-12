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
                "/api/workflows/wf-1/versions/7/rollback")) {
            var unsigned = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("POST", path), unsigned, new MockFilterChain());
            assertEquals(401, unsigned.getStatus());
            var signed = signedRequest(secret, path, body, "valid-" + path, "POST",
                    InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION, "default", "42");
            var accepted = new MockHttpServletResponse();
            filter.doFilter(signed, accepted, new MockFilterChain());
            assertEquals(200, accepted.getStatus());
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
