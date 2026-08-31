package com.enterprise.ai.runtime.client.control;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeControlInternalAuthSignerTest {

    @Test
    void signsTheExactRequestBytesWithTheRuntimeIdentityContract() {
        String secret = "runtime-control-test-secret";
        byte[] body = "{\"text\":\"企业任务\"}".getBytes(StandardCharsets.UTF_8);
        RuntimeControlInternalAuthSigner signer = new RuntimeControlInternalAuthSigner(secret);

        Map<String, String> headers = signer.sign(
                "POST", RuntimeA2aControlClient.SEND_PATH, body);

        assertEquals(InternalServiceAuthHeaders.CALLER_RUNTIME,
                headers.get(InternalServiceAuthHeaders.CALLER));
        assertEquals(InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_UNTRUSTED,
                headers.get(InternalServiceAuthHeaders.IDENTITY_SOURCE));
        assertEquals(InternalServiceHmac.bodySha256Hex(body),
                headers.get(InternalServiceAuthHeaders.BODY_SHA256));
        String canonical = InternalServiceHmac.canonical(
                "POST", RuntimeA2aControlClient.SEND_PATH,
                headers.get(InternalServiceAuthHeaders.CALLER),
                headers.get(InternalServiceAuthHeaders.IDENTITY_SOURCE),
                headers.get(InternalServiceAuthHeaders.IDENTITY_TENANT_ID),
                headers.get(InternalServiceAuthHeaders.IDENTITY_USER_ID),
                headers.get(InternalServiceAuthHeaders.TIMESTAMP),
                headers.get(InternalServiceAuthHeaders.NONCE),
                headers.get(InternalServiceAuthHeaders.BODY_SHA256));
        assertTrue(InternalServiceHmac.verifyConstantTime(
                secret, canonical, headers.get(InternalServiceAuthHeaders.SIGNATURE)));
    }

    @Test
    void failsClosedWithoutAServiceSecret() {
        RuntimeControlInternalAuthSigner signer = new RuntimeControlInternalAuthSigner(" ");
        assertThrows(IllegalStateException.class,
                () -> signer.sign("POST", RuntimeA2aControlClient.SEND_PATH, new byte[0]));
    }
}
