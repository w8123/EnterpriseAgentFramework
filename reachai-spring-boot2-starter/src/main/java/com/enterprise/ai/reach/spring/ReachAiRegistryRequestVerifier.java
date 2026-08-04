package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.auth.ReachAiSigner;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class ReachAiRegistryRequestVerifier {

    private static final long MAX_CLOCK_SKEW_MILLIS = 300000L;

    private final ReachAiRegistryProperties properties;
    private final ConcurrentMap<String, Long> acceptedNonces = new ConcurrentHashMap<String, Long>();

    public ReachAiRegistryRequestVerifier(ReachAiRegistryProperties properties) {
        this.properties = properties;
    }

    public void verify(String appKey, String timestamp, String nonce, String signature) {
        if (properties == null
                || !StringUtils.hasText(properties.getProject().getCode())
                || !StringUtils.hasText(properties.getRegistry().getAppKey())
                || !StringUtils.hasText(properties.getRegistry().getAppSecret())) {
            throw unauthorized("ReachAI registry credentials are not configured", null);
        }
        if (!StringUtils.hasText(appKey)
                || !StringUtils.hasText(timestamp)
                || !StringUtils.hasText(nonce)
                || !StringUtils.hasText(signature)) {
            throw unauthorized("ReachAI registry signature headers are required", null);
        }
        if (!properties.getRegistry().getAppKey().trim().equals(appKey.trim())) {
            throw unauthorized("ReachAI registry appKey is invalid", null);
        }
        validateTimestamp(timestamp);
        String message = properties.getProject().getCode().trim() + "\n" + timestamp.trim() + "\n" + nonce.trim();
        String expected = ReachAiSigner.sign(properties.getRegistry().getAppSecret(), message);
        if (!expected.equalsIgnoreCase(signature.trim())) {
            throw unauthorized("ReachAI registry signature is invalid", null);
        }
        rejectReplay(nonce.trim());
    }

    private void validateTimestamp(String timestamp) {
        try {
            long requestTime = Long.parseLong(timestamp.trim());
            long now = System.currentTimeMillis();
            if (requestTime < now - MAX_CLOCK_SKEW_MILLIS
                    || requestTime > now + MAX_CLOCK_SKEW_MILLIS) {
                throw unauthorized("ReachAI registry signature timestamp is expired", null);
            }
        } catch (NumberFormatException ex) {
            throw unauthorized("ReachAI registry signature timestamp is invalid", ex);
        }
    }

    private void rejectReplay(String nonce) {
        long now = System.currentTimeMillis();
        long oldestAcceptedAt = now - MAX_CLOCK_SKEW_MILLIS;
        for (Map.Entry<String, Long> entry : acceptedNonces.entrySet()) {
            Long acceptedAt = entry.getValue();
            if (acceptedAt < oldestAcceptedAt) {
                acceptedNonces.remove(entry.getKey(), acceptedAt);
            }
        }
        if (acceptedNonces.putIfAbsent(nonce, now) != null) {
            throw unauthorized("ReachAI registry signature nonce was already used", null);
        }
    }

    private ReachAiInvocationUnauthorizedException unauthorized(String message, Throwable cause) {
        return new ReachAiInvocationUnauthorizedException(message, cause);
    }
}
