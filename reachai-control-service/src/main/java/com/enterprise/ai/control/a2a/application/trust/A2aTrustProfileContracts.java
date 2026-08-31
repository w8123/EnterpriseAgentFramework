package com.enterprise.ai.control.a2a.application.trust;

import com.enterprise.ai.control.a2a.domain.trust.A2aTrustProfile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

public final class A2aTrustProfileContracts {

    private A2aTrustProfileContracts() {
    }

    public record UpsertRequest(
            String profileKey,
            String name,
            String description,
            String direction,
            String environment,
            String trustLevel,
            List<String> authenticationMethods,
            List<String> allowedScopes,
            AuthorizationPolicyRequest authorizationPolicy,
            DataPolicyRequest dataPolicy,
            String delegatedIdentityPolicy,
            String personalMemoryPolicy,
            Integer rateLimitPerMinute,
            Integer maxConcurrentTasks,
            Long maxRequestBytes,
            Long maxArtifactBytes,
            Long taskTimeoutMs,
            Boolean allowAnonymous) {
    }

    public record AuthorizationPolicyRequest(
            List<String> publicationKeys,
            List<String> remoteAgentKeys,
            List<String> protocolSkillIds,
            List<String> operations,
            List<String> tenantScopes) {
    }

    public record DataPolicyRequest(
            List<String> allowedDataClassifications,
            Boolean allowTextParts,
            Boolean allowFileParts,
            Boolean allowUrlParts,
            Integer payloadRetentionDays) {
    }

    public record StatusRequest(String status) {
    }

    public record View(
            String schema,
            Long id,
            String profileKey,
            String name,
            String description,
            String direction,
            String environment,
            String trustLevel,
            Set<String> authenticationMethods,
            Set<String> allowedScopes,
            AuthorizationPolicyView authorizationPolicy,
            DataPolicyView dataPolicy,
            String delegatedIdentityPolicy,
            String personalMemoryPolicy,
            int rateLimitPerMinute,
            int maxConcurrentTasks,
            long maxRequestBytes,
            long maxArtifactBytes,
            long taskTimeoutMs,
            boolean allowAnonymous,
            String status,
            int version,
            String createdBy,
            String updatedBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {

        public static View from(A2aTrustProfile profile) {
            return new View(
                    "reachai.a2a-hub.trust-profile.v1",
                    profile.id(),
                    profile.profileKey(),
                    profile.name(),
                    profile.description(),
                    profile.direction().name(),
                    profile.environment().name(),
                    profile.trustLevel().name(),
                    profile.authenticationMethods().stream()
                            .map(Enum::name)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                    profile.allowedScopes(),
                    new AuthorizationPolicyView(
                            profile.authorizationPolicy().publicationKeys(),
                            profile.authorizationPolicy().remoteAgentKeys(),
                            profile.authorizationPolicy().protocolSkillIds(),
                            profile.authorizationPolicy().operations(),
                            profile.authorizationPolicy().tenantScopes()),
                    new DataPolicyView(
                            profile.dataPolicy().allowedDataClassifications(),
                            profile.dataPolicy().allowTextParts(),
                            profile.dataPolicy().allowFileParts(),
                            profile.dataPolicy().allowUrlParts(),
                            profile.dataPolicy().payloadRetentionDays()),
                    profile.delegatedIdentityPolicy().name(),
                    profile.personalMemoryPolicy().name(),
                    profile.rateLimitPerMinute(),
                    profile.maxConcurrentTasks(),
                    profile.maxRequestBytes(),
                    profile.maxArtifactBytes(),
                    profile.taskTimeoutMs(),
                    profile.allowAnonymous(),
                    profile.status().name(),
                    profile.version(),
                    profile.createdBy(),
                    profile.updatedBy(),
                    profile.createdAt(),
                    profile.updatedAt());
        }
    }

    public record AuthorizationPolicyView(
            Set<String> publicationKeys,
            Set<String> remoteAgentKeys,
            Set<String> protocolSkillIds,
            Set<String> operations,
            Set<String> tenantScopes) {
    }

    public record DataPolicyView(
            Set<String> allowedDataClassifications,
            boolean allowTextParts,
            boolean allowFileParts,
            boolean allowUrlParts,
            int payloadRetentionDays) {
    }
}
