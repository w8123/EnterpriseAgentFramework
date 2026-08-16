package com.enterprise.ai.control.internalauth;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Signs Control→Runtime internal Agent execute requests (sync + SSE).
 * Must sign the exact HTTP body bytes that will be transmitted.
 */
@Component
public class InternalServiceAuthSigner {

    private final String serviceSecret;

    public InternalServiceAuthSigner(
            @Value("${reachai.internal.service-secret:${REACHAI_INTERNAL_SERVICE_SECRET:}}") String serviceSecret) {
        this.serviceSecret = serviceSecret == null ? "" : serviceSecret.trim();
    }

    public boolean secretConfigured() {
        return StringUtils.hasText(serviceSecret);
    }

    public Map<String, String> sign(String method,
                                    String path,
                                    String identitySource,
                                    String identityUserId,
                                    byte[] bodyBytes) {
        if (!secretConfigured()) {
            throw new IllegalStateException(
                    "REACHAI_INTERNAL_SERVICE_SECRET / reachai.internal.service-secret is required for trusted Runtime calls");
        }
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String source = identitySource == null ? "" : identitySource.trim();
        String userId = identityUserId == null ? "" : identityUserId.trim();
        String bodySha256 = InternalServiceHmac.bodySha256Hex(bodyBytes);
        String canonical = InternalServiceHmac.canonical(
                method,
                path,
                InternalServiceAuthHeaders.CALLER_CONTROL,
                source,
                userId,
                timestamp,
                nonce,
                bodySha256);
        String signature = InternalServiceHmac.sign(serviceSecret, canonical);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(InternalServiceAuthHeaders.CALLER, InternalServiceAuthHeaders.CALLER_CONTROL);
        headers.put(InternalServiceAuthHeaders.TIMESTAMP, timestamp);
        headers.put(InternalServiceAuthHeaders.NONCE, nonce);
        headers.put(InternalServiceAuthHeaders.IDENTITY_SOURCE, source);
        headers.put(InternalServiceAuthHeaders.IDENTITY_USER_ID, userId);
        headers.put(InternalServiceAuthHeaders.BODY_SHA256, bodySha256);
        headers.put(InternalServiceAuthHeaders.SIGNATURE, signature);
        return headers;
    }

    public Map<String, String> sign(String method,
                                    String path,
                                    String identitySource,
                                    String identityTenantId,
                                    String identityUserId,
                                    byte[] bodyBytes) {
        return signBodyDigest(method, path, identitySource, identityTenantId, identityUserId,
                InternalServiceHmac.bodySha256Hex(bodyBytes));
    }

    /**
     * Signs a request whose exact outbound body has already been streamed and
     * hashed. This keeps large multipart service hops bounded instead of
     * materializing the complete payload in heap merely to calculate HMAC.
     */
    public Map<String, String> signBodyDigest(String method,
                                              String path,
                                              String identitySource,
                                              String identityTenantId,
                                              String identityUserId,
                                              String bodySha256) {
        if (!secretConfigured()) {
            throw new IllegalStateException(
                    "REACHAI_INTERNAL_SERVICE_SECRET / reachai.internal.service-secret is required for trusted internal calls");
        }
        String normalizedDigest = bodySha256 == null ? "" : bodySha256.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalizedDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("bodySha256 must be a lowercase SHA-256 hex digest");
        }
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String source = identitySource == null ? "" : identitySource.trim();
        String tenantId = identityTenantId == null || identityTenantId.isBlank()
                ? "default" : identityTenantId.trim();
        String userId = identityUserId == null ? "" : identityUserId.trim();
        String canonical = InternalServiceHmac.canonical(
                method, path, InternalServiceAuthHeaders.CALLER_CONTROL,
                source, tenantId, userId, timestamp, nonce, normalizedDigest);
        String signature = InternalServiceHmac.sign(serviceSecret, canonical);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(InternalServiceAuthHeaders.CALLER, InternalServiceAuthHeaders.CALLER_CONTROL);
        headers.put(InternalServiceAuthHeaders.TIMESTAMP, timestamp);
        headers.put(InternalServiceAuthHeaders.NONCE, nonce);
        headers.put(InternalServiceAuthHeaders.IDENTITY_SOURCE, source);
        headers.put(InternalServiceAuthHeaders.IDENTITY_TENANT_ID, tenantId);
        headers.put(InternalServiceAuthHeaders.IDENTITY_USER_ID, userId);
        headers.put(InternalServiceAuthHeaders.BODY_SHA256, normalizedDigest);
        headers.put(InternalServiceAuthHeaders.SIGNATURE, signature);
        return headers;
    }
}
