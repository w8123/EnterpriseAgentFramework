package com.enterprise.ai.common.internalauth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses the active internal-service HMAC secret plus a bounded overlap set used
 * only for verification during rolling secret rotation.
 */
public final class InternalServiceSecretRing {

    private static final int MAX_ACCEPTED_SECRETS = 4;

    private InternalServiceSecretRing() {
    }

    public static List<String> accepted(String activeSecret, String additionalSecretsCsv) {
        Set<String> values = new LinkedHashSet<>();
        add(values, activeSecret);
        if (additionalSecretsCsv != null && !additionalSecretsCsv.isBlank()) {
            for (String candidate : additionalSecretsCsv.split(",", -1)) {
                add(values, candidate);
            }
        }
        if (values.size() > MAX_ACCEPTED_SECRETS) {
            throw new IllegalStateException(
                    "at most " + MAX_ACCEPTED_SECRETS + " internal service verification secrets are allowed");
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    public static void requireStrong(String activeSecret, Iterable<String> acceptedSecrets) {
        if (activeSecret == null || activeSecret.trim().length() < 32) {
            throw new IllegalStateException(
                    "active internal service secret must contain at least 32 characters in production");
        }
        if (acceptedSecrets == null) {
            return;
        }
        for (String secret : acceptedSecrets) {
            if (secret == null || secret.trim().length() < 32) {
                throw new IllegalStateException(
                        "every accepted internal service secret must contain at least 32 characters in production");
            }
        }
    }

    private static void add(Set<String> values, String candidate) {
        if (candidate != null && !candidate.isBlank()) {
            values.add(candidate.trim());
        }
    }
}
