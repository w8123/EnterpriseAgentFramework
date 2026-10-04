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
        MockHttpServletRequest catalog = new MockHttpServletRequest(
                "GET", "/api/tools");
        MockHttpServletRequest catalogItem = new MockHttpServletRequest(
                "GET", "/api/tools/orders_read");
        MockHttpServletRequest businessMethodCatalog = new MockHttpServletRequest(
                "GET", "/internal/capability/business-methods");
        MockHttpServletRequest businessMethodItem = new MockHttpServletRequest(
                "GET", "/internal/capability/business-methods/orders_create");
        MockHttpServletRequest businessMethodInvocationContext = new MockHttpServletRequest(
                "GET", "/internal/capability/business-methods/orders_create/invocation-context");
        MockHttpServletRequest projectLookup = new MockHttpServletRequest(
                "GET", "/internal/capability/projects/by-id/7");
        MockHttpServletRequest unknownCatalogPath = new MockHttpServletRequest(
                "GET", "/api/tools/orders_read/extra");
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
        assertFalse(filter.shouldNotFilter(catalog));
        assertFalse(filter.shouldNotFilter(catalogItem));
        assertFalse(filter.shouldNotFilter(businessMethodCatalog));
        assertFalse(filter.shouldNotFilter(businessMethodItem));
        assertFalse(filter.shouldNotFilter(businessMethodInvocationContext));
        for (String path : List.of("/internal/capability/business-methods/orders:normalize/execution-context",
                "/internal/capability/business-methods/orders%3Anormalize/execution-context",
                "/internal/capability//business-methods/./orders:normalize;ignored=x/execution-context")) {
            assertFalse(filter.shouldNotFilter(new MockHttpServletRequest("POST", path)));
        }
        assertFalse(filter.shouldNotFilter(projectLookup));
        assertTrue(filter.shouldNotFilter(unknownCatalogPath));
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
    void unsignedCatalogReadAndProjectLookupAreRejectedBeforeController() throws Exception {
        CapabilityInternalAuthFilter filter = filter();
        for (String path : List.of("/api/tools", "/api/tools/orders_read",
                "/internal/capability/business-methods",
                "/internal/capability/business-methods/orders_create",
                "/internal/capability/projects/by-id/7")) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            assertEquals(401, response.getStatus(), path);
            assertTrue(response.getContentAsString().contains("CAPABILITY_INTERNAL_AUTH_REQUIRED"), path);
            verifyNoInteractions(chain);
        }
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

    @Test
    void signedCatalogReadAndProjectLookupUseExactReadOnlyPaths() throws Exception {
        CapabilityInternalAuthFilter filter = filter();
        for (String path : List.of("/api/tools", "/api/tools/orders_read",
                "/internal/capability/business-methods",
                "/internal/capability/business-methods/orders_create",
                "/internal/capability/projects/by-id/7")) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            byte[] body = new byte[0];
            signedControlHeaders("GET", path, body).forEach(request::addHeader);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            assertEquals(200, response.getStatus(), path);
            verify(chain).doFilter(any(), same(response));
        }
    }

    @Test
    void signedBusinessMethodInvocationContextBindsTheExactPathAndActor() throws Exception {
        String path = "/internal/capability/business-methods/orders.lookup/invocation-context";
        byte[] body = new byte[0];
        CapabilityInternalAuthFilter filter = filter();
        MockHttpServletRequest accepted = new MockHttpServletRequest("GET", path);
        signedControlHeaders("GET", path, body).forEach(accepted::addHeader);
        MockHttpServletResponse acceptedResponse = new MockHttpServletResponse();
        FilterChain acceptedChain = mock(FilterChain.class);
        filter.doFilter(accepted, acceptedResponse, acceptedChain);
        assertEquals(200, acceptedResponse.getStatus());
        verify(acceptedChain).doFilter(any(), same(acceptedResponse));

        MockHttpServletRequest forgedPath = new MockHttpServletRequest("GET",
                "/internal/capability/business-methods/other.lookup/invocation-context");
        signedControlHeaders("GET", path, body).forEach(forgedPath::addHeader);
        MockHttpServletResponse forgedPathResponse = new MockHttpServletResponse();
        filter.doFilter(forgedPath, forgedPathResponse, mock(FilterChain.class));
        assertEquals(401, forgedPathResponse.getStatus());

        MockHttpServletRequest forgedActor = new MockHttpServletRequest("GET", path);
        signedControlHeaders("GET", path, body).forEach((name, value) -> {
            if (!InternalServiceAuthHeaders.IDENTITY_USER_ID.equals(name)) forgedActor.addHeader(name, value);
        });
        forgedActor.addHeader(InternalServiceAuthHeaders.IDENTITY_USER_ID, "43");
        MockHttpServletResponse forgedActorResponse = new MockHttpServletResponse();
        filter.doFilter(forgedActor, forgedActorResponse, mock(FilterChain.class));
        assertEquals(401, forgedActorResponse.getStatus());
    }

    @Test
    void marketPublicAliasesAndOwnerRoutesRequireSignedControlRequests() throws Exception {
        var cases = List.of(new RequestCase("GET", "/api/api-market/entries", "/api/api-market/entries"),
                new RequestCase("POST", "/api/api-market/entries/orders/integrations", "/api/api-market/entries/orders/integrations"),
                new RequestCase("GET", "/internal/capability/api-market/integrations/1", "/internal/capability/api-market/integrations/1"),
                new RequestCase("PUT", "/internal/capability/api-market/integrations/1/status", "/internal/capability/api-market/integrations/1/status"));
        for (var route : cases) {
            byte[] body = "GET".equals(route.method()) ? new byte[0] : "{\"projectId\":41}".getBytes(StandardCharsets.UTF_8);
            var unsigned = new MockHttpServletRequest(route.method(), route.uri()); unsigned.setContent(body);
            var refused = new MockHttpServletResponse(); var refusedChain = mock(FilterChain.class);
            filter().doFilter(unsigned, refused, refusedChain);
            assertEquals(401, refused.getStatus(), route.uri()); verifyNoInteractions(refusedChain);
            var signed = new MockHttpServletRequest(route.method(), route.uri()); signed.setContent(body);
            signedControlHeaders(route.method(), route.canonicalPath(), body).forEach(signed::addHeader);
            var accepted = new MockHttpServletResponse(); var acceptedChain = mock(FilterChain.class);
            filter().doFilter(signed, accepted, acceptedChain);
            assertEquals(200, accepted.getStatus(), route.uri()); verify(acceptedChain).doFilter(any(), same(accepted));
        }
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
        return signedControlHeaders("POST", path, body);
    }

    private Map<String, String> signedControlHeaders(String method, String path, byte[] body) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String digest = InternalServiceHmac.bodySha256Hex(body);
        String canonical = InternalServiceHmac.canonical(
                method,
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
