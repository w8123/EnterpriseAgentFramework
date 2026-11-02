package com.enterprise.ai.control.identity;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlatformAiApprovalCipherTest {
    @Test
    void encryptsWithUniqueNoncesAndBindsThePayloadToItsApproval() {
        var cipher = new PlatformAiApprovalCipher("fixture-key", Base64.getEncoder().encodeToString(new byte[32]));
        byte[] payload = "sensitive fixture".getBytes(StandardCharsets.UTF_8);
        var first = cipher.encrypt(payload, "approval:1");
        var second = cipher.encrypt(payload, "approval:1");
        assertNotEquals(first.nonce(), second.nonce());
        assertNotEquals(first.ciphertext(), second.ciphertext());
        assertArrayEquals(payload, cipher.decrypt(first, "approval:1"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class,
                () -> cipher.decrypt(first, "approval:2")).getStatusCode());
        assertNotEquals(cipher.fingerprint("owner:1", payload), cipher.fingerprint("owner:2", payload));
        assertEquals(cipher.fingerprint("owner:1", payload), cipher.fingerprint("owner:1", payload));
    }

    @Test
    void failsClosedWhenTheKeyIsMissingOrInvalid() {
        for (var cipher : new PlatformAiApprovalCipher[]{new PlatformAiApprovalCipher("", ""),
                new PlatformAiApprovalCipher("id", "not-base64")}) {
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class,
                    () -> cipher.encrypt(new byte[]{1}, "approval:1")).getStatusCode());
        }
    }
}
