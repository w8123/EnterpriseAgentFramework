package com.enterprise.ai.control.internalauth;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.common.internalauth.InternalServiceSecretRing;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

/** Verifies Runtime → Control HMAC requests with timestamp and nonce replay protection. */
@Component
public class ControlInternalServiceAuthVerifier {

    private final List<String> verificationSecrets;
    private final long skewMillis;
    private final long nonceTtlSeconds;
    private final int nonceMaxEntries;
    private final int maxBodyBytes;
    private final ControlInternalAuthNonceStore nonceStore;

    public ControlInternalServiceAuthVerifier(
            ControlInternalAuthNonceStore nonceStore,
            @Value("${reachai.internal.service-secret:${REACHAI_INTERNAL_SERVICE_SECRET:}}")
            String activeSecret,
            @Value("${reachai.internal.accepted-service-secrets:${REACHAI_INTERNAL_SERVICE_ACCEPTED_SECRETS:}}")
            String acceptedSecrets,
            @Value("${reachai.internal.auth.skew-seconds:300}") long skewSeconds,
            @Value("${reachai.internal.auth.nonce-ttl-seconds:600}") long nonceTtlSeconds,
            @Value("${reachai.internal.auth.nonce-max-entries:100000}") int nonceMaxEntries,
            @Value("${reachai.internal.auth.max-body-bytes:1048576}") int maxBodyBytes) {
        this.verificationSecrets = InternalServiceSecretRing.accepted(activeSecret, acceptedSecrets);
        if (skewSeconds < 1L || nonceTtlSeconds < 2L * skewSeconds
                || nonceMaxEntries < 1 || maxBodyBytes < 1) {
            throw new IllegalStateException("Invalid ReachAI Control internal authentication limits");
        }
        this.nonceStore = nonceStore;
        this.skewMillis = skewSeconds * 1000L;
        this.nonceTtlSeconds = nonceTtlSeconds;
        this.nonceMaxEntries = nonceMaxEntries;
        this.maxBodyBytes = maxBodyBytes;
    }

    public void requireRuntime(HttpServletRequest request, byte[] exactBody) {
        if (verificationSecrets.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "ReachAI internal service authentication is not configured");
        }
        byte[] body = exactBody == null ? new byte[0] : exactBody;
        if (body.length > maxBodyBytes || request == null) {
            reject();
        }
        String caller = header(request, InternalServiceAuthHeaders.CALLER, 64);
        String timestamp = header(request, InternalServiceAuthHeaders.TIMESTAMP, 20);
        String nonce = header(request, InternalServiceAuthHeaders.NONCE, 128);
        String source = header(request, InternalServiceAuthHeaders.IDENTITY_SOURCE, 64);
        String tenantId = header(request, InternalServiceAuthHeaders.IDENTITY_TENANT_ID, 96);
        String userId = optionalHeader(request, InternalServiceAuthHeaders.IDENTITY_USER_ID, 128);
        String claimedBodySha = header(request, InternalServiceAuthHeaders.BODY_SHA256, 64)
                .toLowerCase(Locale.ROOT);
        String signature = header(request, InternalServiceAuthHeaders.SIGNATURE, 64)
                .toLowerCase(Locale.ROOT);
        if (!InternalServiceAuthHeaders.CALLER_RUNTIME.equals(caller)
                || !InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_UNTRUSTED.equals(source)
                || !"default".equals(tenantId)
                || StringUtils.hasText(userId)
                || !claimedBodySha.matches("[0-9a-f]{64}")
                || !signature.matches("[0-9a-f]{64}")) {
            reject();
        }
        long requestTime;
        try {
            requestTime = Long.parseLong(timestamp);
        } catch (NumberFormatException exception) {
            reject();
            return;
        }
        long now = System.currentTimeMillis();
        if (Math.abs(now - requestTime) > skewMillis) {
            reject();
        }
        String actualBodySha = InternalServiceHmac.bodySha256Hex(body);
        if (!InternalServiceHmac.digestEqualsConstantTime(actualBodySha, claimedBodySha)) {
            reject();
        }
        String canonical = InternalServiceHmac.canonical(
                request.getMethod(), request.getRequestURI(), caller, source, tenantId, "",
                timestamp, nonce, actualBodySha);
        if (!InternalServiceHmac.verifyAnyConstantTime(verificationSecrets, canonical, signature)) {
            reject();
        }
        try {
            if (nonceStore == null || !nonceStore.tryConsume(
                    caller, nonce, now, nonceTtlSeconds, nonceMaxEntries)) {
                reject();
            }
        } catch (ResponseStatusException expected) {
            throw expected;
        } catch (RuntimeException storeFailure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "ReachAI Control internal anti-replay store is unavailable", storeFailure);
        }
    }

    private String header(HttpServletRequest request, String name, int maxLength) {
        String value = optionalHeader(request, name, maxLength);
        if (!StringUtils.hasText(value)) reject();
        return value;
    }

    private String optionalHeader(HttpServletRequest request, String name, int maxLength) {
        String value = request.getHeader(name);
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maxLength || normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0) {
            reject();
        }
        return normalized;
    }

    private void reject() {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "valid Runtime internal authentication is required");
    }
}
