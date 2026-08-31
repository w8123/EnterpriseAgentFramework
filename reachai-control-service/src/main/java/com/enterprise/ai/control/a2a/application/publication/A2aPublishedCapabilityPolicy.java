package com.enterprise.ai.control.a2a.application.publication;

import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The protocol capabilities that this deployed Control adapter can honestly advertise.
 * Product input may narrow this contract, but it cannot claim an implementation that
 * does not exist in the current release.
 */
@Component
public class A2aPublishedCapabilityPolicy {

    private static final Set<String> INPUT_MEDIA_TYPES = Set.of(
            "text/plain", "application/json", "text/uri-list");
    private static final Set<String> OUTPUT_MEDIA_TYPES = Set.of("text/plain");

    public void validate(A2aPublicationContracts.RevisionDraftRequest request) {
        if (request == null) {
            throw new A2aDomainException("A2A_REVISION_REQUEST_REQUIRED", "revision is required");
        }
        if (Boolean.TRUE.equals(request.streamingSupported())) {
            throw unsupported("streaming");
        }
        if (Boolean.TRUE.equals(request.pushNotificationsSupported())) {
            throw unsupported("push notifications");
        }
        if (Boolean.TRUE.equals(request.extendedCardSupported())) {
            throw unsupported("extended Agent Card");
        }
        requireModes(request.defaultInputModes(), INPUT_MEDIA_TYPES, "defaultInputModes");
        requireModes(request.defaultOutputModes(), OUTPUT_MEDIA_TYPES, "defaultOutputModes");
        if (request.protocolSkills() != null) {
            request.protocolSkills().forEach(skill -> {
                if (skill == null) {
                    throw new A2aDomainException("A2A_PROTOCOL_SKILL_INVALID",
                            "protocolSkills must not contain null entries");
                }
                requireOptionalModes(skill.inputModes(), INPUT_MEDIA_TYPES, "skill.inputModes");
                requireOptionalModes(skill.outputModes(), OUTPUT_MEDIA_TYPES, "skill.outputModes");
            });
        }
    }

    private void requireModes(List<String> requested, Set<String> supported, String field) {
        if (requested == null || requested.isEmpty()) {
            throw new A2aDomainException("A2A_MEDIA_MODE_REQUIRED", field + " must not be empty");
        }
        requireOptionalModes(requested, supported, field);
    }

    private void requireOptionalModes(List<String> requested, Set<String> supported, String field) {
        if (requested == null) {
            return;
        }
        for (String mode : requested) {
            String normalized = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
            if (!supported.contains(normalized)) {
                throw new A2aDomainException("A2A_MEDIA_MODE_NOT_IMPLEMENTED",
                        field + " is not implemented by the deployed A2A adapter: " + normalized);
            }
        }
    }

    private A2aDomainException unsupported(String capability) {
        return new A2aDomainException("A2A_CAPABILITY_NOT_IMPLEMENTED",
                capability + " is not implemented by the deployed A2A adapter");
    }
}
