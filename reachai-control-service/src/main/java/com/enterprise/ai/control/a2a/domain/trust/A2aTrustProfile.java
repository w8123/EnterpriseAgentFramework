package com.enterprise.ai.control.a2a.domain.trust;

import com.enterprise.ai.control.a2a.domain.A2aAuthenticationMethod;
import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.A2aDomainText;
import com.enterprise.ai.control.a2a.domain.A2aTrustLevel;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.regex.Pattern;

public record A2aTrustProfile(
        Long id,
        String profileKey,
        String name,
        String description,
        A2aDirection direction,
        A2aEnvironment environment,
        A2aTrustLevel trustLevel,
        Set<A2aAuthenticationMethod> authenticationMethods,
        Set<String> allowedScopes,
        A2aAuthorizationPolicy authorizationPolicy,
        A2aDataPolicy dataPolicy,
        A2aDelegatedIdentityPolicy delegatedIdentityPolicy,
        A2aPersonalMemoryPolicy personalMemoryPolicy,
        int rateLimitPerMinute,
        int maxConcurrentTasks,
        long maxRequestBytes,
        long maxArtifactBytes,
        long taskTimeoutMs,
        boolean allowAnonymous,
        A2aTrustProfileStatus status,
        int version,
        String createdBy,
        String updatedBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z][a-z0-9-]{2,95}");

    public A2aTrustProfile {
        profileKey = A2aDomainText.requireText(profileKey, "profileKey");
        if (!KEY_PATTERN.matcher(profileKey).matches()) {
            throw new A2aDomainException("A2A_PROFILE_KEY_INVALID",
                    "profileKey must use 3-96 lowercase letters, digits, or hyphens");
        }
        name = A2aDomainText.requireText(name, "name");
        if (name.length() > 128) {
            throw new A2aDomainException("A2A_NAME_TOO_LONG", "name exceeds 128 characters");
        }
        direction = require(direction, "direction");
        environment = require(environment, "environment");
        trustLevel = require(trustLevel, "trustLevel");
        authenticationMethods = authenticationMethods == null
                ? Set.of()
                : Set.copyOf(authenticationMethods);
        if (authenticationMethods.isEmpty()) {
            throw new A2aDomainException("A2A_AUTH_METHOD_REQUIRED",
                    "at least one authentication method is required");
        }
        allowedScopes = normalize(allowedScopes);
        authorizationPolicy = require(authorizationPolicy, "authorizationPolicy");
        dataPolicy = require(dataPolicy, "dataPolicy");
        delegatedIdentityPolicy = require(delegatedIdentityPolicy, "delegatedIdentityPolicy");
        personalMemoryPolicy = require(personalMemoryPolicy, "personalMemoryPolicy");
        status = require(status, "status");
        if (rateLimitPerMinute <= 0 || maxConcurrentTasks <= 0) {
            throw new A2aDomainException("A2A_CAPACITY_INVALID",
                    "rateLimitPerMinute and maxConcurrentTasks must be positive");
        }
        if (maxRequestBytes <= 0 || maxArtifactBytes <= 0 || taskTimeoutMs <= 0) {
            throw new A2aDomainException("A2A_LIMIT_INVALID",
                    "request, artifact, and timeout limits must be positive");
        }
        if (maxArtifactBytes < maxRequestBytes) {
            throw new A2aDomainException("A2A_ARTIFACT_LIMIT_INVALID",
                    "maxArtifactBytes cannot be smaller than maxRequestBytes");
        }
        boolean declaresAnonymous = authenticationMethods.contains(A2aAuthenticationMethod.ANONYMOUS);
        if (declaresAnonymous && authenticationMethods.size() != 1) {
            throw new A2aDomainException("A2A_ANONYMOUS_MIXED_AUTH_FORBIDDEN",
                    "ANONYMOUS cannot be combined with authenticated methods");
        }
        if (allowAnonymous != declaresAnonymous) {
            throw new A2aDomainException("A2A_ANONYMOUS_CONTRACT_MISMATCH",
                    "allowAnonymous and the ANONYMOUS authentication method must match");
        }
        if (allowAnonymous && environment != A2aEnvironment.DEVELOPMENT) {
            throw new A2aDomainException("A2A_ANONYMOUS_ENVIRONMENT_FORBIDDEN",
                    "anonymous A2A access is allowed only in DEVELOPMENT");
        }
        if (allowAnonymous && trustLevel.ordinal() > A2aTrustLevel.KNOWN.ordinal()) {
            throw new A2aDomainException("A2A_ANONYMOUS_TRUST_INVALID",
                    "anonymous access cannot be marked TRUSTED or PRIVILEGED");
        }
        if (personalMemoryPolicy != A2aPersonalMemoryPolicy.DISABLED
                && delegatedIdentityPolicy != A2aDelegatedIdentityPolicy.ATTESTED_ONLY) {
            throw new A2aDomainException("A2A_MEMORY_IDENTITY_REQUIRED",
                    "personal memory requires attested delegated identity");
        }
    }

    public A2aTrustProfile withStatus(A2aTrustProfileStatus next, String actor) {
        if (status == A2aTrustProfileStatus.ARCHIVED) {
            throw new A2aDomainException("A2A_PROFILE_ARCHIVED",
                    "an archived trust profile cannot transition");
        }
        if (next == null) {
            throw new A2aDomainException("A2A_PROFILE_STATUS_REQUIRED", "status is required");
        }
        return new A2aTrustProfile(id, profileKey, name, description, direction, environment,
                trustLevel, authenticationMethods, allowedScopes, authorizationPolicy, dataPolicy,
                delegatedIdentityPolicy, personalMemoryPolicy, rateLimitPerMinute,
                maxConcurrentTasks, maxRequestBytes, maxArtifactBytes, taskTimeoutMs,
                allowAnonymous, next, version, createdBy, actor, createdAt, updatedAt);
    }

    private static Set<String> normalize(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static <T> T require(T value, String field) {
        if (value == null) {
            throw new A2aDomainException("A2A_REQUIRED_FIELD", field + " is required");
        }
        return value;
    }
}
