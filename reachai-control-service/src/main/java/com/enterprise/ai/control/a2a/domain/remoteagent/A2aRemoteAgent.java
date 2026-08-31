package com.enterprise.ai.control.a2a.domain.remoteagent;

import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.A2aDomainText;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

public record A2aRemoteAgent(
        Long id,
        String remoteAgentKey,
        String displayName,
        String tenantScope,
        String cardUrl,
        String cardUrlSha256,
        Long trustProfileId,
        Long credentialId,
        Long currentRevisionId,
        String preferredInterfaceKey,
        String preferredSecuritySchemeKey,
        A2aRemoteAgentStatus status,
        A2aRemoteAgentHealth healthStatus,
        int consecutiveHealthFailures,
        LocalDateTime lastDiscoveredAt,
        LocalDateTime lastHealthCheckedAt,
        String lastHealthSummary,
        int version,
        String createdBy,
        String updatedBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9._-]{2,127}");

    public A2aRemoteAgent {
        remoteAgentKey = A2aDomainText.requireText(remoteAgentKey, "remoteAgentKey");
        if (!KEY.matcher(remoteAgentKey).matches()) {
            throw new A2aDomainException("A2A_REMOTE_AGENT_KEY_INVALID",
                    "remoteAgentKey must use lowercase letters, digits, dots, underscores, or hyphens");
        }
        displayName = A2aDomainText.requireText(displayName, "displayName");
        tenantScope = tenantScope == null ? "" : tenantScope.trim();
        cardUrl = A2aDomainText.requireText(cardUrl, "cardUrl");
        cardUrlSha256 = A2aDomainText.requireText(cardUrlSha256, "cardUrlSha256");
        preferredSecuritySchemeKey = preferredSecuritySchemeKey == null
                || preferredSecuritySchemeKey.isBlank() ? null : preferredSecuritySchemeKey.trim();
        if (displayName.length() > 128 || tenantScope.length() > 96 || cardUrl.length() > 1000
                || (preferredSecuritySchemeKey != null && preferredSecuritySchemeKey.length() > 128)) {
            throw new A2aDomainException("A2A_REMOTE_AGENT_TEXT_TOO_LONG",
                    "remote Agent display name, tenant scope, or Agent Card URL is too long");
        }
        if (cardUrlSha256.length() != 64 || status == null || healthStatus == null
                || consecutiveHealthFailures < 0) {
            throw new A2aDomainException("A2A_REMOTE_AGENT_INVALID",
                    "remote Agent metadata is invalid");
        }
    }

    public A2aRemoteAgent startVerification(String actor) {
        if (status == A2aRemoteAgentStatus.ARCHIVED) {
            throw new A2aDomainException("A2A_REMOTE_AGENT_ARCHIVED",
                    "an archived remote Agent cannot be rediscovered");
        }
        return copy(currentRevisionId, preferredInterfaceKey, preferredSecuritySchemeKey,
                A2aRemoteAgentStatus.VERIFYING,
                healthStatus, consecutiveHealthFailures, lastDiscoveredAt, lastHealthCheckedAt,
                "Agent Card verification in progress", trustProfileId, credentialId, actor);
    }

    public static A2aRemoteAgent discovered(
            String remoteAgentKey,
            String displayName,
            String tenantScope,
            String cardUrl,
            String cardUrlSha256,
            String actor) {
        return new A2aRemoteAgent(
                null, remoteAgentKey, displayName, tenantScope, cardUrl, cardUrlSha256,
                null, null, null, null, null, A2aRemoteAgentStatus.VERIFYING,
                A2aRemoteAgentHealth.UNKNOWN, 0, null, null,
                "Agent Card verification in progress", 0, actor, actor,
                null, null);
    }

    public A2aRemoteAgent reviewRequired(LocalDateTime now, String summary, String actor) {
        return copy(currentRevisionId, preferredInterfaceKey, preferredSecuritySchemeKey,
                A2aRemoteAgentStatus.REVIEW_REQUIRED,
                A2aRemoteAgentHealth.HEALTHY, 0, now, now, summary,
                trustProfileId, credentialId, actor);
    }

    public A2aRemoteAgent verifiedUnchanged(
            LocalDateTime now,
            String summary,
            A2aRemoteAgentStatus statusBeforeVerification,
            A2aRemoteRevisionReviewStatus existingReviewStatus,
            String actor) {
        A2aRemoteAgentStatus next;
        if (existingReviewStatus == A2aRemoteRevisionReviewStatus.PENDING) {
            next = A2aRemoteAgentStatus.REVIEW_REQUIRED;
        } else if (existingReviewStatus == A2aRemoteRevisionReviewStatus.APPROVED
                && currentRevisionId != null) {
            next = statusBeforeVerification == A2aRemoteAgentStatus.DISABLED
                    ? A2aRemoteAgentStatus.DISABLED : A2aRemoteAgentStatus.TRUSTED;
        } else {
            next = A2aRemoteAgentStatus.QUARANTINED;
        }
        return copy(currentRevisionId, preferredInterfaceKey, preferredSecuritySchemeKey, next,
                A2aRemoteAgentHealth.HEALTHY, 0, now, now, summary,
                trustProfileId, credentialId, actor);
    }

    public A2aRemoteAgent verificationFailed(LocalDateTime now, String summary, String actor) {
        return copy(currentRevisionId, preferredInterfaceKey, preferredSecuritySchemeKey,
                A2aRemoteAgentStatus.QUARANTINED,
                A2aRemoteAgentHealth.UNREACHABLE, consecutiveHealthFailures + 1,
                lastDiscoveredAt, now, summary, trustProfileId, credentialId, actor);
    }

    public A2aRemoteAgent trust(
            long revisionId,
            String interfaceKey,
            String securitySchemeKey,
            long nextTrustProfileId,
            Long nextCredentialId,
            LocalDateTime now,
            String actor) {
        if (status != A2aRemoteAgentStatus.REVIEW_REQUIRED) {
            throw new A2aDomainException("A2A_REMOTE_REVIEW_REQUIRED",
                    "the remote Agent must be awaiting review");
        }
        String normalizedSecurityScheme = securitySchemeKey == null || securitySchemeKey.isBlank()
                ? null : securitySchemeKey.trim();
        return copy(revisionId, A2aDomainText.requireText(interfaceKey, "preferredInterfaceKey"),
                normalizedSecurityScheme,
                A2aRemoteAgentStatus.TRUSTED, A2aRemoteAgentHealth.HEALTHY, 0,
                now, now, "Remote Agent revision approved", nextTrustProfileId,
                nextCredentialId, actor);
    }

    public A2aRemoteAgent revisionRejected(LocalDateTime now, String reason, String actor) {
        return copy(currentRevisionId, preferredInterfaceKey, preferredSecuritySchemeKey,
                A2aRemoteAgentStatus.QUARANTINED,
                A2aRemoteAgentHealth.DEGRADED, consecutiveHealthFailures,
                lastDiscoveredAt, now, reason, trustProfileId, credentialId, actor);
    }

    public A2aRemoteAgent disable(String actor) {
        if (status != A2aRemoteAgentStatus.TRUSTED) {
            throw new A2aDomainException("A2A_REMOTE_AGENT_NOT_TRUSTED",
                    "only a trusted remote Agent can be disabled");
        }
        return copy(currentRevisionId, preferredInterfaceKey, preferredSecuritySchemeKey,
                A2aRemoteAgentStatus.DISABLED,
                healthStatus, consecutiveHealthFailures, lastDiscoveredAt,
                lastHealthCheckedAt, "Remote Agent disabled by administrator",
                trustProfileId, credentialId, actor);
    }

    public A2aRemoteAgent enable(String actor) {
        if (status != A2aRemoteAgentStatus.DISABLED || currentRevisionId == null) {
            throw new A2aDomainException("A2A_REMOTE_AGENT_NOT_ENABLEABLE",
                    "only a disabled Agent with an approved revision can be enabled");
        }
        return copy(currentRevisionId, preferredInterfaceKey, preferredSecuritySchemeKey,
                A2aRemoteAgentStatus.TRUSTED,
                healthStatus, consecutiveHealthFailures, lastDiscoveredAt,
                lastHealthCheckedAt, "Remote Agent enabled by administrator",
                trustProfileId, credentialId, actor);
    }

    private A2aRemoteAgent copy(
            Long revisionId,
            String interfaceKey,
            String securitySchemeKey,
            A2aRemoteAgentStatus nextStatus,
            A2aRemoteAgentHealth nextHealth,
            int healthFailures,
            LocalDateTime discoveredAt,
            LocalDateTime healthCheckedAt,
            String summary,
            Long nextTrustProfileId,
            Long nextCredentialId,
            String actor) {
        return new A2aRemoteAgent(
                id, remoteAgentKey, displayName, tenantScope, cardUrl, cardUrlSha256,
                nextTrustProfileId, nextCredentialId, revisionId, interfaceKey, securitySchemeKey,
                nextStatus, nextHealth, healthFailures, discoveredAt, healthCheckedAt,
                summary, version, createdBy, actor, createdAt, updatedAt);
    }
}
