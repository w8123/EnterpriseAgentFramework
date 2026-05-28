package com.enterprise.ai.agent.platform.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
public class OidcPlatformAuthProvider implements PlatformAuthProvider {

    private final PlatformAuthProperties properties;
    private final OidcJwtDecoderFactory decoderFactory;

    @Override
    public String providerType() {
        return "OIDC";
    }

    @Override
    public PlatformUserProfile authenticate(PlatformLoginRequest request) {
        PlatformAuthProperties.Oidc oidc = resolveConfig(request);
        if (!oidc.isEnabled()) {
            throw new IllegalArgumentException("OIDC provider is disabled");
        }
        if (request == null || !StringUtils.hasText(request.getIdToken())) {
            throw new IllegalArgumentException("OIDC idToken is required");
        }
        Jwt jwt;
        try {
            jwt = decoderFactory.create(oidc).decode(request.getIdToken());
        } catch (JwtException ex) {
            throw new IllegalArgumentException("OIDC idToken validation failed: " + ex.getMessage(), ex);
        }
        validateIssuer(jwt, oidc);
        validateAudience(jwt, oidc);
        validateTime(jwt);
        String subject = jwt.getSubject();
        if (!StringUtils.hasText(subject)) {
            throw new IllegalArgumentException("OIDC idToken subject is required");
        }
        String username = firstNonBlank(
                claimAsString(jwt, oidc.getUsernameClaim()),
                claimAsString(jwt, "preferred_username"),
                claimAsString(jwt, "username"),
                claimAsString(jwt, "email"),
                subject);
        return new PlatformUserProfile(
                "OIDC",
                subject,
                username,
                firstNonBlank(claimAsString(jwt, "name"), username),
                claimAsString(jwt, "email"),
                firstNonBlank(claimAsString(jwt, "phone_number"), claimAsString(jwt, "mobile")),
                roleCodes(jwt, oidc));
    }

    private PlatformAuthProperties.Oidc resolveConfig(PlatformLoginRequest request) {
        MapReader config = new MapReader(request == null ? null : request.getProviderConfig());
        PlatformAuthProperties.Oidc base = properties.getOidc();
        PlatformAuthProperties.Oidc oidc = new PlatformAuthProperties.Oidc();
        oidc.setEnabled(config.bool("enabled", base.isEnabled()));
        oidc.setIssuerUri(config.text("issuerUri", base.getIssuerUri()));
        oidc.setJwkSetUri(config.text("jwkSetUri", base.getJwkSetUri()));
        oidc.setClientId(config.text("clientId", base.getClientId()));
        oidc.setUsernameClaim(config.text("usernameClaim", base.getUsernameClaim()));
        oidc.setRolesClaim(config.text("rolesClaim", base.getRolesClaim()));
        return oidc;
    }

    private void validateIssuer(Jwt jwt, PlatformAuthProperties.Oidc oidc) {
        String expected = oidc.getIssuerUri();
        if (!StringUtils.hasText(expected)) {
            return;
        }
        String actual = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("OIDC issuer mismatch");
        }
    }

    private void validateAudience(Jwt jwt, PlatformAuthProperties.Oidc oidc) {
        String clientId = oidc.getClientId();
        if (StringUtils.hasText(clientId) && !jwt.getAudience().contains(clientId)) {
            throw new IllegalArgumentException("OIDC audience mismatch");
        }
    }

    private void validateTime(Jwt jwt) {
        Instant now = Instant.now();
        if (jwt.getExpiresAt() != null && jwt.getExpiresAt().isBefore(now)) {
            throw new IllegalArgumentException("OIDC idToken is expired");
        }
        if (jwt.getNotBefore() != null && jwt.getNotBefore().isAfter(now)) {
            throw new IllegalArgumentException("OIDC idToken is not active yet");
        }
    }

    private Set<String> roleCodes(Jwt jwt, PlatformAuthProperties.Oidc oidc) {
        Set<String> roles = new LinkedHashSet<>();
        addClaimValues(roles, jwt.getClaim(oidc.getRolesClaim()));
        addClaimValues(roles, jwt.getClaim("groups"));
        addClaimValues(roles, jwt.getClaim("scp"));
        addClaimValues(roles, jwt.getClaim("scope"));
        return roles.stream()
                .filter(StringUtils::hasText)
                .map(role -> role.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void addClaimValues(Set<String> target, Object value) {
        if (value instanceof Collection<?> collection) {
            collection.stream().map(String::valueOf).forEach(target::add);
            return;
        }
        if (value instanceof String text) {
            Stream.of(text.split("[,\\s]+"))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .forEach(target::add);
        }
    }

    private String claimAsString(Jwt jwt, String claim) {
        if (!StringUtils.hasText(claim)) {
            return null;
        }
        Object value = jwt.getClaim(claim);
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private record MapReader(java.util.Map<String, Object> values) {
        private String text(String key, String fallback) {
            Object value = values == null ? null : values.get(key);
            return value == null || !StringUtils.hasText(String.valueOf(value)) ? fallback : String.valueOf(value);
        }

        private boolean bool(String key, boolean fallback) {
            Object value = values == null ? null : values.get(key);
            return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
        }
    }
}
