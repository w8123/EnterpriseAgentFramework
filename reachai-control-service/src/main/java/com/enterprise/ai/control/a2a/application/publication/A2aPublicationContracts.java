package com.enterprise.ai.control.a2a.application.publication;

import com.enterprise.ai.control.a2a.domain.publication.A2aPublication;
import com.enterprise.ai.control.a2a.domain.publication.A2aPublicationRevision;

import java.time.LocalDateTime;
import java.util.List;

public final class A2aPublicationContracts {

    private A2aPublicationContracts() {
    }

    public record CreateRequest(
            String publicationKey,
            String agentId,
            String environment,
            String tenantScope,
            String publicHost,
            Long trustProfileId,
            RevisionDraftRequest revision) {
    }

    public record RevisionDraftRequest(
            Long agentConfigVersionId,
            String agentVersion,
            String name,
            String description,
            String providerOrganization,
            String providerUrl,
            String documentationUrl,
            String iconUrl,
            String publicOrigin,
            Boolean streamingSupported,
            Boolean pushNotificationsSupported,
            Boolean extendedCardSupported,
            List<String> defaultInputModes,
            List<String> defaultOutputModes,
            List<ProtocolSkillRequest> protocolSkills) {
    }

    public record ProtocolSkillRequest(
            String id,
            String name,
            String description,
            List<String> tags,
            List<String> examples,
            List<String> inputModes,
            List<String> outputModes) {
    }

    public record RevisionCommandRequest(Long revisionId) {
    }

    public record PublicationView(
            String schema,
            Long id,
            String publicationKey,
            String agentId,
            Long projectId,
            String projectCode,
            String environment,
            String tenantScope,
            String publicHost,
            long trustProfileId,
            Long currentRevisionId,
            String status,
            int version,
            LocalDateTime publishedAt,
            LocalDateTime suspendedAt,
            String createdBy,
            String updatedBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {

        public static PublicationView from(A2aPublication publication) {
            return new PublicationView(
                    "reachai.a2a-hub.publication.v1",
                    publication.id(), publication.publicationKey(), publication.agentId(),
                    publication.projectId(), publication.projectCode(), publication.environment().name(),
                    publication.tenantScope(), publication.publicHost(), publication.trustProfileId(),
                    publication.currentRevisionId(), publication.status().name(), publication.version(),
                    publication.publishedAt(), publication.suspendedAt(), publication.createdBy(),
                    publication.updatedBy(), publication.createdAt(), publication.updatedAt());
        }
    }

    public record RevisionView(
            String schema,
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
            List<ProtocolSkillView> protocolSkills,
            String agentCardJson,
            String agentCardSha256,
            String signatureStatus,
            String conformanceStatus,
            String validationSummaryJson,
            String status,
            String createdBy,
            LocalDateTime createdAt,
            LocalDateTime publishedAt) {

        public static RevisionView from(A2aPublicationRevision revision) {
            return new RevisionView(
                    "reachai.a2a-hub.publication-revision.v1",
                    revision.id(), revision.publicationId(), revision.revisionNo(),
                    revision.agentConfigVersionId(), revision.agentVersion(), revision.name(),
                    revision.description(), revision.providerOrganization(), revision.providerUrl(),
                    revision.documentationUrl(), revision.iconUrl(), revision.publicOrigin(),
                    revision.protocolBasePath(), revision.protocolBinding(), revision.protocolVersion(),
                    revision.streamingSupported(), revision.pushNotificationsSupported(),
                    revision.extendedCardSupported(), revision.defaultInputModes(),
                    revision.defaultOutputModes(), revision.protocolSkills().stream()
                            .map(ProtocolSkillView::from).toList(),
                    revision.cardSnapshot().agentCardJson(),
                    revision.cardSnapshot().agentCardSha256(), revision.signatureStatus(),
                    revision.conformanceStatus(), revision.cardSnapshot().validationSummaryJson(),
                    revision.status().name(), revision.createdBy(), revision.createdAt(),
                    revision.publishedAt());
        }
    }

    public record ProtocolSkillView(
            String id,
            String name,
            String description,
            List<String> tags,
            List<String> examples,
            List<String> inputModes,
            List<String> outputModes) {

        private static ProtocolSkillView from(
                com.enterprise.ai.control.a2a.domain.publication.A2aProtocolSkill skill) {
            return new ProtocolSkillView(skill.id(), skill.name(), skill.description(), skill.tags(),
                    skill.examples(), skill.inputModes(), skill.outputModes());
        }
    }

    public record DetailView(
            String schema,
            PublicationView publication,
            List<RevisionView> revisions) {

        public DetailView {
            revisions = revisions == null ? List.of() : List.copyOf(revisions);
        }
    }
}
