package com.enterprise.ai.control.aicoding.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

final class AiCodingRequestSupport {

    static final String JSON_UTF8_VALUE = "application/json;charset=UTF-8";

    private AiCodingRequestSupport() {
    }

    static String publicBaseUrl(HttpServletRequest request) {
        String scheme = firstHeader(request, "X-Forwarded-Proto");
        if (!StringUtils.hasText(scheme)) {
            scheme = request.getScheme();
        }
        scheme = scheme == null ? "" : scheme.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("public request scheme must be http or https");
        }

        String forwardedHost = firstHeader(request, "X-Forwarded-Host");
        boolean forwarded = StringUtils.hasText(forwardedHost);
        String host = forwarded ? forwardedHost.trim() : request.getServerName();
        validateAuthority(host);

        int port = forwarded
                ? forwardedPort(request, scheme, host)
                : request.getServerPort();
        String formattedHost = formatHost(host);
        String formattedPort = explicitPort(host) || defaultPort(scheme, port) || port <= 0
                ? ""
                : ":" + port;
        if (forwarded && !StringUtils.hasText(firstHeader(request, "X-Forwarded-Port"))) {
            // X-Forwarded-Host is the public authority. Never append Tomcat's
            // internal listener port when the proxy did not publish a port.
            formattedPort = "";
        }
        String contextPath = request.getContextPath() == null
                ? ""
                : request.getContextPath();
        return scheme + "://" + formattedHost + formattedPort + contextPath;
    }

    private static String firstHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return StringUtils.hasText(value)
                ? value.split(",")[0].trim()
                : null;
    }

    private static int forwardedPort(
            HttpServletRequest request,
            String scheme,
            String host) {
        if (explicitPort(host)) {
            return -1;
        }
        String value = firstHeader(request, "X-Forwarded-Port");
        if (!StringUtils.hasText(value)) {
            return -1;
        }
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException(
                        "X-Forwarded-Port must be between 1 and 65535");
            }
            return port;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    "X-Forwarded-Port must be numeric",
                    ex);
        }
    }

    private static void validateAuthority(String host) {
        if (!StringUtils.hasText(host)
                || host.length() > 512
                || host.chars().anyMatch(value ->
                        Character.isWhitespace(value)
                                || Character.isISOControl(value))
                || host.contains("/")
                || host.contains("\\")
                || host.contains("@")
                || host.contains("?")
                || host.contains("#")
                || host.contains("\"")
                || host.contains("'")
                || host.contains("<")
                || host.contains(">")) {
            throw new IllegalArgumentException("public request host is invalid");
        }

        if (host.startsWith("[")) {
            int closingBracket = host.indexOf(']');
            if (closingBracket <= 1
                    || host.indexOf('[', 1) >= 0
                    || host.indexOf(']', closingBracket + 1) >= 0) {
                throw new IllegalArgumentException("public request host is invalid");
            }
            validateIpv6Literal(host.substring(1, closingBracket));
            String suffix = host.substring(closingBracket + 1);
            if (suffix.isEmpty()) {
                return;
            }
            if (!suffix.startsWith(":") || suffix.length() == 1) {
                throw new IllegalArgumentException(
                        "public request host is invalid");
            }
            validatePort(suffix.substring(1));
            return;
        }

        if (host.contains("[") || host.contains("]")) {
            throw new IllegalArgumentException("public request host is invalid");
        }
        long colonCount = host.chars().filter(value -> value == ':').count();
        if (colonCount > 1) {
            validateIpv6Literal(host);
            return;
        }
        if (colonCount == 1) {
            int separator = host.lastIndexOf(':');
            if (separator == 0) {
                throw new IllegalArgumentException("public request host is invalid");
            }
            validatePort(host.substring(separator + 1));
        }
    }

    private static void validateIpv6Literal(String value) {
        if (!value.contains(":")) {
            throw new IllegalArgumentException("public request host is invalid");
        }
        try {
            InetAddress address = InetAddress.getByName(value);
            if (!(address instanceof Inet6Address)) {
                throw new IllegalArgumentException("public request host is invalid");
            }
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException(
                    "public request host is invalid",
                    ex);
        }
    }

    private static void validatePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException(
                        "public request host port is invalid");
            }
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    "public request host port is invalid",
                    ex);
        }
    }

    private static String formatHost(String host) {
        if (host.startsWith("[") || !host.contains(":")) {
            return host;
        }
        long colonCount = host.chars().filter(value -> value == ':').count();
        return colonCount > 1 ? "[" + host + "]" : host;
    }

    private static boolean explicitPort(String host) {
        if (host.startsWith("[")) {
            int closingBracket = host.indexOf(']');
            return closingBracket >= 0
                    && closingBracket + 1 < host.length()
                    && host.charAt(closingBracket + 1) == ':';
        }
        return host.chars().filter(value -> value == ':').count() == 1;
    }

    private static boolean defaultPort(String scheme, int port) {
        return ("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443);
    }
}
