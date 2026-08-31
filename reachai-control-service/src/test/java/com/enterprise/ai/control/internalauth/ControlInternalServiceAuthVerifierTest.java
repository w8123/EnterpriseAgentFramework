package com.enterprise.ai.control.internalauth;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ControlInternalServiceAuthVerifierTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String PATH = "/internal/control/agent-skills/resolve-execution";

    @Test
    void acceptsExactRuntimeSignatureAndRejectsNonceReplay() {
        ControlInternalServiceAuthVerifier verifier = verifier(SECRET);
        byte[] body = "[{\"skillId\":1}]".getBytes(StandardCharsets.UTF_8);
        HttpServletRequest request = signed("POST", PATH, body, "nonce-1", SECRET);

        assertDoesNotThrow(() -> verifier.requireRuntime(request, body));
        ResponseStatusException replay = assertThrows(ResponseStatusException.class,
                () -> verifier.requireRuntime(request, body));

        assertEquals(HttpStatus.UNAUTHORIZED, replay.getStatusCode());
    }

    @Test
    void sharedNonceStoreRejectsReplayAcrossControlInstances() {
        TestNonceStore shared = new TestNonceStore();
        ControlInternalServiceAuthVerifier first = verifier(SECRET, shared);
        ControlInternalServiceAuthVerifier second = verifier(SECRET, shared);
        byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
        HttpServletRequest request = signed("POST", PATH, body, "shared-nonce", SECRET);

        assertDoesNotThrow(() -> first.requireRuntime(request, body));
        ResponseStatusException replay = assertThrows(ResponseStatusException.class,
                () -> second.requireRuntime(request, body));

        assertEquals(HttpStatus.UNAUTHORIZED, replay.getStatusCode());
    }

    @Test
    void rejectsBodyTamperingAndFailsClosedWhenSecretIsMissing() {
        byte[] signedBody = "[]".getBytes(StandardCharsets.UTF_8);
        HttpServletRequest request = signed("POST", PATH, signedBody, "nonce-2", SECRET);
        ControlInternalServiceAuthVerifier verifier = verifier(SECRET);

        ResponseStatusException tampered = assertThrows(ResponseStatusException.class,
                () -> verifier.requireRuntime(request, "[1]".getBytes(StandardCharsets.UTF_8)));
        assertEquals(HttpStatus.UNAUTHORIZED, tampered.getStatusCode());

        ResponseStatusException missing = assertThrows(ResponseStatusException.class,
                () -> verifier("").requireRuntime(request, signedBody));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, missing.getStatusCode());
    }

    private ControlInternalServiceAuthVerifier verifier(String secret) {
        return verifier(secret, new TestNonceStore());
    }

    private ControlInternalServiceAuthVerifier verifier(String secret,
                                                         ControlInternalAuthNonceStore nonceStore) {
        return new ControlInternalServiceAuthVerifier(
                nonceStore, secret, "", 300, 600, 100, 1024 * 1024);
    }

    private HttpServletRequest signed(String method,
                                      String path,
                                      byte[] body,
                                      String nonce,
                                      String secret) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String digest = InternalServiceHmac.bodySha256Hex(body);
        String source = InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_UNTRUSTED;
        String canonical = InternalServiceHmac.canonical(
                method, path, InternalServiceAuthHeaders.CALLER_RUNTIME,
                source, "default", "", timestamp, nonce, digest);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(InternalServiceAuthHeaders.CALLER, InternalServiceAuthHeaders.CALLER_RUNTIME);
        headers.put(InternalServiceAuthHeaders.TIMESTAMP, timestamp);
        headers.put(InternalServiceAuthHeaders.NONCE, nonce);
        headers.put(InternalServiceAuthHeaders.IDENTITY_SOURCE, source);
        headers.put(InternalServiceAuthHeaders.IDENTITY_TENANT_ID, "default");
        headers.put(InternalServiceAuthHeaders.IDENTITY_USER_ID, "");
        headers.put(InternalServiceAuthHeaders.BODY_SHA256, digest);
        headers.put(InternalServiceAuthHeaders.SIGNATURE, InternalServiceHmac.sign(secret, canonical));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(path);
        headers.forEach((name, value) -> when(request.getHeader(name)).thenReturn(value));
        return request;
    }

    private static final class TestNonceStore implements ControlInternalAuthNonceStore {
        private final ConcurrentHashMap<String, Long> entries = new ConcurrentHashMap<>();

        @Override
        public boolean tryConsume(String caller,
                                  String nonce,
                                  long nowMillis,
                                  long ttlSeconds,
                                  int maxEntries) {
            entries.entrySet().removeIf(entry -> entry.getValue() <= nowMillis);
            if (entries.size() >= maxEntries) return false;
            return entries.putIfAbsent(caller + ":" + nonce, nowMillis + ttlSeconds * 1000L) == null;
        }
    }
}
