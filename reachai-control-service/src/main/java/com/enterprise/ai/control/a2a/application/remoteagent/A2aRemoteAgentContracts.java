package com.enterprise.ai.control.a2a.application.remoteagent;

import com.enterprise.ai.control.a2a.application.port.A2aRemoteAuthenticationPlanner;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgent;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgentRevision;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteInterface;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteProtocolSkill;

import java.time.LocalDateTime;
import java.util.List;

public final class A2aRemoteAgentContracts {

    private A2aRemoteAgentContracts() {
    }

    public record DiscoverRequest(
            String remoteAgentKey,
            String displayName,
            String tenantScope,
            String agentCardUrl) {
    }

    public record ReviewRequest(
            String preferredInterfaceKey,
            String preferredSecuritySchemeKey,
            Long trustProfileId,
            Long credentialId) {
    }

    public record RejectRequest(String reason) {
    }

    public record RemoteAgentView(
            String schema,
            Long id,
            String remoteAgentKey,
            String displayName,
            String tenantScope,
            String agentCardUrl,
            Long trustProfileId,
            Long credentialId,
            Long currentRevisionId,
            String preferredInterfaceKey,
            String preferredSecuritySchemeKey,
            String status,
            String healthStatus,
            int consecutiveHealthFailures,
            LocalDateTime lastDiscoveredAt,
            LocalDateTime lastHealthCheckedAt,
            String lastHealthSummary,
            boolean callable,
            String callabilityCode,
            int version,
            String createdBy,
            String updatedBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {

        public static RemoteAgentView from(A2aRemoteAgent value) {
            String callabilityCode = callabilityCode(value);
            return new RemoteAgentView(
                    "reachai.a2a-hub.remote-agent.v1", value.id(), value.remoteAgentKey(),
                    value.displayName(), value.tenantScope(), value.cardUrl(), value.trustProfileId(),
                    value.credentialId(), value.currentRevisionId(), value.preferredInterfaceKey(),
                    value.preferredSecuritySchemeKey(),
                    value.status().name(), value.healthStatus().name(),
                    value.consecutiveHealthFailures(), value.lastDiscoveredAt(),
                    value.lastHealthCheckedAt(), value.lastHealthSummary(),
                    "READY".equals(callabilityCode),
                    callabilityCode, value.version(), value.createdBy(), value.updatedBy(),
                    value.createdAt(), value.updatedAt());
        }

        private static String callabilityCode(A2aRemoteAgent value) {
            if (!"TRUSTED".equals(value.status().name())) return "REMOTE_AGENT_NOT_TRUSTED";
            if (value.currentRevisionId() == null || value.preferredInterfaceKey() == null) {
                return "APPROVED_REVISION_NOT_SELECTED";
            }
            if ("UNREACHABLE".equals(value.healthStatus().name())) {
                return "REMOTE_AGENT_UNREACHABLE";
            }
            return "READY";
        }
    }

    public record RemoteAuthenticationOptionView(
            String securitySchemeKey,
            String schemeType,
            String credentialType,
            String placement,
            String headerName,
            String authorizationScheme,
            List<String> scopes,
            boolean selectable,
            String supportCode) {

        public static RemoteAuthenticationOptionView from(
                A2aRemoteAuthenticationPlanner.Option value) {
            return new RemoteAuthenticationOptionView(
                    value.securitySchemeKey(), value.schemeType(),
                    value.credentialType() == null ? null : value.credentialType().name(),
                    value.placement(), value.headerName(), value.authorizationScheme(),
                    value.scopes(), value.selectable(), value.supportCode());
        }
    }

    public record RemoteRevisionView(
            String schema,
            Long id,
            long remoteAgentId,
            int revisionNo,
            String name,
            String description,
            String providerOrganization,
            String providerUrl,
            String documentationUrl,
            String iconUrl,
            String agentVersion,
            List<A2aRemoteInterface> supportedInterfaces,
            boolean streamingSupported,
            boolean pushNotificationsSupported,
            boolean extendedCardSupported,
            List<String> defaultInputModes,
            List<String> defaultOutputModes,
            List<A2aRemoteProtocolSkill> protocolSkills,
            String securitySchemesJson,
            String securityRequirementsJson,
            boolean authenticationRequired,
            boolean anonymousAccessAllowed,
            List<RemoteAuthenticationOptionView> authenticationOptions,
            String authenticationSupportCode,
            String agentCardJson,
            String agentCardSha256,
            String signatureStatus,
            String signingKeyId,
            String tlsIdentitySha256,
            String networkEvidenceJson,
            String httpEtag,
            String httpLastModified,
            String reviewStatus,
            LocalDateTime discoveredAt,
            String reviewedBy,
            LocalDateTime reviewedAt,
            LocalDateTime createdAt) {

        public static RemoteRevisionView from(
                A2aRemoteAgentRevision value,
                A2aRemoteAuthenticationPlanner.Assessment authentication) {
            var card = value.card();
            return new RemoteRevisionView(
                    "reachai.a2a-hub.remote-agent-revision.v1", value.id(), value.remoteAgentId(),
                    value.revisionNo(), card.name(), card.description(), card.providerOrganization(),
                    card.providerUrl(), card.documentationUrl(), card.iconUrl(), card.agentVersion(),
                    card.supportedInterfaces(),
                    card.capabilities().streaming(), card.capabilities().pushNotifications(),
                    card.capabilities().extendedAgentCard(), card.defaultInputModes(),
                    card.defaultOutputModes(), card.protocolSkills(), card.securitySchemesJson(),
                    card.securityRequirementsJson(), authentication.authenticationRequired(),
                    authentication.anonymousAllowed(), authentication.options().stream()
                            .map(RemoteAuthenticationOptionView::from).toList(),
                    authentication.supportCode(),
                    card.agentCardJson(), card.agentCardSha256(), card.signatureStatus(),
                    card.signingKeyId(), value.tlsIdentitySha256(), value.networkEvidenceJson(),
                    value.httpEtag(), value.httpLastModified(), value.reviewStatus().name(),
                    value.discoveredAt(), value.reviewedBy(), value.reviewedAt(), value.createdAt());
        }
    }

    public record DetailView(
            String schema,
            RemoteAgentView remoteAgent,
            List<RemoteRevisionView> revisions) {

        public DetailView {
            revisions = revisions == null ? List.of() : List.copyOf(revisions);
        }
    }

    public record DiscoveryResult(
            String schema,
            String outcome,
            String reasonCode,
            DetailView detail) {
    }
}
