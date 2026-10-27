package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.mcp.infrastructure.McpHubProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;

import java.util.Locale;

/** The ReachAI origin employees' browsers and AI tools reach: the configured one, else the request's. */
final class PlatformPublicOrigin {

    private PlatformPublicOrigin() {
    }

    static String resolve(McpHubProperties properties, HttpServletRequest request) {
        if (StringUtils.hasText(properties.getPublicBaseUrl())) {
            String configured = properties.getPublicBaseUrl().trim();
            return configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;
        }
        String scheme = firstHeader(request, "X-Forwarded-Proto");
        if (!StringUtils.hasText(scheme)) {
            scheme = request.getScheme();
        }
        String host = firstHeader(request, "X-Forwarded-Host");
        if (!StringUtils.hasText(host)) {
            host = firstHeader(request, HttpHeaders.HOST);
        }
        if (!StringUtils.hasText(host)) {
            host = request.getServerName() + ":" + request.getServerPort();
        }
        return scheme.toLowerCase(Locale.ROOT) + "://" + host;
    }

    private static String firstHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return StringUtils.hasText(value) ? value.split(",")[0].trim() : null;
    }
}
