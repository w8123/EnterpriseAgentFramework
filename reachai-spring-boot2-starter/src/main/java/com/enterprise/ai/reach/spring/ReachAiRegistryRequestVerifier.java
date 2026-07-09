package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.auth.ReachAiSigner;
import org.springframework.util.StringUtils;

public class ReachAiRegistryRequestVerifier {

    private static final long MAX_CLOCK_SKEW_MILLIS = 300000L;

    private final ReachAiRegistryProperties properties;

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
    }

    private void validateTimestamp(String timestamp) {
        try {
            long requestTime = Long.parseLong(timestamp.trim());
            long skew = Math.abs(System.currentTimeMillis() - requestTime);
            if (skew > MAX_CLOCK_SKEW_MILLIS) {
                throw unauthorized("ReachAI registry signature timestamp is expired", null);
            }
        } catch (NumberFormatException ex) {
            throw unauthorized("ReachAI registry signature timestamp is invalid", ex);
        }
    }

    private ReachAiInvocationUnauthorizedException unauthorized(String message, Throwable cause) {
        return new ReachAiInvocationUnauthorizedException(message, cause);
    }
}
