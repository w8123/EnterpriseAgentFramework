package com.enterprise.ai.control.a2a.domain.identity;

import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.A2aDomainText;
import com.enterprise.ai.control.a2a.domain.A2aPrincipalType;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public record A2aPrincipal(
        Long id,
        String principalKey,
        A2aPrincipalType principalType,
        String displayName,
        String tenantScope,
        String authenticatedSubject,
        long trustProfileId,
        Long credentialId,
        Set<String> scopes,
        Map<String, String> attributes,
        A2aPrincipalStatus status,
        LocalDateTime lastAuthenticatedAt,
        int version,
        String createdBy,
        String updatedBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z][a-z0-9._-]{2,127}");
    private static final Set<String> FORBIDDEN_ATTRIBUTE_FRAGMENTS = Set.of(
            "secret", "password", "token", "credential", "api_key", "apikey", "private_key");

    public A2aPrincipal {
        principalKey = A2aDomainText.requireText(principalKey, "principalKey");
        if (!KEY_PATTERN.matcher(principalKey).matches()) {
            throw new A2aDomainException("A2A_PRINCIPAL_KEY_INVALID", "principalKey is invalid");
        }
        if (principalType == null || status == null || trustProfileId <= 0) {
            throw new A2aDomainException("A2A_PRINCIPAL_INVALID", "principal metadata is invalid");
        }
        displayName = A2aDomainText.requireText(displayName, "displayName");
        tenantScope = tenantScope == null ? "" : tenantScope.trim();
        authenticatedSubject = A2aDomainText.requireText(authenticatedSubject,
                "authenticatedSubject");
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        attributes.keySet().forEach(A2aPrincipal::requireSafeAttributeKey);
    }

    public A2aPrincipal withStatus(A2aPrincipalStatus next, String actor) {
        if (status == A2aPrincipalStatus.ARCHIVED) {
            throw new A2aDomainException("A2A_PRINCIPAL_ARCHIVED",
                    "an archived principal cannot transition");
        }
        return new A2aPrincipal(id, principalKey, principalType, displayName, tenantScope,
                authenticatedSubject, trustProfileId, credentialId, scopes, attributes, next,
                lastAuthenticatedAt, version, createdBy, actor, createdAt, updatedAt);
    }

    private static void requireSafeAttributeKey(String value) {
        String normalized = value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
        if (value == null || value.isBlank()
                || FORBIDDEN_ATTRIBUTE_FRAGMENTS.stream().anyMatch(normalized::contains)) {
            throw new A2aDomainException("A2A_PRINCIPAL_ATTRIBUTE_FORBIDDEN",
                    "principal attributes cannot contain credential material");
        }
    }
}
