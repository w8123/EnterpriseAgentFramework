package com.enterprise.ai.internalauth;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeProjectIngressAuthFilterTest {

    private static final String SECRET = "knowledge-project-ingress-test-secret-32bytes";
    private static final String PATH =
            "/internal/knowledge/project-ingress/projects/orders/biz-index/orders_idx/batch";

    @Test
    void acceptsSignedProjectIdentityAndReplaysExactBody() throws Exception {
        KnowledgeInternalNonceStore nonceStore = mock(KnowledgeInternalNonceStore.class);
        when(nonceStore.tryConsume(eq(InternalServiceAuthHeaders.CALLER_CONTROL),
                any(), any(Duration.class))).thenReturn(true);
        KnowledgeProjectIngressAuthFilter filter = new KnowledgeProjectIngressAuthFilter(
                SECRET, "", 300, 1_048_576, nonceStore);
        byte[] body = "{\"items\":[]}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = signed(body, "orders", "credential:3");
        MockHttpServletResponse response = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertEquals("orders", chain.project);
        assertEquals("credential:3", chain.credential);
        assertArrayEquals(body, chain.body);
    }

    @Test
    void rejectsCrossProjectPathEvenWithValidHmac() throws Exception {
        KnowledgeInternalNonceStore nonceStore = mock(KnowledgeInternalNonceStore.class);
        when(nonceStore.tryConsume(any(), any(), any())).thenReturn(true);
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = signed(body, "other", "credential:3");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new KnowledgeProjectIngressAuthFilter(SECRET, "", 300, 1_048_576, nonceStore)
                .doFilter(request, response, new org.springframework.mock.web.MockFilterChain());

        assertEquals(401, response.getStatus());
    }

    private static MockHttpServletRequest signed(byte[] body, String project, String actor) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ai" + PATH);
        request.setContextPath("/ai");
        request.setContentType("application/json");
        request.setContent(body);
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String digest = InternalServiceHmac.bodySha256Hex(body);
        String canonical = InternalServiceHmac.canonical(
                "POST", PATH, InternalServiceAuthHeaders.CALLER_CONTROL,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PROJECT_CREDENTIAL,
                project, actor, timestamp, nonce, digest);
        request.addHeader(InternalServiceAuthHeaders.CALLER, InternalServiceAuthHeaders.CALLER_CONTROL);
        request.addHeader(InternalServiceAuthHeaders.IDENTITY_SOURCE,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PROJECT_CREDENTIAL);
        request.addHeader(InternalServiceAuthHeaders.IDENTITY_TENANT_ID, project);
        request.addHeader(InternalServiceAuthHeaders.IDENTITY_USER_ID, actor);
        request.addHeader(InternalServiceAuthHeaders.TIMESTAMP, timestamp);
        request.addHeader(InternalServiceAuthHeaders.NONCE, nonce);
        request.addHeader(InternalServiceAuthHeaders.BODY_SHA256, digest);
        request.addHeader(InternalServiceAuthHeaders.SIGNATURE, InternalServiceHmac.sign(SECRET, canonical));
        return request;
    }

    private static final class CapturingChain implements jakarta.servlet.FilterChain {
        private byte[] body;
        private Object project;
        private Object credential;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request,
                             jakarta.servlet.ServletResponse response) throws java.io.IOException {
            body = request.getInputStream().readAllBytes();
            project = request.getAttribute(KnowledgeProjectIngressAuthFilter.VERIFIED_PROJECT_ATTRIBUTE);
            credential = request.getAttribute(KnowledgeProjectIngressAuthFilter.VERIFIED_CREDENTIAL_ATTRIBUTE);
        }
    }
}
