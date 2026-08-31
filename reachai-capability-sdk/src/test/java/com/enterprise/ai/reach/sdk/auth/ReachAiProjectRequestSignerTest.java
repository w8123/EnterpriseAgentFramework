package com.enterprise.ai.reach.sdk.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReachAiProjectRequestSignerTest {

    /** Deterministic body and its SHA-256 hex digest, reused by the canonical assertions. */
    private static final String BODY = "{\"items\":[1]}";
    private static final String DIGEST =
            ReachAiProjectRequestSigner.bodySha256Hex(BODY.getBytes(StandardCharsets.UTF_8));

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

    @Test
    void normalizesMethodAndSerializesCanonicalInFixedOrder() {
        String canonical = ReachAiProjectRequestSigner.canonical(
                " post ", "/api/kb/index", " orders ", " rak_kb ",
                " 1700000000000 ", " nonce-1 ", DIGEST);

        assertEquals(
                "REACHAI_PROJECT_REQUEST_V1\nPOST\n/api/kb/index\norders\nrak_kb\n1700000000000\nnonce-1\n" + DIGEST,
                canonical);
    }

    @Test
    void digestIsLowercasedAndMustBeExactly64Hex() {
        String upper = DIGEST.toUpperCase(Locale.ENGLISH);
        String canonical = ReachAiProjectRequestSigner.canonical(
                "POST", "/api/kb", "orders", "rak_kb", "1700000000000", "nonce-1", upper);

        assertEquals(DIGEST, canonical.substring(canonical.lastIndexOf('\n') + 1));

        assertThrows(IllegalArgumentException.class,
                () -> ReachAiProjectRequestSigner.canonical(
                        "POST", "/api/kb", "orders", "rak_kb", "1700000000000", "nonce-1",
                        DIGEST.substring(0, 63) + "g"),
                "64-char digest containing a non-hex character must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> ReachAiProjectRequestSigner.canonical(
                        "POST", "/api/kb", "orders", "rak_kb", "1700000000000", "nonce-1",
                        DIGEST.substring(1)),
                "63-char digest must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> ReachAiProjectRequestSigner.canonical(
                        "POST", "/api/kb", "orders", "rak_kb", "1700000000000", "nonce-1",
                        DIGEST + "0"),
                "65-char digest must be rejected");
    }

    @Test
    void bodySha256HexTreatsNullAndEmptyBodyIdentically() {
        String nullDigest = ReachAiProjectRequestSigner.bodySha256Hex(null);
        String emptyDigest = ReachAiProjectRequestSigner.bodySha256Hex(new byte[0]);

        assertEquals(emptyDigest, nullDigest);
        assertEquals(64, emptyDigest.length());
        assertTrue(emptyDigest.matches("[0-9a-f]{64}"));
    }

    @Test
    void rejectsRelativeQueryFragmentDotDotAndCrLfPaths() {
        String[] invalid = {
                "api/test",                // relative path (no leading slash)
                "/api/test?x=1",           // query
                "/api/test#frag",          // fragment
                "/api/../secret",          // dot-dot traversal
                "/api/test\radmin",        // carriage return
                "/api/test\nadmin"         // line feed
        };
        for (String path : invalid) {
            assertThrows(IllegalArgumentException.class,
                    () -> ReachAiProjectRequestSigner.sign(
                            "key", "secret", "project", "POST", path, new byte[0], "1", "n"),
                    "path should be rejected: " + path);
        }
    }

    @Test
    void rejectsBlankOrCrLfRequiredFields() {
        // method
        assertCanonicalRejected(null, "/api/kb", "orders", "rak_kb", "ts", "nonce");
        assertCanonicalRejected("  ", "/api/kb", "orders", "rak_kb", "ts", "nonce");
        assertCanonicalRejected("PO\rST", "/api/kb", "orders", "rak_kb", "ts", "nonce");
        assertCanonicalRejected("PO\nST", "/api/kb", "orders", "rak_kb", "ts", "nonce");
        // projectCode
        assertCanonicalRejected("POST", "/api/kb", null, "rak_kb", "ts", "nonce");
        assertCanonicalRejected("POST", "/api/kb", "  ", "rak_kb", "ts", "nonce");
        assertCanonicalRejected("POST", "/api/kb", "or\rder", "rak_kb", "ts", "nonce");
        // appKey
        assertCanonicalRejected("POST", "/api/kb", "orders", null, "ts", "nonce");
        assertCanonicalRejected("POST", "/api/kb", "orders", "  ", "ts", "nonce");
        assertCanonicalRejected("POST", "/api/kb", "orders", "rak_\nkb", "ts", "nonce");
        // timestamp
        assertCanonicalRejected("POST", "/api/kb", "orders", "rak_kb", null, "nonce");
        assertCanonicalRejected("POST", "/api/kb", "orders", "rak_kb", "  ", "nonce");
        assertCanonicalRejected("POST", "/api/kb", "orders", "rak_kb", "ts\rx", "nonce");
        // nonce
        assertCanonicalRejected("POST", "/api/kb", "orders", "rak_kb", "ts", null);
        assertCanonicalRejected("POST", "/api/kb", "orders", "rak_kb", "ts", "  ");
        assertCanonicalRejected("POST", "/api/kb", "orders", "rak_kb", "ts", "no\nce");
    }

    @Test
    void signatureChangesWhenAnySignedDimensionChanges() {
        ReachAiProjectRequestHeaders base = ReachAiProjectRequestSigner.sign(
                "key", "secret", "project", "POST", "/api/test",
                "a".getBytes(StandardCharsets.UTF_8), "1700000000000", "nonce-1");

        assertNotEquals(base.getSignature(), ReachAiProjectRequestSigner.sign(
                "key", "secret", "project", "GET", "/api/test",
                "a".getBytes(StandardCharsets.UTF_8), "1700000000000", "nonce-1").getSignature());

        assertNotEquals(base.getSignature(), ReachAiProjectRequestSigner.sign(
                "key", "secret", "project", "POST", "/api/other",
                "a".getBytes(StandardCharsets.UTF_8), "1700000000000", "nonce-1").getSignature());

        assertNotEquals(base.getSignature(), ReachAiProjectRequestSigner.sign(
                "key", "secret", "project2", "POST", "/api/test",
                "a".getBytes(StandardCharsets.UTF_8), "1700000000000", "nonce-1").getSignature());

        assertNotEquals(base.getSignature(), ReachAiProjectRequestSigner.sign(
                "key2", "secret", "project", "POST", "/api/test",
                "a".getBytes(StandardCharsets.UTF_8), "1700000000000", "nonce-1").getSignature());

        assertNotEquals(base.getSignature(), ReachAiProjectRequestSigner.sign(
                "key", "secret", "project", "POST", "/api/test",
                "a".getBytes(StandardCharsets.UTF_8), "1700000000001", "nonce-1").getSignature());

        assertNotEquals(base.getSignature(), ReachAiProjectRequestSigner.sign(
                "key", "secret", "project", "POST", "/api/test",
                "a".getBytes(StandardCharsets.UTF_8), "1700000000000", "nonce-2").getSignature());

        assertNotEquals(base.getSignature(), ReachAiProjectRequestSigner.sign(
                "key", "secret", "project", "POST", "/api/test",
                "b".getBytes(StandardCharsets.UTF_8), "1700000000000", "nonce-1").getSignature());
    }

    @Test
    void toHttpHeadersExposesExactlyFiveProtocolHeaders() {
        ReachAiProjectRequestHeaders headers = ReachAiProjectRequestSigner.sign(
                "rak_orders", "ras_secret", "orders", "POST", "/api/kb/index",
                BODY.getBytes(StandardCharsets.UTF_8), "1700000000000", "nonce-1");

        Map<String, String> http = headers.toHttpHeaders();

        assertEquals(5, http.size());
        assertEquals("rak_orders", http.get("X-ReachAI-App-Key"));
        assertEquals("1700000000000", http.get("X-ReachAI-Timestamp"));
        assertEquals("nonce-1", http.get("X-ReachAI-Nonce"));
        assertEquals(headers.getBodySha256(), http.get("X-ReachAI-Body-SHA256"));
        assertEquals(headers.getSignature(), http.get("X-ReachAI-Signature"));
    }

    private static void assertCanonicalRejected(String method, String path, String projectCode,
                                                String appKey, String timestamp, String nonce) {
        assertThrows(IllegalArgumentException.class, () -> ReachAiProjectRequestSigner.canonical(
                        method, path, projectCode, appKey, timestamp, nonce, DIGEST),
                "canonical should reject method=" + method + " path=" + path
                        + " projectCode=" + projectCode + " appKey=" + appKey
                        + " timestamp=" + timestamp + " nonce=" + nonce);
    }
}
