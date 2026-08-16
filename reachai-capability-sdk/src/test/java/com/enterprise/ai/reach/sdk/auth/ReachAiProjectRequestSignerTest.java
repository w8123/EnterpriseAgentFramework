package com.enterprise.ai.reach.sdk.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReachAiProjectRequestSignerTest {

    @Test
    void signsMethodPathProjectCredentialAndExactBodyBytes() {
        byte[] body = "{\"items\":[1]}".getBytes(StandardCharsets.UTF_8);
        ReachAiProjectRequestHeaders headers = ReachAiProjectRequestSigner.sign(
                "rak_orders", "ras_secret", "orders", "POST",
                "/api/knowledge-ingress/projects/orders/biz-index/orders_idx/batch",
                body, "1700000000000", "nonce-1");

        String canonical = ReachAiProjectRequestSigner.canonical(
                "POST",
                "/api/knowledge-ingress/projects/orders/biz-index/orders_idx/batch",
                "orders", "rak_orders", "1700000000000", "nonce-1",
                ReachAiProjectRequestSigner.bodySha256Hex(body));
        assertEquals(ReachAiSigner.sign("ras_secret", canonical), headers.getSignature());
        assertEquals(headers.getBodySha256(),
                headers.toHttpHeaders().get(ReachAiProjectRequestHeaders.BODY_SHA256));
    }

    @Test
    void bodyMutationChangesDigestAndSignature() {
        ReachAiProjectRequestHeaders first = ReachAiProjectRequestSigner.sign(
                "key", "secret", "project", "POST", "/api/test",
                "a".getBytes(StandardCharsets.UTF_8), "1", "n");
        ReachAiProjectRequestHeaders second = ReachAiProjectRequestSigner.sign(
                "key", "secret", "project", "POST", "/api/test",
                "b".getBytes(StandardCharsets.UTF_8), "1", "n");

        assertNotEquals(first.getBodySha256(), second.getBodySha256());
        assertNotEquals(first.getSignature(), second.getSignature());
    }

    @Test
    void rejectsAmbiguousCanonicalPath() {
        assertThrows(IllegalArgumentException.class, () -> ReachAiProjectRequestSigner.sign(
                "key", "secret", "project", "POST", "/api/test?x=1",
                new byte[0], "1", "n"));
    }
}
