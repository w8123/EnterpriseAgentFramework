package com.enterprise.ai.control.a2a.domain.remoteagent;

import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.A2aDomainText;

import java.util.List;

public record A2aRemoteCardSnapshot(
        String name,
        String description,
        String providerOrganization,
        String providerUrl,
        String documentationUrl,
        String iconUrl,
        String agentVersion,
        List<A2aRemoteInterface> supportedInterfaces,
        A2aRemoteCapabilities capabilities,
        List<String> defaultInputModes,
        List<String> defaultOutputModes,
        List<A2aRemoteProtocolSkill> protocolSkills,
        String securitySchemesJson,
        String securityRequirementsJson,
        String agentCardJson,
        String agentCardSha256,
        String signatureStatus,
        String signingKeyId) {

    public A2aRemoteCardSnapshot {
        name = A2aDomainText.requireText(name, "card.name");
        description = A2aDomainText.requireText(description, "card.description");
        agentVersion = A2aDomainText.requireText(agentVersion, "card.version");
        supportedInterfaces = supportedInterfaces == null ? List.of() : List.copyOf(supportedInterfaces);
        if (supportedInterfaces.stream().noneMatch(A2aRemoteInterface::supportedByFirstRelease)) {
            throw new A2aDomainException("A2A_REMOTE_INTERFACE_NOT_SUPPORTED",
                    "the Agent Card must declare an A2A 1.0 HTTP+JSON interface");
        }
        capabilities = capabilities == null
                ? new A2aRemoteCapabilities(false, false, false) : capabilities;
        defaultInputModes = requireModes(defaultInputModes, "defaultInputModes");
        defaultOutputModes = requireModes(defaultOutputModes, "defaultOutputModes");
        protocolSkills = protocolSkills == null ? List.of() : List.copyOf(protocolSkills);
        agentCardJson = A2aDomainText.requireText(agentCardJson, "agentCardJson");
        agentCardSha256 = A2aDomainText.requireText(agentCardSha256, "agentCardSha256");
        if (agentCardSha256.length() != 64) {
            throw new A2aDomainException("A2A_REMOTE_CARD_HASH_INVALID",
                    "Agent Card SHA-256 is invalid");
        }
        signatureStatus = A2aDomainText.requireText(signatureStatus, "signatureStatus");
    }

    public boolean requiresAuthentication() {
        return securityRequirementsJson != null && !securityRequirementsJson.isBlank()
                && !"[]".equals(securityRequirementsJson.trim());
    }

    private static List<String> requireModes(List<String> modes, String field) {
        if (modes == null || modes.isEmpty()) {
            throw new A2aDomainException("A2A_REMOTE_MEDIA_MODE_REQUIRED",
                    "Agent Card " + field + " must not be empty");
        }
        return List.copyOf(modes);
    }
}
