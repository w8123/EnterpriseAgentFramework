package com.enterprise.ai.control.platform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PlatformEmbedTokenService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final PlatformEmbedTokenProperties properties;
    private final Clock clock;

    @Autowired
    public PlatformEmbedTokenService(ObjectMapper objectMapper, PlatformEmbedTokenProperties properties) {
        this(objectMapper, properties, Clock.systemUTC());
    }

    PlatformEmbedTokenService(ObjectMapper objectMapper,
                              PlatformEmbedTokenProperties properties,
                              Clock clock) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    public PlatformEmbedTokenIssueResult issue(PlatformEmbedTokenIssueCommand command) {
        if (command == null) {
            throw new PlatformEmbedTokenException("embed token command is required");
        }
        requireText(command.getProjectCode(), "projectCode");
        requireText(command.getAgentId(), "agentId");
        requireText(command.getPageInstanceId(), "pageInstanceId");
        requireText(command.getOrigin(), "origin");
        PlatformEmbedTokenIssueCommand.BusinessPrincipal principal = command.getPrincipal();
        if (principal == null || !StringUtils.hasText(principal.externalUserId())) {
            throw new PlatformEmbedTokenException("principal.externalUserId is required");
        }

        long ttl = command.getTtlSeconds() == null || command.getTtlSeconds() <= 0
                ? properties.getDefaultTokenTtlSeconds()
                : command.getTtlSeconds();
        Instant now = clock.instant();
        Instant expiresAt = now.plusSeconds(ttl);
        String keyId = activeKeyId();
        Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT", "kid", keyId);
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", properties.getIssuer());
        claims.put("aud", properties.getAudience());
        claims.put("tenantId", firstNonBlank(command.getTenantId(), "default"));
        claims.put("appId", firstNonBlank(command.getAppId(), command.getProjectCode()));
        claims.put("projectCode", command.getProjectCode());
        claims.put("agentId", command.getAgentId());
        claims.put("externalUserId", principal.externalUserId());
        claims.put("globalUserId", firstNonBlank(principal.globalUserId(), principal.externalUserId()));
        claims.put("userName", principal.userName());
        claims.put("pageKey", command.getPageKey());
        claims.put("pageInstanceId", command.getPageInstanceId());
        claims.put("route", command.getRoute());
        claims.put("origin", command.getOrigin());
        claims.put("roles", principal.roles() == null ? List.of() : principal.roles());
        claims.put("attributes", principal.attributes() == null ? Map.of() : principal.attributes());
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        claims.put("jti", "embed-token-" + UUID.randomUUID());

        String signingInput = base64UrlJson(header) + "." + base64UrlJson(claims);
        String token = signingInput + "." + sign(signingInput, signingSecret(keyId));
        return new PlatformEmbedTokenIssueResult(token, ttl, expiresAt);
    }

    public PlatformEmbedTokenClaims verify(String token) {
        if (!StringUtils.hasText(token)) {
            throw new PlatformEmbedTokenException("embed token is required");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new PlatformEmbedTokenException("embed token format is invalid");
        }
        String signingInput = parts[0] + "." + parts[1];
        Map<String, Object> header = readPart(parts[0], "header");
        String expectedSignature = sign(signingInput, signingSecret(asString(header.get("kid"))));
        if (!MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8))) {
            throw new PlatformEmbedTokenException("embed token signature is invalid");
        }
        Map<String, Object> claims = readPart(parts[1], "payload");
        if (!properties.getIssuer().equals(asString(claims.get("iss")))) {
            throw new PlatformEmbedTokenException("embed token issuer is invalid");
        }
        if (!properties.getAudience().equals(asString(claims.get("aud")))) {
            throw new PlatformEmbedTokenException("embed token audience is invalid");
        }
        long exp = asLong(claims.get("exp"));
        if (exp <= 0 || !clock.instant().isBefore(Instant.ofEpochSecond(exp))) {
            throw new PlatformEmbedTokenException("embed token is expired");
        }
        PlatformEmbedTokenClaims result = new PlatformEmbedTokenClaims();
        result.setIssuer(asString(claims.get("iss")));
        result.setAudience(asString(claims.get("aud")));
        result.setTenantId(asString(claims.get("tenantId")));
        result.setAppId(asString(claims.get("appId")));
        result.setProjectCode(asString(claims.get("projectCode")));
        result.setAgentId(asString(claims.get("agentId")));
        result.setExternalUserId(asString(claims.get("externalUserId")));
        result.setGlobalUserId(asString(claims.get("globalUserId")));
        result.setUserName(asString(claims.get("userName")));
        result.setPageKey(asString(claims.get("pageKey")));
        result.setPageInstanceId(asString(claims.get("pageInstanceId")));
        result.setRoute(asString(claims.get("route")));
        result.setOrigin(asString(claims.get("origin")));
        result.setJti(asString(claims.get("jti")));
        result.setIssuedAt(asLong(claims.get("iat")));
        result.setExpiresAt(exp);
        result.setRoles(asStringList(claims.get("roles")));
        result.setAttributes(asMap(claims.get("attributes")));
        return result;
    }

    private Map<String, Object> readPart(String value, String label) {
        try {
            return objectMapper.readValue(Base64.getUrlDecoder().decode(value), MAP_TYPE);
        } catch (Exception ex) {
            throw new PlatformEmbedTokenException("embed token " + label + " is invalid", ex);
        }
    }

    private String base64UrlJson(Object value) {
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (Exception ex) {
            throw new PlatformEmbedTokenException("embed token json serialization failed", ex);
        }
    }

    private String activeKeyId() {
        return StringUtils.hasText(properties.getActiveKeyId()) ? properties.getActiveKeyId() : "default";
    }

    private String signingSecret(String keyId) {
        String normalizedKeyId = StringUtils.hasText(keyId) ? keyId : activeKeyId();
        String mapped = properties.getSecrets() == null ? null : properties.getSecrets().get(normalizedKeyId);
        if (StringUtils.hasText(mapped)) {
            return mapped;
        }
        if ("default".equals(normalizedKeyId) || normalizedKeyId.equals(activeKeyId())) {
            return properties.getSecret();
        }
        throw new PlatformEmbedTokenException("embed token signing key is not configured: " + normalizedKeyId);
    }

    private String sign(String signingInput, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new PlatformEmbedTokenException("embed token signature failed", ex);
        }
    }

    private void requireText(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new PlatformEmbedTokenException(name + " is required");
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(String::valueOf)
                .filter(StringUtils::hasText)
                .toList();
    }

    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, val) -> result.put(String.valueOf(key), val));
            return result;
        }
        return Map.of();
    }
}
