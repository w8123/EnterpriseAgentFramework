package com.enterprise.ai.runtime.internalauth;

import com.enterprise.ai.common.internalauth.InternalServiceSecretRing;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Internal HMAC auth settings. Unsafe TTL/skew combinations fail at startup.
 * Nonce retention must cover the full signature validity window including future skew.
 */
@Component
public class InternalServiceAuthProperties {

    private final String serviceSecret;
    private final List<String> verificationSecrets;
    private final long skewSeconds;
    private final long nonceTtlSeconds;
    private final int nonceMaxEntries;
    private final int maxBodyBytes;
    private final boolean production;

    @Autowired
    public InternalServiceAuthProperties(
            @Value("${reachai.internal.service-secret:${REACHAI_INTERNAL_SERVICE_SECRET:}}") String serviceSecret,
            @Value("${reachai.internal.accepted-service-secrets:${REACHAI_INTERNAL_SERVICE_ACCEPTED_SECRETS:}}")
            String acceptedServiceSecrets,
            @Value("${reachai.internal.auth.skew-seconds:300}") long skewSeconds,
            @Value("${reachai.internal.auth.nonce-ttl-seconds:600}") long nonceTtlSeconds,
            @Value("${reachai.internal.auth.nonce-max-entries:100000}") int nonceMaxEntries,
            @Value("${reachai.internal.auth.max-body-bytes:1048576}") int maxBodyBytes,
            Environment environment) {
        this.serviceSecret = serviceSecret == null ? "" : serviceSecret.trim();
        this.verificationSecrets = InternalServiceSecretRing.accepted(
                this.serviceSecret, acceptedServiceSecrets);
        this.skewSeconds = skewSeconds;
        this.nonceTtlSeconds = nonceTtlSeconds;
        this.nonceMaxEntries = nonceMaxEntries;
        this.maxBodyBytes = maxBodyBytes;
        this.production = isProduction(environment);
    }

    public InternalServiceAuthProperties(String serviceSecret,
                                         String acceptedServiceSecrets,
                                         long skewSeconds,
                                         long nonceTtlSeconds,
                                         int nonceMaxEntries,
                                         int maxBodyBytes) {
        this(serviceSecret, acceptedServiceSecrets, skewSeconds, nonceTtlSeconds,
                nonceMaxEntries, maxBodyBytes, null);
    }

    public InternalServiceAuthProperties(String serviceSecret,
                                         long skewSeconds,
                                         long nonceTtlSeconds,
                                         int nonceMaxEntries,
                                         int maxBodyBytes) {
        this(serviceSecret, "", skewSeconds, nonceTtlSeconds, nonceMaxEntries, maxBodyBytes, null);
    }

    @PostConstruct
    void validate() {
        validateConfig(skewSeconds, nonceTtlSeconds, nonceMaxEntries, maxBodyBytes);
        if (production) {
            InternalServiceSecretRing.requireStrong(serviceSecret, verificationSecrets);
        }
    }

    static void validateConfig(long skewSeconds, long nonceTtlSeconds, int nonceMaxEntries, int maxBodyBytes) {
        if (skewSeconds < 1) {
            throw new IllegalStateException("reachai.internal.auth.skew-seconds must be >= 1");
        }
        if (nonceMaxEntries < 1) {
            throw new IllegalStateException("reachai.internal.auth.nonce-max-entries must be >= 1");
        }
        if (maxBodyBytes < 1) {
            throw new IllegalStateException("reachai.internal.auth.max-body-bytes must be >= 1");
        }
        // Client timestamp may be up to skewSeconds in the future; retention must cover [now-skew, now+skew].
        long minTtl = 2L * skewSeconds;
        if (nonceTtlSeconds < minTtl) {
            throw new IllegalStateException(
                    "reachai.internal.auth.nonce-ttl-seconds must be >= 2 * skew-seconds (required "
                            + minTtl + ", got " + nonceTtlSeconds + ")");
        }
    }

    public String serviceSecret() {
        return serviceSecret;
    }

    public boolean secretConfigured() {
        return !verificationSecrets.isEmpty();
    }

    public List<String> verificationSecrets() {
        return verificationSecrets;
    }

    public long skewSeconds() {
        return skewSeconds;
    }

    public long nonceTtlSeconds() {
        return nonceTtlSeconds;
    }

    public int nonceMaxEntries() {
        return nonceMaxEntries;
    }

    public int maxBodyBytes() {
        return maxBodyBytes;
    }

    private static boolean isProduction(Environment environment) {
        return environment != null && Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.trim().toLowerCase(Locale.ROOT))
                .anyMatch(profile -> "prod".equals(profile) || "production".equals(profile));
    }
}
