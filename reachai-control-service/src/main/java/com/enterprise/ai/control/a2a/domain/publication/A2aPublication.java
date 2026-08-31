package com.enterprise.ai.control.a2a.domain.publication;

import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.A2aDomainText;
import com.enterprise.ai.control.a2a.domain.trust.A2aEnvironment;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

public record A2aPublication(
        Long id,
        String publicationKey,
        String agentId,
        Long projectId,
        String projectCode,
        A2aEnvironment environment,
        String tenantScope,
        String publicHost,
        long trustProfileId,
        Long currentRevisionId,
        A2aPublicationStatus status,
        int version,
        LocalDateTime publishedAt,
        LocalDateTime suspendedAt,
        String createdBy,
        String updatedBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z][a-z0-9-]{2,95}");

    public A2aPublication {
        publicationKey = A2aDomainText.requireText(publicationKey, "publicationKey");
        if (!KEY_PATTERN.matcher(publicationKey).matches()) {
            throw new A2aDomainException("A2A_PUBLICATION_KEY_INVALID",
                    "publicationKey must use 3-96 lowercase letters, digits, or hyphens");
        }
        agentId = A2aDomainText.requireText(agentId, "agentId");
        if (environment == null || status == null) {
            throw new A2aDomainException("A2A_REQUIRED_FIELD", "environment and status are required");
        }
        tenantScope = tenantScope == null ? "" : tenantScope.trim();
        publicHost = A2aPublicationAddress.normalizeHostHeader(publicHost);
        if (trustProfileId <= 0) {
            throw new A2aDomainException("A2A_TRUST_PROFILE_REQUIRED", "trustProfileId is required");
        }
    }

    public A2aPublication markReady(String actor) {
        if (status == A2aPublicationStatus.ARCHIVED) {
            throw archived();
        }
        if (status == A2aPublicationStatus.PUBLISHED || status == A2aPublicationStatus.SUSPENDED) {
            return this;
        }
        return copy(currentRevisionId, A2aPublicationStatus.READY, publishedAt, suspendedAt, actor);
    }

    public A2aPublication publish(long revisionId, LocalDateTime now, String actor) {
        if (status == A2aPublicationStatus.ARCHIVED) {
            throw archived();
        }
        return copy(revisionId, A2aPublicationStatus.PUBLISHED, now, null, actor);
    }

    public A2aPublication suspend(LocalDateTime now, String actor) {
        if (status != A2aPublicationStatus.PUBLISHED) {
            throw new A2aDomainException("A2A_PUBLICATION_NOT_PUBLISHED",
                    "only a published publication can be suspended");
        }
        return copy(currentRevisionId, A2aPublicationStatus.SUSPENDED, publishedAt, now, actor);
    }

    public A2aPublication resume(String actor) {
        if (status != A2aPublicationStatus.SUSPENDED || currentRevisionId == null) {
            throw new A2aDomainException("A2A_PUBLICATION_NOT_SUSPENDED",
                    "only a suspended publication with a current revision can be resumed");
        }
        return copy(currentRevisionId, A2aPublicationStatus.PUBLISHED, publishedAt, null, actor);
    }

    private A2aPublication copy(Long revisionId, A2aPublicationStatus next, LocalDateTime published,
                                LocalDateTime suspended, String actor) {
        return new A2aPublication(id, publicationKey, agentId, projectId, projectCode, environment,
                tenantScope, publicHost, trustProfileId, revisionId, next, version,
                published, suspended, createdBy, actor, createdAt, updatedAt);
    }

    private A2aDomainException archived() {
        return new A2aDomainException("A2A_PUBLICATION_ARCHIVED",
                "an archived publication cannot transition");
    }
}
