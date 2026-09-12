package com.enterprise.ai.capability.internalauth;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class CapabilityInternalAuthFilterTest {

    private static final String SECRET = "filter-contract-secret-32bytes!!";

    @Test
    void toolExecutionRouteIncludingEncodedQualifiedNameIsProtected() {
        CapabilityInternalAuthFilter filter = filter();

        MockHttpServletRequest plain = new MockHttpServletRequest(
                "POST", "/internal/capability/tools/bzjs20:team.memory.resolve/execute");
        MockHttpServletRequest encoded = new MockHttpServletRequest(
                "POST", "/internal/capability/tools/bzjs20%3Ateam.memory.resolve/execute");
        MockHttpServletRequest lookup = new MockHttpServletRequest(
                "GET", "/internal/capability/tools/bzjs20:team.memory.resolve");
        MockHttpServletRequest invocation = new MockHttpServletRequest(
                "POST", "/internal/capability/invocations");
        MockHttpServletRequest projectVerification = new MockHttpServletRequest(
                "POST", "/internal/capability/registry/project-requests/verify");
        MockHttpServletRequest capabilityReview = new MockHttpServletRequest(
                "POST", "/api/registry/projects/orders/capability-diff-items/12/review");
        MockHttpServletRequest snapshotDiffItems = new MockHttpServletRequest(
                "GET", "/api/registry/projects/orders/capability-snapshots/21/diff-items");
        MockHttpServletRequest capabilityDiff = new MockHttpServletRequest(
                "POST", "/api/registry/projects/orders/capabilities/diff");
        MockHttpServletRequest sdkSync = new MockHttpServletRequest(
                "POST", "/api/registry/projects/orders/capabilities/sync");
        MockHttpServletRequest retiredBulkApply = new MockHttpServletRequest(
                "POST", "/api/registry/projects/orders/capabilities/apply");
        MockHttpServletRequest retiredIdOnlyReview = new MockHttpServletRequest(
                "POST", "/api/registry/capability-diff-items/12/review");

        assertFalse(filter.shouldNotFilter(plain));
        assertFalse(filter.shouldNotFilter(encoded));
        assertFalse(filter.shouldNotFilter(invocation));
        assertTrue(filter.shouldNotFilter(lookup));
        assertFalse(filter.shouldNotFilter(projectVerification));
        assertFalse(filter.shouldNotFilter(capabilityReview));
        assertFalse(filter.shouldNotFilter(snapshotDiffItems));
        assertFalse(filter.shouldNotFilter(capabilityDiff));
        assertTrue(filter.shouldNotFilter(sdkSync));
        assertTrue(filter.shouldNotFilter(retiredBulkApply));
        assertTrue(filter.shouldNotFilter(retiredIdOnlyReview));
    }

    @Test
    void unsignedToolExecutionIsRejectedBeforeController() throws Exception {
        CapabilityInternalAuthFilter filter = filter();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/internal/capability/tools/bzjs20:team.memory.resolve/execute");
        request.setContentType("application/json");
        request.setContent("{\"input\":{}}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("CAPABILITY_INTERNAL_AUTH_REQUIRED"));
        verifyNoInteractions(chain);
    }

    @Test
    void unsignedCapabilityReviewOperationIsRejectedBeforeController() throws Exception {
        CapabilityInternalAuthFilter filter = filter();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/registry/projects/orders/capability-diff-items/12/review");
        request.setContentType("application/json");
        request.setContent("{\"action\":\"APPLY\"}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("CAPABILITY_INTERNAL_AUTH_REQUIRED"));
        verifyNoInteractions(chain);
    }

    @Test
    void capabilityReviewRouteVariantsAreCanonicalizedAndRequireHmac() throws Exception {
        String diffPath = "/api/registry/projects/orders/capabilities/diff";
        List<RequestCase> cases = List.of(
                new RequestCase("POST", diffPath, diffPath),
                new RequestCase("GET", "/api/registry/projects/orders/capability-snapshots",
                        "/api/registry/projects/orders/capability-snapshots"),
                new RequestCase("GET", "/api/registry/projects/orders/capability-changes",
                        "/api/registry/projects/orders/capability-changes"),
                new RequestCase("GET", "/api/registry/projects/orders/capability-snapshots/12/diff-items",
                        "/api/registry/projects/orders/capability-snapshots/12/diff-items"),
                new RequestCase("POST", "/api/registry/projects/orders/capability-diff-items/34/review",
                        "/api/registry/projects/orders/capability-diff-items/34/review"),
                new RequestCase("POST", "/api/registry/projects/orders/capability-diff-items/34/rollback",
                        "/api/registry/projects/orders/capability-diff-items/34/rollback"),
                new RequestCase("POST", diffPath + "/", diffPath),
                new RequestCase("POST", diffPath + ";x=1", diffPath),
                new RequestCase("POST", diffPath + "%3Bx=1", diffPath),
                new RequestCase("POST", "/api//registry/projects/orders/capabilities/diff", diffPath),
                new RequestCase("POST", "/api/registry/projects/ignored/../orders/capabilities/diff", diffPath),
                new RequestCase("POST", "/api/registry/projects/ignored/%2e%2e/orders/capabilities/diff", diffPath),
                new RequestCase("POST", "/api%2Fregistry/projects/orders/capabilities/diff", diffPath),
                new RequestCase("POST", "/api%5Cregistry%5Cprojects%5Corders%5Ccapabilities%5Cdiff", diffPath));

        CapabilityInternalAuthFilter filter = filter();
        for (RequestCase requestCase : cases) {
            MockHttpServletRequest request = new MockHttpServletRequest(requestCase.method(), requestCase.uri());
            if ("POST".equals(requestCase.method())) {
                request.setContentType("application/json");
                request.setContent("{\"capabilities\":[]}".getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(requestCase.canonicalPath(), CapabilityInternalAuthFilter.normalizePath(request),
                    requestCase.uri());
            assertFalse(filter.shouldNotFilter(request), requestCase.uri());

            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);
            filter.doFilter(request, response, chain);

            assertEquals(401, response.getStatus(), requestCase.uri());
            assertTrue(response.getContentAsString().contains("CAPABILITY_INTERNAL_AUTH_REQUIRED"),
                    requestCase.uri());
            verifyNoInteractions(chain);
        }
    }

    @Test
    void signedCanonicalCapabilityReviewRequestReachesControllerWithExactBody() throws Exception {
        String path = "/api/registry/projects/orders/capabilities/diff";
        byte[] body = "{\"source\":\"manual-debug\",\"capabilities\":[]}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContentType("application/json");
        request.setContent(body);
        signedReviewHeaders(path, body).forEach(request::addHeader);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter().doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        verify(chain).doFilter(any(), same(response));
    }

    private CapabilityInternalAuthFilter filter() {
        CapabilityInternalAuthProperties properties = new CapabilityInternalAuthProperties(
                SECRET, 300, 600, 10_000, 1_048_576);
        properties.validate();
        CapabilityInternalAuthNonceStore nonceStore =
                (caller, nonce, nowMillis, ttlSeconds, maxEntries) -> true;
        CapabilityInternalAuthVerifier verifier = new CapabilityInternalAuthVerifier(
                properties, nonceStore, new ObjectMapper());
        return new CapabilityInternalAuthFilter(verifier, properties);
    }

    private Map<String, String> signedReviewHeaders(String path, byte[] body) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String digest = InternalServiceHmac.bodySha256Hex(body);
        String canonical = InternalServiceHmac.canonical(
                "POST",
                path,
                InternalServiceAuthHeaders.CALLER_CONTROL,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                "42",
                timestamp,
                nonce,
                digest);
        return Map.of(
                InternalServiceAuthHeaders.CALLER, InternalServiceAuthHeaders.CALLER_CONTROL,
                InternalServiceAuthHeaders.IDENTITY_SOURCE,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                InternalServiceAuthHeaders.IDENTITY_USER_ID, "42",
                InternalServiceAuthHeaders.TIMESTAMP, timestamp,
                InternalServiceAuthHeaders.NONCE, nonce,
                InternalServiceAuthHeaders.BODY_SHA256, digest,
                InternalServiceAuthHeaders.SIGNATURE, InternalServiceHmac.sign(SECRET, canonical));
    }

    private record RequestCase(String method, String uri, String canonicalPath) {
    }
}
