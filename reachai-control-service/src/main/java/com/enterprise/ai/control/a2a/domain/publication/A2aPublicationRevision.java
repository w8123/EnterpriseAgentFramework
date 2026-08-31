package com.enterprise.ai.control.a2a.domain.publication;

import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.A2aDomainText;

import java.time.LocalDateTime;
import java.util.List;

public record A2aPublicationRevision(
        Long id,
        long publicationId,
        int revisionNo,
        long agentConfigVersionId,
        String agentVersion,
        String name,
        String description,
        String providerOrganization,
        String providerUrl,
        String documentationUrl,
        String iconUrl,
        String publicOrigin,
        String protocolBasePath,
        String protocolBinding,
        String protocolVersion,
        boolean streamingSupported,
        boolean pushNotificationsSupported,
        boolean extendedCardSupported,
        List<String> defaultInputModes,
        List<String> defaultOutputModes,
        List<A2aProtocolSkill> protocolSkills,
        A2aAgentCardSnapshot cardSnapshot,
        String signatureStatus,
        String conformanceStatus,
        A2aPublicationRevisionStatus status,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime publishedAt) {

    public A2aPublicationRevision {
        if (publicationId <= 0 || revisionNo <= 0 || agentConfigVersionId <= 0) {
            throw new A2aDomainException("A2A_PUBLICATION_REVISION_ID_INVALID",
                    "publication, revision, and Agent config identifiers must be positive");
        }
        agentVersion = A2aDomainText.requireText(agentVersion, "agentVersion");
        name = A2aDomainText.requireText(name, "name");
        description = A2aDomainText.requireText(description, "description");
        publicOrigin = A2aDomainText.requireText(publicOrigin, "publicOrigin");
        protocolBasePath = A2aDomainText.requireText(protocolBasePath, "protocolBasePath");
        protocolBinding = A2aDomainText.requireText(protocolBinding, "protocolBinding");
        protocolVersion = A2aDomainText.requireText(protocolVersion, "protocolVersion");
        if (!"/a2a/v1".equals(protocolBasePath)
                || !"HTTP+JSON".equals(protocolBinding)
                || !"1.0".equals(protocolVersion)) {
            throw new A2aDomainException("A2A_PROTOCOL_CONTRACT_INVALID",
                    "the initial A2A Hub release supports only HTTP+JSON 1.0 at /a2a/v1");
        }
        defaultInputModes = normalized(defaultInputModes);
        defaultOutputModes = normalized(defaultOutputModes);
        defaultInputModes.forEach(mode -> A2aProtocolSkill.requireMediaType(mode, "defaultInputModes"));
        defaultOutputModes.forEach(mode -> A2aProtocolSkill.requireMediaType(mode, "defaultOutputModes"));
        if (defaultInputModes.isEmpty() || defaultOutputModes.isEmpty()) {
            throw new A2aDomainException("A2A_DEFAULT_MODE_REQUIRED",
                    "defaultInputModes and defaultOutputModes are required");
        }
        java.util.Set<String> implementedInputs = java.util.Set.of(
                "text/plain", "application/json", "text/uri-list");
        java.util.Set<String> implementedOutputs = java.util.Set.of("text/plain");
        if (defaultInputModes.stream().map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .anyMatch(value -> !implementedInputs.contains(value))
                || defaultOutputModes.stream().map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .anyMatch(value -> !implementedOutputs.contains(value))) {
            throw new A2aDomainException("A2A_MEDIA_MODE_NOT_IMPLEMENTED",
                    "the initial Runtime adapter supports text/plain, application/json, "
                            + "text/uri-list input and text/plain output only");
        }
        protocolSkills = protocolSkills == null ? List.of() : List.copyOf(protocolSkills);
        if (protocolSkills.isEmpty()) {
            throw new A2aDomainException("A2A_PROTOCOL_SKILL_REQUIRED",
                    "at least one A2A protocol skill is required");
        }
        if ((providerOrganization == null) != (providerUrl == null)) {
            throw new A2aDomainException("A2A_PROVIDER_INCOMPLETE",
                    "providerOrganization and providerUrl must be provided together");
        }
        if (cardSnapshot == null || status == null) {
            throw new A2aDomainException("A2A_REQUIRED_FIELD", "cardSnapshot and status are required");
        }
        signatureStatus = A2aDomainText.requireText(signatureStatus, "signatureStatus");
        conformanceStatus = A2aDomainText.requireText(conformanceStatus, "conformanceStatus");
    }

    public A2aPublicationRevision markReady(A2aAgentCardSnapshot regenerated) {
        if (status != A2aPublicationRevisionStatus.DRAFT
                && status != A2aPublicationRevisionStatus.READY) {
            throw new A2aDomainException("A2A_REVISION_NOT_DRAFT",
                    "only a draft revision can be preflighted");
        }
        if (!cardSnapshot.agentCardSha256().equals(regenerated.agentCardSha256())) {
            throw new A2aDomainException("A2A_AGENT_CARD_DRIFT",
                    "the regenerated Agent Card differs from the immutable revision snapshot");
        }
        return copy(A2aPublicationRevisionStatus.READY, regenerated, publishedAt);
    }

    public A2aPublicationRevision markPublished(LocalDateTime now) {
        if (status != A2aPublicationRevisionStatus.READY
                && status != A2aPublicationRevisionStatus.PUBLISHED) {
            throw new A2aDomainException("A2A_REVISION_NOT_READY",
                    "the publication revision must pass preflight before publishing");
        }
        return copy(A2aPublicationRevisionStatus.PUBLISHED, cardSnapshot,
                publishedAt == null ? now : publishedAt);
    }

    private A2aPublicationRevision copy(
            A2aPublicationRevisionStatus next,
            A2aAgentCardSnapshot snapshot,
            LocalDateTime published) {
        return new A2aPublicationRevision(id, publicationId, revisionNo, agentConfigVersionId,
                agentVersion, name, description, providerOrganization, providerUrl,
                documentationUrl, iconUrl, publicOrigin, protocolBasePath, protocolBinding,
                protocolVersion, streamingSupported, pushNotificationsSupported,
                extendedCardSupported, defaultInputModes, defaultOutputModes, protocolSkills,
                snapshot, signatureStatus, conformanceStatus, next, createdBy, createdAt, published);
    }

    private static List<String> normalized(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
