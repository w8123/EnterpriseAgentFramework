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

    private static MockHttpServletRequest signedRequest(
            String secret, String path, byte[] body, String nonce) {
        long now = System.currentTimeMillis();
        String timestamp = String.valueOf(now);
        String digest = InternalServiceHmac.bodySha256Hex(body);
        String canonical = InternalServiceHmac.canonical(
                "PUT",
                path,
                InternalServiceAuthHeaders.CALLER_CONTROL,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                "tenant-a",
                "42",
                timestamp,
                nonce,
                digest);
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", path);
        request.setContent(body);
        request.addHeader(InternalServiceAuthHeaders.CALLER,
                InternalServiceAuthHeaders.CALLER_CONTROL);
        request.addHeader(InternalServiceAuthHeaders.IDENTITY_SOURCE,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION);
        request.addHeader(InternalServiceAuthHeaders.IDENTITY_TENANT_ID, "tenant-a");
        request.addHeader(InternalServiceAuthHeaders.IDENTITY_USER_ID, "42");
        request.addHeader(InternalServiceAuthHeaders.TIMESTAMP, timestamp);
        request.addHeader(InternalServiceAuthHeaders.NONCE, nonce);
        request.addHeader(InternalServiceAuthHeaders.BODY_SHA256, digest);
        request.addHeader(InternalServiceAuthHeaders.SIGNATURE,
                InternalServiceHmac.sign(secret, canonical));
        return request;
    }
}
