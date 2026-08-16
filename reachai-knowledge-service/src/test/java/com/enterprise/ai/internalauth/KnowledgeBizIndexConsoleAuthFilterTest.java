package com.enterprise.ai.internalauth;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBizIndexConsoleAuthFilterTest {

    private static final String SECRET = "knowledge-console-test-secret-with-32-bytes";
    private static final String PATH = "/internal/knowledge/console/biz-index/orders/search";

    @Test
    void acceptsExactBodySignatureAndReplaysBodyToController() throws Exception {
        KnowledgeInternalNonceStore nonceStore = mock(KnowledgeInternalNonceStore.class);
        when(nonceStore.tryConsume(eq(InternalServiceAuthHeaders.CALLER_CONTROL), any(), any(Duration.class)))
                .thenReturn(true);
        KnowledgeBizIndexConsoleAuthFilter filter = filter(nonceStore, 1_048_576);
        byte[] body = "{\"query\":\"order\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = signedRequest(body, body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertArrayEquals(body, chain.body);
        assertEquals("default", chain.tenant);
        assertEquals("42", chain.actor);
        verify(nonceStore).tryConsume(eq(InternalServiceAuthHeaders.CALLER_CONTROL), any(), any(Duration.class));
    }

    @Test
    void rejectsUnsignedAndTamperedRequests() throws Exception {
        KnowledgeInternalNonceStore nonceStore = mock(KnowledgeInternalNonceStore.class);
        when(nonceStore.tryConsume(any(), any(), any())).thenReturn(true);
        KnowledgeBizIndexConsoleAuthFilter filter = filter(nonceStore, 1_048_576);

        MockHttpServletRequest unsigned = request("POST", "{}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse unsignedResponse = new MockHttpServletResponse();
        filter.doFilter(unsigned, unsignedResponse, new MockFilterChain());
        assertEquals(401, unsignedResponse.getStatus());

        byte[] signed = "{\"query\":\"allowed\"}".getBytes(StandardCharsets.UTF_8);
        byte[] tampered = "{\"query\":\"tampered\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = signedRequest(tampered, signed);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(401, response.getStatus());
    }

    @Test
    void rejectsReplayWhenSharedNonceStoreDoesNotConsume() throws Exception {
        KnowledgeInternalNonceStore nonceStore = mock(KnowledgeInternalNonceStore.class);
        when(nonceStore.tryConsume(any(), any(), any())).thenReturn(false);
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(nonceStore, 1_048_576).doFilter(
                signedRequest(body, body), response, new MockFilterChain());

        assertEquals(401, response.getStatus());
    }

    @Test
    void rejectsBodyAboveConfiguredLimitBeforeMvc() throws Exception {
        KnowledgeInternalNonceStore nonceStore = mock(KnowledgeInternalNonceStore.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(nonceStore, 4).doFilter(
                request("POST", "12345".getBytes(StandardCharsets.UTF_8)),
                response,
                new MockFilterChain());

        assertEquals(413, response.getStatus());
        assertNotNull(response.getContentAsString());
    }

    private KnowledgeBizIndexConsoleAuthFilter filter(KnowledgeInternalNonceStore nonceStore,
                                                       long maxBodyBytes) {
        return new KnowledgeBizIndexConsoleAuthFilter(
                SECRET, "", 300, maxBodyBytes, nonceStore);
    }

    private MockHttpServletRequest signedRequest(byte[] actualBody, byte[] signedBody) {
        MockHttpServletRequest request = request("POST", actualBody);
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String digest = InternalServiceHmac.bodySha256Hex(signedBody);
        String canonical = InternalServiceHmac.canonical(
                "POST",
                PATH,
                InternalServiceAuthHeaders.CALLER_CONTROL,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                "default",
                "42",
                timestamp,
                nonce,
                digest);
        request.addHeader(InternalServiceAuthHeaders.CALLER, InternalServiceAuthHeaders.CALLER_CONTROL);
        request.addHeader(InternalServiceAuthHeaders.IDENTITY_SOURCE,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION);
        request.addHeader(InternalServiceAuthHeaders.IDENTITY_TENANT_ID, "default");
        request.addHeader(InternalServiceAuthHeaders.IDENTITY_USER_ID, "42");
        request.addHeader(InternalServiceAuthHeaders.TIMESTAMP, timestamp);
        request.addHeader(InternalServiceAuthHeaders.NONCE, nonce);
        request.addHeader(InternalServiceAuthHeaders.BODY_SHA256, digest);
        request.addHeader(InternalServiceAuthHeaders.SIGNATURE, InternalServiceHmac.sign(SECRET, canonical));
        return request;
    }

    private MockHttpServletRequest request(String method, byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/ai" + PATH);
        request.setContextPath("/ai");
        request.setContent(body);
        request.setContentType("application/json");
        return request;
    }

    private static final class CapturingChain extends MockFilterChain {
        private byte[] body;
        private Object tenant;
        private Object actor;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request,
                             jakarta.servlet.ServletResponse response) throws java.io.IOException {
            body = request.getInputStream().readAllBytes();
            tenant = request.getAttribute(KnowledgeBizIndexConsoleAuthFilter.VERIFIED_TENANT_ATTRIBUTE);
            actor = request.getAttribute(KnowledgeBizIndexConsoleAuthFilter.VERIFIED_ACTOR_ATTRIBUTE);
        }
    }
}
