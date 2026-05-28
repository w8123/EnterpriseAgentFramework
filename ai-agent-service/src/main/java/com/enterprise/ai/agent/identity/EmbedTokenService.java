package com.enterprise.ai.agent.identity;

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
public class EmbedTokenService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final EmbedTokenProperties properties;
    private final Clock clock;
    private final EmbedTokenRevocationService revocationService;

    @Autowired
    public EmbedTokenService(ObjectMapper objectMapper,
                             EmbedTokenProperties properties,
                             EmbedTokenRevocationService revocationService) {
        this(objectMapper, properties, Clock.systemUTC(), revocationService);
    }

    EmbedTokenService(ObjectMapper objectMapper, EmbedTokenProperties properties, Clock clock) {
        this(objectMapper, properties, clock, null);
    }

    EmbedTokenService(ObjectMapper objectMapper,
                      EmbedTokenProperties properties,
                      Clock clock,
                      EmbedTokenRevocationService revocationService) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
        this.revocationService = revocationService;
    }

    public EmbedTokenIssueResult issue(EmbedTokenIssueCommand command) {
        if (command == null) {
            throw new EmbedTokenException("embed token command is required");
        }
        requireText(command.getProjectCode(), "projectCode");
        requireText(command.getAgentId(), "agentId");
        requireText(command.getPageInstanceId(), "pageInstanceId");
        requireText(command.getOrigin(), "origin");
        BusinessPrincipal principal = command.getPrincipal();
        if (principal == null || !StringUtils.hasText(principal.getExternalUserId())) {
            throw new EmbedTokenException("principal.externalUserId is required");
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
        claims.put("tenantId", firstNonBlank(command.getTenantId(), principal.getTenantId(), "default"));
        claims.put("appId", firstNonBlank(command.getAppId(), principal.getAppId(), command.getProjectCode()));
        claims.put("projectCode", command.getProjectCode());
        claims.put("agentId", command.getAgentId());
        claims.put("externalUserId", principal.getExternalUserId());
        claims.put("globalUserId", firstNonBlank(principal.getGlobalUserId(), principal.getExternalUserId()));
        claims.put("userName", principal.getUserName());
        claims.put("deptId", principal.getDeptId());
        claims.put("deptName", principal.getDeptName());
        claims.put("pageInstanceId", command.getPageInstanceId());
        claims.put("route", command.getRoute());
        claims.put("origin", command.getOrigin());
        claims.put("roles", principal.getRoles() == null ? List.of() : principal.getRoles());
        claims.put("attributes", principal.getAttributes() == null ? Map.of() : principal.getAttributes());
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        claims.put("jti", "embed-token-" + UUID.randomUUID());

        String signingInput = base64UrlJson(header) + "." + base64UrlJson(claims);
        String token = signingInput + "." + sign(signingInput, signingSecret(keyId));
        return new EmbedTokenIssueResult(token, ttl, expiresAt);
    }

    public EmbedTokenClaims verify(String token) {
        if (!StringUtils.hasText(token)) {
            throw new EmbedTokenException("embed token is required");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new EmbedTokenException("embed token format is invalid");
        }
        String signingInput = parts[0] + "." + parts[1];
        Map<String, Object> header;
        try {
            header = objectMapper.readValue(Base64.getUrlDecoder().decode(parts[0]), MAP_TYPE);
        } catch (Exception ex) {
            throw new EmbedTokenException("embed token header is invalid", ex);
        }
        String secret = signingSecret(asString(header.get("kid")));
        if (!MessageDigest.isEqual(sign(signingInput, secret).getBytes(StandardCharsets.UTF_8), parts[2].getBytes(StandardCharsets.UTF_8))) {
            throw new EmbedTokenException("embed token signature is invalid");
        }
        Map<String, Object> claims;
        try {
            claims = objectMapper.readValue(Base64.getUrlDecoder().decode(parts[1]), MAP_TYPE);
        } catch (Exception ex) {
            throw new EmbedTokenException("embed token payload is invalid", ex);
        }
        if (!properties.getIssuer().equals(asString(claims.get("iss")))) {
            throw new EmbedTokenException("embed token issuer is invalid");
        }
        if (!properties.getAudience().equals(asString(claims.get("aud")))) {
            throw new EmbedTokenException("embed token audience is invalid");
        }
        long exp = asLong(claims.get("exp"));
        if (exp <= 0 || !clock.instant().isBefore(Instant.ofEpochSecond(exp))) {
            throw new EmbedTokenException("embed token is expired");
        }
        String jti = asString(claims.get("jti"));
        if (revocationService != null && revocationService.isRevoked(jti)) {
            throw new EmbedTokenException("embed token is revoked");
        }
        EmbedTokenClaims result = new EmbedTokenClaims();
        result.setIssuer(asString(claims.get("iss")));
        result.setAudience(asString(claims.get("aud")));
        result.setTenantId(asString(claims.get("tenantId")));
        result.setAppId(asString(claims.get("appId")));
        result.setProjectCode(asString(claims.get("projectCode")));
        result.setAgentId(asString(claims.get("agentId")));
        result.setExternalUserId(asString(claims.get("externalUserId")));
        result.setGlobalUserId(asString(claims.get("globalUserId")));
        result.setUserName(asString(claims.get("userName")));
        result.setDeptId(asString(claims.get("deptId")));
        result.setDeptName(asString(claims.get("deptName")));
        result.setPageInstanceId(asString(claims.get("pageInstanceId")));
        result.setRoute(asString(claims.get("route")));
        result.setOrigin(asString(claims.get("origin")));
        result.setJti(jti);
        result.setIssuedAt(asLong(claims.get("iat")));
        result.setExpiresAt(exp);
        result.setRoles(asStringList(claims.get("roles")));
        result.setAttributes(asMap(claims.get("attributes")));
        return result;
    }

    private String base64UrlJson(Object value) {
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (Exception ex) {
            throw new EmbedTokenException("embed token json serialization failed", ex);
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
        throw new EmbedTokenException("embed token signing key is not configured: " + normalizedKeyId);
    }

    private String sign(String signingInput, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new EmbedTokenException("embed token signature failed", ex);
        }
    }

    private void requireText(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new EmbedTokenException(name + " is required");
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, val) -> result.put(String.valueOf(key), val));
            return result;
        }
        return Map.of();
    }
}
