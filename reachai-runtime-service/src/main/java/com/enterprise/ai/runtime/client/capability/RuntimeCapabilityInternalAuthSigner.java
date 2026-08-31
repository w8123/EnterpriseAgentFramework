package com.enterprise.ai.runtime.client.capability;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Signs the exact Runtime -> Capability Tool-execution payload. */
@Component
public class RuntimeCapabilityInternalAuthSigner {

    public static final String TOOL_EXECUTE_PATH_TEMPLATE =
            "/internal/capability/tools/{qualifiedName}/execute";
    public static final String CAPABILITY_INVOCATION_PATH =
            "/internal/capability/invocations";

    private static final Pattern QUALIFIED_NAME = Pattern.compile("[A-Za-z0-9._:-]{1,200}");
    private static final int MAX_IDENTITY_LENGTH = 256;

    private final String serviceSecret;

    public RuntimeCapabilityInternalAuthSigner(
            @Value("${reachai.internal.service-secret:${REACHAI_INTERNAL_SERVICE_SECRET:}}") String serviceSecret) {
        this.serviceSecret = serviceSecret == null ? "" : serviceSecret.trim();
    }

    public Map<String, String> signToolExecute(String qualifiedName,
                                               String identitySource,
                                               String identityTenantId,
                                               String identityUserId,
                                               byte[] exactBody) {
        return sign(toolExecutePath(qualifiedName), identitySource,
                identityTenantId, identityUserId, exactBody);
    }

    public Map<String, String> signInvocation(String identitySource,
                                              String identityTenantId,
                                              String identityUserId,
                                              byte[] exactBody) {
        return sign(CAPABILITY_INVOCATION_PATH, identitySource,
                identityTenantId, identityUserId, exactBody);
    }

    private Map<String, String> sign(String path,
                                     String identitySource,
                                     String identityTenantId,
                                     String identityUserId,
                                     byte[] exactBody) {
        if (!StringUtils.hasText(serviceSecret)) {
            throw new IllegalStateException(
                    "REACHAI_INTERNAL_SERVICE_SECRET / reachai.internal.service-secret is required for Capability Tool calls");
        }
        String source = safeHeader(identitySource, "identitySource", false);
        if (!InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_TRUSTED.equals(source)
                && !InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_UNTRUSTED.equals(source)) {
            throw new IllegalArgumentException("unsupported Runtime Capability identity source");
        }
        String tenantId = safeHeader(identityTenantId, "identityTenantId", true);
        String userId = safeHeader(identityUserId, "identityUserId", true);
        if (InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_TRUSTED.equals(source)
                && !StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("trusted Runtime Capability calls require a user identity");
        }
        if (InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_UNTRUSTED.equals(source)
                && (StringUtils.hasText(tenantId) || StringUtils.hasText(userId))) {
            throw new IllegalArgumentException("untrusted Runtime Capability calls cannot carry an identity");
        }

        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String bodySha256 = InternalServiceHmac.bodySha256Hex(exactBody);
        String canonical = InternalServiceHmac.canonical(
                "POST", path, InternalServiceAuthHeaders.CALLER_RUNTIME,
                source, tenantId, userId, timestamp, nonce, bodySha256);
        String signature = InternalServiceHmac.sign(serviceSecret, canonical);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(InternalServiceAuthHeaders.CALLER, InternalServiceAuthHeaders.CALLER_RUNTIME);
        headers.put(InternalServiceAuthHeaders.TIMESTAMP, timestamp);
        headers.put(InternalServiceAuthHeaders.NONCE, nonce);
        headers.put(InternalServiceAuthHeaders.IDENTITY_SOURCE, source);
        headers.put(InternalServiceAuthHeaders.IDENTITY_TENANT_ID, tenantId);
        headers.put(InternalServiceAuthHeaders.IDENTITY_USER_ID, userId);
        headers.put(InternalServiceAuthHeaders.BODY_SHA256, bodySha256);
        headers.put(InternalServiceAuthHeaders.SIGNATURE, signature);
        return headers;
    }

    public static String toolExecutePath(String qualifiedName) {
        if (qualifiedName == null
                || !qualifiedName.equals(qualifiedName.trim())
                || !QUALIFIED_NAME.matcher(qualifiedName).matches()) {
            throw new IllegalArgumentException("qualifiedName contains unsupported path characters");
        }
        return "/internal/capability/tools/" + qualifiedName + "/execute";
    }

    private static String safeHeader(String value, String field, boolean optional) {
        String normalized = value == null ? "" : value.trim();
        if (!optional && !StringUtils.hasText(normalized)) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (normalized.length() > MAX_IDENTITY_LENGTH
                || normalized.indexOf('\r') >= 0
                || normalized.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
