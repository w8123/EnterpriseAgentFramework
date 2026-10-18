package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.client.capability.CapabilityProxyClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read model of the business origins that may embed ReachAI, taken from the
 * active project embed policies Capability owns. The same patterns decide both
 * Embed Token exchange (in Capability) and browser CORS for /api/embed/**, so an
 * operator only maintains the origin list once, in the product.
 */
@Slf4j
@Component
public class PlatformEmbedOriginPolicy {

    static final Duration REFRESH_INTERVAL = Duration.ofSeconds(60);
    private static final TypeReference<List<Map<String, Object>>> POLICY_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final CapabilityProxyClient capabilityProxyClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private volatile Snapshot snapshot = new Snapshot(Set.of(), false, Instant.EPOCH);

    @Autowired
    public PlatformEmbedOriginPolicy(CapabilityProxyClient capabilityProxyClient, ObjectMapper objectMapper) {
        this(capabilityProxyClient, objectMapper, Clock.systemUTC());
    }

    PlatformEmbedOriginPolicy(CapabilityProxyClient capabilityProxyClient, ObjectMapper objectMapper, Clock clock) {
        this.capabilityProxyClient = capabilityProxyClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Whether a browser on {@code origin} may call the embed API. */
    public boolean allows(String origin) {
        if (!StringUtils.hasText(origin)) {
            return false;
        }
        Snapshot current = current();
        String normalized = origin.trim();
        if (current.localhostAllowed() && localDevelopmentOrigin(normalized)) {
            return true;
        }
        return current.patterns().stream().anyMatch(pattern -> originMatches(pattern, normalized));
    }

    /** Drop the cached snapshot after an operator changed a policy. */
    public void invalidate() {
        snapshot = new Snapshot(snapshot.patterns(), snapshot.localhostAllowed(), Instant.EPOCH);
    }

    private Snapshot current() {
        Snapshot current = snapshot;
        if (clock.instant().isBefore(current.loadedAt().plus(REFRESH_INTERVAL))) {
            return current;
        }
        synchronized (this) {
            current = snapshot;
            if (clock.instant().isBefore(current.loadedAt().plus(REFRESH_INTERVAL))) {
                return current;
            }
            try {
                snapshot = load();
            } catch (RuntimeException ex) {
                // Keep serving the last known policy; retry on the next interval.
                log.warn("embed origin policy refresh failed: {}", ex.getMessage());
                snapshot = new Snapshot(current.patterns(), current.localhostAllowed(), clock.instant());
            }
            return snapshot;
        }
    }

    private Snapshot load() {
        ResponseEntity<Object> response = capabilityProxyClient.listEmbedCredentialPolicies(null, "ACTIVE", 1000);
        if (response == null || !response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("embed policy list is unavailable");
        }
        List<Map<String, Object>> policies = objectMapper.convertValue(response.getBody(), POLICY_LIST);
        Set<String> patterns = new LinkedHashSet<>();
        boolean localhostAllowed = false;
        for (Map<String, Object> policy : policies) {
            List<String> origins = readOrigins(policy.get("allowedOriginsJson"));
            if (origins.isEmpty()) {
                // Capability lets a project without an origin list exchange tokens from localhost only.
                localhostAllowed = true;
            }
            origins.stream().filter(StringUtils::hasText).map(String::trim).forEach(patterns::add);
        }
        return new Snapshot(Set.copyOf(patterns), localhostAllowed, clock.instant());
    }

    private List<String> readOrigins(Object json) {
        if (!(json instanceof String text) || !StringUtils.hasText(text)) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(text, STRING_LIST);
            return values == null ? List.of() : values;
        } catch (Exception ex) {
            return new ArrayList<>();
        }
    }

    /** Mirrors Capability's token-exchange matcher: exact origin or {@code https://*.example.com}. */
    static boolean originMatches(String pattern, String origin) {
        if (!StringUtils.hasText(pattern) || !StringUtils.hasText(origin) || "*".equals(pattern.trim())) {
            return false;
        }
        String normalizedPattern = pattern.trim();
        if (!normalizedPattern.contains("*")) {
            return normalizedPattern.equals(origin);
        }
        if (!normalizedPattern.startsWith("https://*.") && !normalizedPattern.startsWith("http://*.")) {
            return false;
        }
        String suffix = normalizedPattern.substring(normalizedPattern.indexOf("*.") + 1);
        String scheme = normalizedPattern.substring(0, normalizedPattern.indexOf("://") + 3);
        if (!origin.startsWith(scheme) || !origin.endsWith(suffix)) {
            return false;
        }
        String subdomain = origin.substring(scheme.length(), origin.length() - suffix.length());
        return StringUtils.hasText(subdomain) && !subdomain.contains("/") && !subdomain.contains(":");
    }

    private static boolean localDevelopmentOrigin(String origin) {
        try {
            URI uri = URI.create(origin);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private record Snapshot(Set<String> patterns, boolean localhostAllowed, Instant loadedAt) {
    }
}
