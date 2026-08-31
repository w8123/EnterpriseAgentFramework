package com.enterprise.ai.capability.internalauth;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class CapabilityInternalAuthVerifier {

    private static final Set<String> FORBIDDEN_EXTRA_IDENTITY_FIELDS = Set.of(
            "userId", "globalUserId", "userName", "deptId", "deptName", "roles", "attributes");
    private static final Set<String> ALL_IDENTITY_FIELDS = Set.of(
            "tenantId", "userId", "externalUserId", "globalUserId", "userName",
            "deptId", "deptName", "roles", "attributes");

    private final CapabilityInternalAuthProperties properties;
    private final CapabilityInternalAuthNonceStore nonceStore;
    private final ObjectMapper objectMapper;

    public CapabilityInternalAuthVerifier(CapabilityInternalAuthProperties properties,
                                          CapabilityInternalAuthNonceStore nonceStore,
                                          ObjectMapper objectMapper) {
        this.properties = properties;
        this.nonceStore = nonceStore;
        this.objectMapper = objectMapper;
    }

    /** Existing Control -> Capability enrollment protocol (V1 canonical form). */
    public Optional<CapabilityVerifiedInternalServiceAuth> verify(String method, String path,
                                                                  String caller, String source, String userId,
                                                                  String timestamp, String nonce,
                                                                  String bodyDigest, String signature,
                                                                  byte[] body, long nowMillis) {
        if (!properties.secretConfigured()
                || !InternalServiceAuthHeaders.CALLER_CONTROL.equals(caller)
                || !"PLATFORM_SESSION".equalsIgnoreCase(source)
                || !StringUtils.hasText(userId)
                || !StringUtils.hasText(timestamp) || !StringUtils.hasText(nonce)
                || !StringUtils.hasText(bodyDigest) || !StringUtils.hasText(signature)) {
            return Optional.empty();
        }
        long requestMillis;
        try {
            requestMillis = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException invalid) {
            return Optional.empty();
        }
        if (Math.abs(nowMillis - requestMillis) > properties.skewSeconds() * 1000L) {
            return Optional.empty();
        }
        String actualDigest = InternalServiceHmac.bodySha256Hex(body);
        if (!InternalServiceHmac.digestEqualsConstantTime(actualDigest, bodyDigest)) {
            return Optional.empty();
        }
        String canonical = InternalServiceHmac.canonical(method, path, caller, source, userId,
                timestamp.trim(), nonce.trim(), actualDigest);
        if (!InternalServiceHmac.verifyAnyConstantTime(properties.verificationSecrets(), canonical, signature)
                || !nonceStore.tryConsume(caller, nonce.trim(), nowMillis, properties.nonceTtlSeconds(),
                properties.nonceMaxEntries())) {
            return Optional.empty();
        }
        return Optional.of(new CapabilityVerifiedInternalServiceAuth(
                caller, "PLATFORM_SESSION", null, userId.trim()));
    }

    /** Runtime -> Capability Tool execution protocol (V2 canonical form + body identity binding). */
    public Optional<CapabilityVerifiedInternalServiceAuth> verifyToolExecution(
            String method, String path,
            String caller, String source, String tenantId, String userId,
            String timestamp, String nonce,
            String bodyDigest, String signature,
            byte[] body, long nowMillis) {
        String normalizedSource = normalized(source).toUpperCase(java.util.Locale.ROOT);
        String normalizedTenant = normalized(tenantId);
        String normalizedUser = normalized(userId);
        boolean trusted = InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_TRUSTED.equals(normalizedSource);
        boolean untrusted = InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_UNTRUSTED.equals(normalizedSource);
        if (!properties.secretConfigured()
                || !"POST".equalsIgnoreCase(method)
                || (!CapabilityInternalAuthFilter.isToolExecutionPath(path)
                && !CapabilityInternalAuthFilter.CAPABILITY_INVOCATION_PATH.equals(path))
                || !InternalServiceAuthHeaders.CALLER_RUNTIME.equals(normalized(caller))
                || (!trusted && !untrusted)
                || (trusted && !StringUtils.hasText(normalizedUser))
                || (untrusted && (StringUtils.hasText(normalizedTenant) || StringUtils.hasText(normalizedUser)))
                || !StringUtils.hasText(timestamp) || !StringUtils.hasText(nonce)
                || !StringUtils.hasText(bodyDigest) || !StringUtils.hasText(signature)) {
            return Optional.empty();
        }
        long requestMillis;
        try {
            requestMillis = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException invalid) {
            return Optional.empty();
        }
        if (Math.abs(nowMillis - requestMillis) > properties.skewSeconds() * 1000L) {
            return Optional.empty();
        }
        String actualDigest = InternalServiceHmac.bodySha256Hex(body);
        if (!InternalServiceHmac.digestEqualsConstantTime(actualDigest, bodyDigest)) {
            return Optional.empty();
        }
        String normalizedCaller = normalized(caller);
        String canonical = InternalServiceHmac.canonical(
                method, path, normalizedCaller, normalizedSource, normalizedTenant, normalizedUser,
                timestamp.trim(), nonce.trim(), actualDigest);
        if (!InternalServiceHmac.verifyAnyConstantTime(properties.verificationSecrets(), canonical, signature)
                || !identityMatchesBody(trusted, normalizedTenant, normalizedUser, body)
                || !nonceStore.tryConsume(normalizedCaller, nonce.trim(), nowMillis,
                properties.nonceTtlSeconds(), properties.nonceMaxEntries())) {
            return Optional.empty();
        }
        return Optional.of(new CapabilityVerifiedInternalServiceAuth(
                normalizedCaller, normalizedSource,
                StringUtils.hasText(normalizedTenant) ? normalizedTenant : null,
                StringUtils.hasText(normalizedUser) ? normalizedUser : null));
    }

    /** Control → Capability V2 request used only to verify a public project credential. */
    public Optional<CapabilityVerifiedInternalServiceAuth> verifyProjectRequestVerification(
            String method, String path,
            String caller, String source, String tenantId, String appKey,
            String timestamp, String nonce,
            String bodyDigest, String signature,
            byte[] body, long nowMillis) {
        String normalizedCaller = normalized(caller);
        String normalizedSource = normalized(source).toUpperCase(java.util.Locale.ROOT);
        String normalizedTenant = normalized(tenantId);
        String normalizedAppKey = normalized(appKey);
        if (!properties.secretConfigured()
                || !"POST".equalsIgnoreCase(method)
                || !CapabilityInternalAuthFilter.PROJECT_REQUEST_VERIFICATION_PATH.equals(path)
                || !InternalServiceAuthHeaders.CALLER_CONTROL.equals(normalizedCaller)
                || !InternalServiceAuthHeaders.IDENTITY_SOURCE_PROJECT_CREDENTIAL_VERIFICATION
                .equals(normalizedSource)
                || !StringUtils.hasText(normalizedTenant)
                || !StringUtils.hasText(normalizedAppKey)
                || !StringUtils.hasText(timestamp) || !StringUtils.hasText(nonce)
                || !StringUtils.hasText(bodyDigest) || !StringUtils.hasText(signature)) {
            return Optional.empty();
        }
        long requestMillis;
        try {
            requestMillis = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException invalid) {
            return Optional.empty();
        }
        if (Math.abs(nowMillis - requestMillis) > properties.skewSeconds() * 1000L) {
            return Optional.empty();
        }
        String actualDigest = InternalServiceHmac.bodySha256Hex(body);
        if (!InternalServiceHmac.digestEqualsConstantTime(actualDigest, bodyDigest)) {
            return Optional.empty();
        }
        String canonical = InternalServiceHmac.canonical(
                method, path, normalizedCaller, normalizedSource,
                normalizedTenant, normalizedAppKey,
                timestamp.trim(), nonce.trim(), actualDigest);
        if (!InternalServiceHmac.verifyAnyConstantTime(properties.verificationSecrets(), canonical, signature)
                || !projectVerificationIdentityMatchesBody(
                normalizedTenant, normalizedAppKey, body)
                || !nonceStore.tryConsume(normalizedCaller, nonce.trim(), nowMillis,
                properties.nonceTtlSeconds(), properties.nonceMaxEntries())) {
            return Optional.empty();
        }
        return Optional.of(new CapabilityVerifiedInternalServiceAuth(
                normalizedCaller, normalizedSource, normalizedTenant, normalizedAppKey));
    }

    private boolean identityMatchesBody(boolean trusted,
                                        String tenantId,
                                        String userId,
                                        byte[] body) {
        try {
            Map<String, Object> root = objectMapper.readValue(
                    body == null ? new byte[0] : body,
                    new TypeReference<Map<String, Object>>() { });
            Object rawContext = root.get("context");
            Map<?, ?> context = rawContext instanceof Map<?, ?> map ? map : Map.of();
            if (!trusted) {
                return ALL_IDENTITY_FIELDS.stream().noneMatch(context::containsKey);
            }
            if (!tenantId.equals(normalized(context.get("tenantId")))
                    || !userId.equals(normalized(context.get("externalUserId")))) {
                return false;
            }
            return FORBIDDEN_EXTRA_IDENTITY_FIELDS.stream().noneMatch(context::containsKey);
        } catch (Exception invalidJson) {
            return false;
        }
    }

    private boolean projectVerificationIdentityMatchesBody(String projectCode,
                                                           String appKey,
                                                           byte[] body) {
        try {
            JsonNode root = objectMapper.readTree(body == null ? new byte[0] : body);
            return root != null
                    && projectCode.equals(normalized(root.path("projectCode").asText(null)))
                    && appKey.equals(normalized(root.path("appKey").asText(null)));
        } catch (Exception invalidJson) {
            return false;
        }
    }

    private static String normalized(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
