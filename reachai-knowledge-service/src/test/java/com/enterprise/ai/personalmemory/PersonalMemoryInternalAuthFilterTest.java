package com.enterprise.ai.personalmemory;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.internalauth.KnowledgeInternalNonceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersonalMemoryInternalAuthFilterTest {

    private static final String SECRET = "knowledge-personal-memory-test-secret";
    private static final String PATH = "/internal/knowledge/personal-memories/query";

    @Test
    void acceptsMatchingOwnerAndConsumesSharedNonce() throws Exception {
        KnowledgeInternalNonceStore nonceStore = mock(KnowledgeInternalNonceStore.class);
        when(nonceStore.tryConsume(eq(InternalServiceAuthHeaders.CALLER_CONTROL),
                eq("nonce-1"), any(Duration.class))).thenReturn(true);
        PersonalMemoryInternalAuthFilter filter =
                new PersonalMemoryInternalAuthFilter(SECRET, 300, nonceStore, new ObjectMapper());
        byte[] body = "{\"tenantId\":\"default\",\"runtimeUserId\":\"42\",\"query\":\"中文\"}"
                .getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = signed(body, "default", "42", "nonce-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        verify(nonceStore).tryConsume(eq(InternalServiceAuthHeaders.CALLER_CONTROL),
                eq("nonce-1"), any(Duration.class));
    }

    @Test
    void rejectsHeaderBodyOwnerMismatchBeforeNonceConsumption() throws Exception {
        KnowledgeInternalNonceStore nonceStore = mock(KnowledgeInternalNonceStore.class);
        PersonalMemoryInternalAuthFilter filter =
                new PersonalMemoryInternalAuthFilter(SECRET, 300, nonceStore, new ObjectMapper());
        byte[] body = "{\"tenantId\":\"default\",\"runtimeUserId\":\"victim\",\"query\":\"x\"}"
                .getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = signed(body, "default", "attacker", "nonce-2");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
    }

    @Test
    void replayStoreFailureIsFailClosed() throws Exception {
        KnowledgeInternalNonceStore nonceStore = mock(KnowledgeInternalNonceStore.class);
        when(nonceStore.tryConsume(any(), any(), any())).thenReturn(false);
        PersonalMemoryInternalAuthFilter filter =
                new PersonalMemoryInternalAuthFilter(SECRET, 300, nonceStore, new ObjectMapper());
        byte[] body = "{\"tenantId\":\"default\",\"runtimeUserId\":\"42\"}"
                .getBytes(StandardCharsets.UTF_8);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(signed(body, "default", "42", "nonce-3"), response, new MockFilterChain());

        assertEquals(401, response.getStatus());
    }

    @Test
    void acceptsPreviousSecretDuringBoundedRotationWindow() throws Exception {
        String active = "knowledge-active-internal-service-secret-32bytes";
        String previous = "knowledge-previous-internal-service-secret-32bytes";
        KnowledgeInternalNonceStore nonceStore = mock(KnowledgeInternalNonceStore.class);
        when(nonceStore.tryConsume(eq(InternalServiceAuthHeaders.CALLER_CONTROL),
                eq("nonce-rotation"), any(Duration.class))).thenReturn(true);
        PersonalMemoryInternalAuthFilter filter = new PersonalMemoryInternalAuthFilter(
                active, previous, 300, nonceStore, new ObjectMapper());
        byte[] body = "{\"tenantId\":\"default\",\"runtimeUserId\":\"42\"}"
                .getBytes(StandardCharsets.UTF_8);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(signed(body, "default", "42", "nonce-rotation", previous),
                response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }

    @Test
    void productionProfileRejectsWeakActiveOrOverlapSecret() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");
        KnowledgeInternalNonceStore nonceStore = mock(KnowledgeInternalNonceStore.class);

        assertThrows(IllegalStateException.class,
                () -> new PersonalMemoryInternalAuthFilter(
                        "short", "", 300, nonceStore, new ObjectMapper(), production));
        assertThrows(IllegalStateException.class,
                () -> new PersonalMemoryInternalAuthFilter(
                        "knowledge-active-internal-service-secret-32bytes", "short",
                        300, nonceStore, new ObjectMapper(), production));

        new PersonalMemoryInternalAuthFilter(
                "knowledge-active-internal-service-secret-32bytes",
                "knowledge-previous-internal-service-secret-32bytes",
                300, nonceStore, new ObjectMapper(), production);
    }

    private static MockHttpServletRequest signed(byte[] body, String tenant, String user, String nonce) {
        return signed(body, tenant, user, nonce, SECRET);
    }

    private static MockHttpServletRequest signed(byte[] body, String tenant, String user, String nonce,
                                                  String signingSecret) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.setContent(body);
        request.setContentType("application/json");
        String timestamp = String.valueOf(System.currentTimeMillis());
        String digest = InternalServiceHmac.bodySha256Hex(body);
        String canonical = InternalServiceHmac.canonical("POST", PATH,
                InternalServiceAuthHeaders.CALLER_CONTROL, "MEMORY_INDEXER", tenant, user,
                timestamp, nonce, digest);
        request.addHeader(InternalServiceAuthHeaders.CALLER, InternalServiceAuthHeaders.CALLER_CONTROL);
        request.addHeader(InternalServiceAuthHeaders.IDENTITY_SOURCE, "MEMORY_INDEXER");
        request.addHeader(InternalServiceAuthHeaders.IDENTITY_TENANT_ID, tenant);
        request.addHeader(InternalServiceAuthHeaders.IDENTITY_USER_ID, user);
        request.addHeader(InternalServiceAuthHeaders.TIMESTAMP, timestamp);
        request.addHeader(InternalServiceAuthHeaders.NONCE, nonce);
        request.addHeader(InternalServiceAuthHeaders.BODY_SHA256, digest);
        request.addHeader(InternalServiceAuthHeaders.SIGNATURE,
                InternalServiceHmac.sign(signingSecret, canonical));
        return request;
    }
}
