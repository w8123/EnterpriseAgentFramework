package com.enterprise.ai.runtime.internalauth;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Component
public class InternalServiceAuthVerifier {

    private static final Set<String> ALLOWED_CALLERS = Set.of(InternalServiceAuthHeaders.CALLER_CONTROL);
    private static final Set<String> ALLOWED_SOURCES = Set.of(
            "AGENT",
            "EMBED_SESSION",
            InternalServiceAuthHeaders.IDENTITY_SOURCE_A2A_REMOTE_AGENT,
            InternalServiceAuthHeaders.IDENTITY_SOURCE_MCP_REMOTE_CLIENT,
            InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION);

    private final InternalServiceAuthProperties properties;
    private final InternalAuthNonceStore nonceStore;

    public InternalServiceAuthVerifier(InternalServiceAuthProperties properties,
                                       InternalAuthNonceStore nonceStore) {
        this.properties = properties;
        this.nonceStore = nonceStore;
    }

    public Optional<VerifiedInternalServiceAuth> verify(String method,
                                                        String path,
                                                        String caller,
                                                        String identitySource,
                                                        String identityTenantId,
                                                        String identityUserId,
                                                        String timestamp,
                                                        String nonce,
                                                        String bodySha256Header,
                                                        String signature,
                                                        byte[] bodyBytes,
                                                        long nowMillis) {
        if (!properties.secretConfigured()) {
            return Optional.empty();
        }
        if (!StringUtils.hasText(caller)
                || !StringUtils.hasText(identitySource)
                || !StringUtils.hasText(timestamp)
                || !StringUtils.hasText(nonce)
                || !StringUtils.hasText(bodySha256Header)
                || !StringUtils.hasText(signature)) {
            return Optional.empty();
        }
        if (caller.length() > 64 || identitySource.length() > 32
                || (identityTenantId != null && identityTenantId.length() > 96)
                || (identityUserId != null && identityUserId.length() > 128)
                || timestamp.length() > 20 || nonce.length() > 128
                || bodySha256Header.length() != 64 || signature.length() != 64) {
            return Optional.empty();
        }
        String normalizedCaller = caller.trim();
        if (!ALLOWED_CALLERS.contains(normalizedCaller)) {
            return Optional.empty();
        }
        String source = identitySource.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_SOURCES.contains(source)) {
            return Optional.empty();
        }
        String userId = identityUserId == null ? "" : identityUserId.trim();
        String tenantId = StringUtils.hasText(identityTenantId) ? identityTenantId.trim() : "default";
        if (!tenantId.matches("[A-Za-z0-9._:-]+")
                || userId.chars().anyMatch(Character::isISOControl)) {
            return Optional.empty();
        }
        if ("EMBED_SESSION".equals(source) && !StringUtils.hasText(userId)) {
            return Optional.empty();
        }
        if (InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION.equals(source)
                && !StringUtils.hasText(userId)) {
            return Optional.empty();
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
        long skewMs = properties.skewSeconds() * 1000L;
        if (Math.abs(nowMillis - ts) > skewMs) {
            return Optional.empty();
        }
        String actualBodyDigest = InternalServiceHmac.bodySha256Hex(bodyBytes);
        if (!InternalServiceHmac.digestEqualsConstantTime(actualBodyDigest, bodySha256Header)) {
            return Optional.empty();
        }
        String canonical = StringUtils.hasText(identityTenantId)
                ? InternalServiceHmac.canonical(
                        method, path, normalizedCaller, source, tenantId, userId,
                        timestamp.trim(), nonce.trim(), actualBodyDigest)
                : InternalServiceHmac.canonical(
                        method, path, normalizedCaller, source, userId,
                        timestamp.trim(), nonce.trim(), actualBodyDigest);
        if (!InternalServiceHmac.verifyAnyConstantTime(
                properties.verificationSecrets(), canonical, signature)) {
            return Optional.empty();
        }
        if (!nonceStore.tryConsume(
                normalizedCaller,
                nonce.trim(),
                nowMillis,
                properties.nonceTtlSeconds(),
                properties.nonceMaxEntries())) {
            return Optional.empty();
        }
        return Optional.of(new VerifiedInternalServiceAuth(
                normalizedCaller, source, tenantId, StringUtils.hasText(userId) ? userId : null));
    }

    public Optional<VerifiedInternalServiceAuth> verify(String method,
                                                        String path,
                                                        String caller,
                                                        String identitySource,
                                                        String identityUserId,
                                                        String timestamp,
                                                        String nonce,
                                                        String bodySha256Header,
                                                        String signature,
                                                        byte[] bodyBytes,
                                                        long nowMillis) {
        return verify(method, path, caller, identitySource, null, identityUserId,
                timestamp, nonce, bodySha256Header, signature, bodyBytes, nowMillis);
    }
}
