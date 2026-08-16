package com.enterprise.ai.common.internalauth;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;

/**
 * Production transport gate for trusted service-to-service calls.
 *
 * <p>HMAC authenticates a request but does not encrypt it. Production must use
 * direct HTTPS or explicitly attest that an mTLS service mesh protects the hop.</p>
 */
public final class InternalTransportSecurityPolicy {

    public static final String DEVELOPMENT_PLAINTEXT = "DEVELOPMENT_PLAINTEXT";
    public static final String DIRECT_TLS = "DIRECT_TLS";
    public static final String MTLS_MESH = "MTLS_MESH";

    private final boolean production;
    private final String mode;

    public InternalTransportSecurityPolicy(boolean production, String configuredMode) {
        this.production = production;
        this.mode = normalizeMode(configuredMode);
        if (production && DEVELOPMENT_PLAINTEXT.equals(mode)) {
            throw new IllegalStateException(
                    "reachai.internal.transport-mode must be DIRECT_TLS or MTLS_MESH in production");
        }
    }

    public void requireBaseUrl(String value, String label) {
        requireUrl(value, label, false);
    }

    public void requireRequestUrl(String value, String label) {
        requireUrl(value, label, true);
    }

    private void requireUrl(String value, String label, boolean allowQuery) {
        String url = value == null ? "" : value.trim();
        if (url.isEmpty()) {
            throw new IllegalStateException(label + " is required");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(label + " must be an absolute HTTP(S) URL", invalid);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null) {
            throw new IllegalStateException(label + " must be an absolute HTTP(S) URL");
        }
        if (uri.getUserInfo() != null || (!allowQuery && uri.getQuery() != null) || uri.getFragment() != null) {
            throw new IllegalStateException(label + " contains unsupported URL components");
        }
        if (production && DIRECT_TLS.equals(mode) && !"https".equals(scheme)) {
            throw new IllegalStateException(label + " must use https in DIRECT_TLS production mode");
        }
    }

    public String mode() {
        return mode;
    }

    public static boolean isProduction(String... profiles) {
        return profiles != null && Arrays.stream(profiles)
                .filter(profile -> profile != null)
                .map(profile -> profile.trim().toLowerCase(Locale.ROOT))
                .anyMatch(profile -> "prod".equals(profile) || "production".equals(profile));
    }

    private static String normalizeMode(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return DEVELOPMENT_PLAINTEXT;
        }
        if (!DEVELOPMENT_PLAINTEXT.equals(normalized)
                && !DIRECT_TLS.equals(normalized)
                && !MTLS_MESH.equals(normalized)) {
            throw new IllegalStateException("unsupported reachai.internal.transport-mode: " + normalized);
        }
        return normalized;
    }
}
