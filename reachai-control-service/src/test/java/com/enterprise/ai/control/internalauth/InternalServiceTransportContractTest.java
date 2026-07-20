package com.enterprise.ai.control.internalauth;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.control.client.runtime.RuntimeAgentExecutionInternalClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real Control signer ↔ Runtime-equivalent verifier on the same serialized bytes.
 * Uses shared ai-common HMAC primitives (same algorithm as Runtime InternalServiceAuthVerifier).
 * Not a Live E2E.
 */
class InternalServiceTransportContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void controlSignerAndRuntimeEquivalentVerifierAgreeOnUtf8ChineseBody() throws Exception {
        String secret = "transport-contract-secret-32bytes!!";
        InternalServiceAuthSigner signer = new InternalServiceAuthSigner(secret);
        byte[] payload = envelopeBytes("AGENT", "42", "查订单");
        Map<String, String> headers = signer.sign(
                "POST",
                RuntimeAgentExecutionInternalClient.EXECUTE_PATH,
                "AGENT",
                "42",
                payload);

        assertTrue(verifyLikeRuntime(
                secret,
                "POST",
                RuntimeAgentExecutionInternalClient.EXECUTE_PATH,
                headers,
                payload,
                System.currentTimeMillis()));

        byte[] tampered = envelopeBytes("AGENT", "42", "被篡改");
        assertFalse(verifyLikeRuntime(
                secret,
                "POST",
                RuntimeAgentExecutionInternalClient.EXECUTE_PATH,
                headers,
                tampered,
                System.currentTimeMillis()));
    }

    @Test
    void streamPathUsesSameDigestProtocol() throws Exception {
        String secret = "transport-contract-secret-32bytes!!";
        InternalServiceAuthSigner signer = new InternalServiceAuthSigner(secret);
        byte[] payload = envelopeBytes("EMBED_SESSION", "embed-user", "hello");
        Map<String, String> headers = signer.sign(
                "POST",
                RuntimeAgentExecutionInternalClient.EXECUTE_STREAM_PATH,
                "EMBED_SESSION",
                "embed-user",
                payload);
        assertTrue(verifyLikeRuntime(
                secret,
                "POST",
                RuntimeAgentExecutionInternalClient.EXECUTE_STREAM_PATH,
                headers,
                payload,
                System.currentTimeMillis()));
        // signed userId vs body identity mismatch is checked by Runtime controller after auth;
        // digest still binds the transmitted envelope bytes including identity payload.
        assertEquals(
                headers.get(InternalServiceAuthHeaders.BODY_SHA256),
                InternalServiceHmac.bodySha256Hex(payload));
    }

    @Test
    void emptyBodyAndFieldOrderStableDigest() throws Exception {
        byte[] empty = "{}".getBytes(StandardCharsets.UTF_8);
        assertEquals(
                InternalServiceHmac.bodySha256Hex(empty),
                InternalServiceHmac.bodySha256Hex("{}".getBytes(StandardCharsets.UTF_8)));
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("body", Map.of("agentId", "a", "message", "m"));
        a.put("identity", Map.of("source", "AGENT", "userId", "1"));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("identity", Map.of("source", "AGENT", "userId", "1"));
        b.put("body", Map.of("agentId", "a", "message", "m"));
        assertFalse(InternalServiceHmac.digestEqualsConstantTime(
                InternalServiceHmac.bodySha256Hex(objectMapper.writeValueAsBytes(a)),
                InternalServiceHmac.bodySha256Hex(objectMapper.writeValueAsBytes(b))));
    }

    /**
     * Mirrors Runtime {@code InternalServiceAuthVerifier} using only shared HMAC helpers.
     */
    private static boolean verifyLikeRuntime(String secret,
                                             String method,
                                             String path,
                                             Map<String, String> headers,
                                             byte[] bodyBytes,
                                             long nowMillis) {
        Set<String> callers = Set.of(InternalServiceAuthHeaders.CALLER_CONTROL);
        String caller = headers.get(InternalServiceAuthHeaders.CALLER);
        String source = headers.get(InternalServiceAuthHeaders.IDENTITY_SOURCE);
        String userId = headers.getOrDefault(InternalServiceAuthHeaders.IDENTITY_USER_ID, "");
        String timestamp = headers.get(InternalServiceAuthHeaders.TIMESTAMP);
        String nonce = headers.get(InternalServiceAuthHeaders.NONCE);
        String bodyShaHeader = headers.get(InternalServiceAuthHeaders.BODY_SHA256);
        String signature = headers.get(InternalServiceAuthHeaders.SIGNATURE);
        if (caller == null || source == null || timestamp == null || nonce == null
                || bodyShaHeader == null || signature == null) {
            return false;
        }
        if (!callers.contains(caller.trim())) {
            return false;
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException ex) {
            return false;
        }
        if (Math.abs(nowMillis - ts) > 300_000L) {
            return false;
        }
        String actual = InternalServiceHmac.bodySha256Hex(bodyBytes);
        if (!InternalServiceHmac.digestEqualsConstantTime(actual, bodyShaHeader)) {
            return false;
        }
        String canonical = InternalServiceHmac.canonical(
                method, path, caller.trim(), source.trim(), userId.trim(),
                timestamp.trim(), nonce.trim(), actual);
        return InternalServiceHmac.verifyConstantTime(secret, canonical, signature);
    }

    private byte[] envelopeBytes(String source, String userId, String message) throws Exception {
        Map<String, Object> envelope = new LinkedHashMap<>();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agentId", "demo");
        body.put("message", message);
        body.put("userId", "attacker");
        envelope.put("body", body);
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("source", source);
        identity.put("userId", userId);
        envelope.put("identity", identity);
        return objectMapper.writeValueAsBytes(envelope);
    }
}
