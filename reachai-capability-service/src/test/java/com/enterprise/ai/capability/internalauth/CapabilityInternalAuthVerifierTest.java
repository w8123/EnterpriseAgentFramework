package com.enterprise.ai.capability.internalauth;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityInternalAuthVerifierTest {

    private static final String SECRET = "capability-verifier-contract-secret-32bytes";
    private static final String QUALIFIED_NAME = "bzjs20:team.memory.resolve";
    private static final String TOOL_PATH = "/internal/capability/tools/" + QUALIFIED_NAME + "/execute";
    private static final String INVOCATION_PATH = "/internal/capability/invocations";

    private CapabilityInternalAuthVerifier verifier;

    @ParameterizedTest
    @ValueSource(strings = {TOOL_PATH, INVOCATION_PATH})
    void tenantScopedInvocationBindsExactBodyWithoutBusinessUserAndRejectsReplay(String path) throws Exception {
        byte[] body = new ObjectMapper().writeValueAsBytes(Map.of("context", Map.of("tenantId", "tenant-a")));
        Signed signed = signTool(path, body, "RUNTIME_TRUSTED_TENANT", "tenant-a", "", SECRET);
        CapabilityVerifiedInternalServiceAuth verified = verifyTool(verifier, path, signed, body).orElseThrow();
        assertEquals("RUNTIME_TRUSTED_TENANT", verified.identitySource());
        assertEquals("tenant-a", verified.identityTenantId());
        assertNull(verified.identityUserId());
        assertTrue(verifyTool(verifier, path, signed, body).isEmpty());
    }

    @Test
    void tenantScopedInvocationRejectsEndUserAndRoleClaimsEvenWithValidSignature() throws Exception {
        for (String field : Set.of("userId", "externalUserId", "globalUserId", "userName", "deptId", "deptName", "roles", "attributes")) {
            byte[] body = new ObjectMapper().writeValueAsBytes(Map.of("context", Map.of("tenantId", "tenant-a", field, "forged")));
            Signed signed = signTool(INVOCATION_PATH, body, "RUNTIME_TRUSTED_TENANT", "tenant-a", "", SECRET);
            assertTrue(verifyTool(verifier, INVOCATION_PATH, signed, body).isEmpty(), field);
        }
    }

    @Test
    void tenantScopedInvocationRejectsMissingScopeIdentityMismatchAndUserHeader() throws Exception {
        for (String[] scopes : new String[][] {
                {"", "", ""}, {"tenant-a", "tenant-b", ""}, {"tenant-a", "tenant-a", "mcp-client-7"}
        }) {
            byte[] body = new ObjectMapper().writeValueAsBytes(Map.of("context", Map.of("tenantId", scopes[0])));
            Signed signed = signTool(INVOCATION_PATH, body, "RUNTIME_TRUSTED_TENANT", scopes[1], scopes[2], SECRET);
            assertTrue(verifyTool(verifier, INVOCATION_PATH, signed, body).isEmpty());
        }
    }

    @BeforeEach
    void setUp() {
        CapabilityInternalAuthProperties properties = new CapabilityInternalAuthProperties(
                SECRET, 300, 600, 10_000, 1_048_576);
        properties.validate();
        Set<String> consumed = ConcurrentHashMap.newKeySet();
        CapabilityInternalAuthNonceStore nonceStore = (caller, nonce, nowMillis, ttlSeconds, maxEntries) ->
                consumed.add(caller + "|" + nonce);
        verifier = new CapabilityInternalAuthVerifier(properties, nonceStore, new ObjectMapper());
    }

    @Test
    void trustedToolExecutionBindsSignatureTenantUserAndBodyAndRejectsReplay() {
        byte[] body = json("tenant-a", "user-7", false);
        Signed signed = signTool(body,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_TRUSTED,
                "tenant-a", "user-7");

        CapabilityVerifiedInternalServiceAuth verified = verifyTool(signed, body).orElseThrow();
        assertEquals(InternalServiceAuthHeaders.CALLER_RUNTIME, verified.caller());
        assertEquals("tenant-a", verified.identityTenantId());
        assertEquals("user-7", verified.identityUserId());

        assertTrue(verifyTool(signed, body).isEmpty(), "the same nonce must not be reusable");
    }

    @Test
    void canonicalInvocationPathUsesTheSameExactBodyIdentityBinding() {
        byte[] body = json("tenant-a", "user-7", false);
        Signed signed = signTool(INVOCATION_PATH, body,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_TRUSTED,
                "tenant-a", "user-7", SECRET);

        assertTrue(verifyTool(verifier, INVOCATION_PATH, signed, body).isPresent());
    }

    @Test
    void exactBodyDigestRejectsPayloadTampering() {
        byte[] original = json("tenant-a", "user-7", false);
        Signed signed = signTool(original,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_TRUSTED,
                "tenant-a", "user-7");
        byte[] tampered = json("tenant-a", "attacker", false);

        assertTrue(verifyTool(signed, tampered).isEmpty());
    }

    @Test
    void validHmacStillRejectsHeaderBodyIdentityMismatch() {
        byte[] body = json("tenant-a", "attacker", false);
        Signed signed = signTool(body,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_TRUSTED,
                "tenant-a", "user-7");

        assertTrue(verifyTool(signed, body).isEmpty());
    }

    @Test
    void untrustedCallCannotCarryAnyIdentityField() {
        byte[] body = json("forged-tenant", "forged-user", false);
        Signed signed = signTool(body,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_UNTRUSTED,
                "", "");

        assertTrue(verifyTool(signed, body).isEmpty());
    }

    @Test
    void trustedCallRejectsAdditionalCallerControlledIdentityClaims() {
        byte[] body = json("tenant-a", "user-7", true);
        Signed signed = signTool(body,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_TRUSTED,
                "tenant-a", "user-7");

        assertTrue(verifyTool(signed, body).isEmpty());
    }

    @Test
    void existingControlEnrollmentV1ProtocolRemainsAccepted() {
        byte[] body = "{\"projectCode\":\"bzjs20\"}".getBytes(StandardCharsets.UTF_8);
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String digest = InternalServiceHmac.bodySha256Hex(body);
        String canonical = InternalServiceHmac.canonical(
                "POST", CapabilityInternalAuthFilter.ENROLLMENT_PATH,
                InternalServiceAuthHeaders.CALLER_CONTROL, "PLATFORM_SESSION", "42",
                timestamp, nonce, digest);
        String signature = InternalServiceHmac.sign(SECRET, canonical);

        assertTrue(verifier.verify(
                "POST", CapabilityInternalAuthFilter.ENROLLMENT_PATH,
                InternalServiceAuthHeaders.CALLER_CONTROL, "PLATFORM_SESSION", "42",
                timestamp, nonce, digest, signature, body, System.currentTimeMillis()).isPresent());
    }

    @Test
    void projectCredentialVerificationBindsInternalIdentityToBody() {
        byte[] body = ("{\"projectCode\":\"orders\",\"appKey\":\"rak_orders\"," +
                "\"method\":\"POST\",\"path\":\"/api/knowledge-ingress/projects/orders/biz-index/orders_idx/batch\"}")
                .getBytes(StandardCharsets.UTF_8);
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String digest = InternalServiceHmac.bodySha256Hex(body);
        String canonical = InternalServiceHmac.canonical(
                "POST", CapabilityInternalAuthFilter.PROJECT_REQUEST_VERIFICATION_PATH,
                InternalServiceAuthHeaders.CALLER_CONTROL,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PROJECT_CREDENTIAL_VERIFICATION,
                "orders", "rak_orders", timestamp, nonce, digest);

        var verified = verifier.verifyProjectRequestVerification(
                "POST", CapabilityInternalAuthFilter.PROJECT_REQUEST_VERIFICATION_PATH,
                InternalServiceAuthHeaders.CALLER_CONTROL,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PROJECT_CREDENTIAL_VERIFICATION,
                "orders", "rak_orders", timestamp, nonce, digest,
                InternalServiceHmac.sign(SECRET, canonical), body, System.currentTimeMillis());

        assertTrue(verified.isPresent());
        assertEquals("orders", verified.orElseThrow().identityTenantId());
    }

    @Test
    void projectCredentialVerificationRejectsHeaderBodyProjectMismatch() {
        byte[] body = "{\"projectCode\":\"other\",\"appKey\":\"rak_orders\"}"
                .getBytes(StandardCharsets.UTF_8);
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String digest = InternalServiceHmac.bodySha256Hex(body);
        String canonical = InternalServiceHmac.canonical(
                "POST", CapabilityInternalAuthFilter.PROJECT_REQUEST_VERIFICATION_PATH,
                InternalServiceAuthHeaders.CALLER_CONTROL,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PROJECT_CREDENTIAL_VERIFICATION,
                "orders", "rak_orders", timestamp, nonce, digest);

        assertTrue(verifier.verifyProjectRequestVerification(
                "POST", CapabilityInternalAuthFilter.PROJECT_REQUEST_VERIFICATION_PATH,
                InternalServiceAuthHeaders.CALLER_CONTROL,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PROJECT_CREDENTIAL_VERIFICATION,
                "orders", "rak_orders", timestamp, nonce, digest,
                InternalServiceHmac.sign(SECRET, canonical), body,
                System.currentTimeMillis()).isEmpty());
    }

    @Test
    void toolExecutionAcceptsPreviousSecretDuringRotation() {
        String previous = "capability-previous-internal-secret-32bytes";
        CapabilityInternalAuthProperties properties = new CapabilityInternalAuthProperties(
                SECRET, previous, 300, 600, 10_000, 1_048_576);
        properties.validate();
        Set<String> consumed = ConcurrentHashMap.newKeySet();
        CapabilityInternalAuthVerifier rotatingVerifier = new CapabilityInternalAuthVerifier(
                properties,
                (caller, nonce, nowMillis, ttlSeconds, maxEntries) -> consumed.add(caller + "|" + nonce),
                new ObjectMapper());
        byte[] body = json("tenant-a", "user-7", false);
        Signed signed = signTool(body,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_TRUSTED,
                "tenant-a", "user-7", previous);

        assertTrue(verifyTool(rotatingVerifier, signed, body).isPresent());
    }

    @Test
    void productionProfileRejectsWeakVerificationRing() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("production");

        assertThrows(IllegalStateException.class,
                () -> new CapabilityInternalAuthProperties(
                        "short", "", 300, 600, 10_000, 1_048_576, production).validate());
        assertThrows(IllegalStateException.class,
                () -> new CapabilityInternalAuthProperties(
                        SECRET, "short", 300, 600, 10_000, 1_048_576, production).validate());

        new CapabilityInternalAuthProperties(
                SECRET, "previous-capability-internal-secret-32bytes",
                300, 600, 10_000, 1_048_576, production).validate();
    }

    private java.util.Optional<CapabilityVerifiedInternalServiceAuth> verifyTool(Signed signed, byte[] body) {
        return verifyTool(verifier, signed, body);
    }

    private java.util.Optional<CapabilityVerifiedInternalServiceAuth> verifyTool(
            CapabilityInternalAuthVerifier target, Signed signed, byte[] body) {
        return verifyTool(target, TOOL_PATH, signed, body);
    }

    private java.util.Optional<CapabilityVerifiedInternalServiceAuth> verifyTool(
            CapabilityInternalAuthVerifier target, String path, Signed signed, byte[] body) {
        Map<String, String> headers = signed.headers();
        return target.verifyToolExecution(
                "POST", path,
                headers.get(InternalServiceAuthHeaders.CALLER),
                headers.get(InternalServiceAuthHeaders.IDENTITY_SOURCE),
                headers.get(InternalServiceAuthHeaders.IDENTITY_TENANT_ID),
                headers.get(InternalServiceAuthHeaders.IDENTITY_USER_ID),
                headers.get(InternalServiceAuthHeaders.TIMESTAMP),
                headers.get(InternalServiceAuthHeaders.NONCE),
                headers.get(InternalServiceAuthHeaders.BODY_SHA256),
                headers.get(InternalServiceAuthHeaders.SIGNATURE),
                body, System.currentTimeMillis());
    }

    private Signed signTool(byte[] body, String source, String tenantId, String userId) {
        return signTool(body, source, tenantId, userId, SECRET);
    }

    private Signed signTool(byte[] body, String source, String tenantId, String userId,
                            String signingSecret) {
        return signTool(TOOL_PATH, body, source, tenantId, userId, signingSecret);
    }

    private Signed signTool(String path, byte[] body, String source, String tenantId, String userId,
                            String signingSecret) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String digest = InternalServiceHmac.bodySha256Hex(body);
        String canonical = InternalServiceHmac.canonical(
                "POST", path, InternalServiceAuthHeaders.CALLER_RUNTIME,
                source, tenantId, userId, timestamp, nonce, digest);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(InternalServiceAuthHeaders.CALLER, InternalServiceAuthHeaders.CALLER_RUNTIME);
        headers.put(InternalServiceAuthHeaders.IDENTITY_SOURCE, source);
        headers.put(InternalServiceAuthHeaders.IDENTITY_TENANT_ID, tenantId);
        headers.put(InternalServiceAuthHeaders.IDENTITY_USER_ID, userId);
        headers.put(InternalServiceAuthHeaders.TIMESTAMP, timestamp);
        headers.put(InternalServiceAuthHeaders.NONCE, nonce);
        headers.put(InternalServiceAuthHeaders.BODY_SHA256, digest);
        headers.put(InternalServiceAuthHeaders.SIGNATURE, InternalServiceHmac.sign(signingSecret, canonical));
        return new Signed(headers);
    }

    private byte[] json(String tenantId, String userId, boolean includeRoles) {
        String roles = includeRoles ? ",\"roles\":[\"ADMIN\"]" : "";
        return ("{\"input\":{\"teamId\":\"7\"},\"context\":{"
                + "\"tenantId\":\"" + tenantId + "\","
                + "\"externalUserId\":\"" + userId + "\"" + roles + "}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private record Signed(Map<String, String> headers) {
    }
}
