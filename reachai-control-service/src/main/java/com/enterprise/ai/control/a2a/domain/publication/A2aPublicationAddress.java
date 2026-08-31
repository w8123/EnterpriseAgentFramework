package com.enterprise.ai.control.a2a.domain.publication;

import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.trust.A2aEnvironment;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

public record A2aPublicationAddress(String publicOrigin, String publicHost) {

    public static A2aPublicationAddress of(
            String requestedOrigin,
            String requestedHost,
            A2aEnvironment environment) {
        if (requestedOrigin == null || requestedOrigin.isBlank()) {
            throw new A2aDomainException("A2A_PUBLIC_ORIGIN_REQUIRED", "publicOrigin is required");
        }
        try {
            URI uri = new URI(requestedOrigin.trim());
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
                throw invalidOrigin();
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"https".equals(scheme) && !"http".equals(scheme)) {
                throw invalidOrigin();
            }
            if (environment.isProduction() && !"https".equals(scheme)) {
                throw new A2aDomainException("A2A_PRODUCTION_HTTPS_REQUIRED",
                        "production A2A publications require HTTPS");
            }
            String authority = normalizedAuthority(uri.getHost(), uri.getPort());
            String host = normalizeHostHeader(requestedHost);
            if (!authority.equals(host)) {
                throw new A2aDomainException("A2A_PUBLIC_HOST_MISMATCH",
                        "publicHost must match the publicOrigin authority");
            }
            return new A2aPublicationAddress(scheme + "://" + authority, authority);
        } catch (URISyntaxException exception) {
            throw invalidOrigin();
        }
    }

    public static String normalizeHostHeader(String value) {
        if (value == null || value.isBlank()) {
            throw new A2aDomainException("A2A_PUBLIC_HOST_REQUIRED", "publicHost is required");
        }
        String host = value.trim().toLowerCase(Locale.ROOT);
        if (host.contains("/") || host.contains("@") || host.contains(" ")
                || host.contains("\r") || host.contains("\n") || host.length() > 255) {
            throw new A2aDomainException("A2A_PUBLIC_HOST_INVALID", "publicHost is invalid");
        }
        return host;
    }

    private static String normalizedAuthority(String host, int port) {
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (normalizedHost.contains(":")) {
            normalizedHost = "[" + normalizedHost + "]";
        }
        return port < 0 ? normalizedHost : normalizedHost + ":" + port;
    }

    private static A2aDomainException invalidOrigin() {
        return new A2aDomainException("A2A_PUBLIC_ORIGIN_INVALID",
                "publicOrigin must be an absolute HTTP(S) origin without path, credentials, query, or fragment");
    }
}
