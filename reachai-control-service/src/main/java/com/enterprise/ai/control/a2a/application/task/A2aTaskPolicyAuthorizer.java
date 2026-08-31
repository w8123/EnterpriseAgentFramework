package com.enterprise.ai.control.a2a.application.task;

import com.enterprise.ai.control.a2a.application.identity.A2aInboundCallContext;
import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.A2aProtocolOperation;
import com.enterprise.ai.control.a2a.domain.trust.A2aAuthorizationPolicy;
import com.enterprise.ai.control.a2a.domain.trust.A2aDataPolicy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

import static com.enterprise.ai.control.a2a.application.task.A2aTaskContracts.SendCommand;

@Service
public class A2aTaskPolicyAuthorizer {

    public void requireOperation(
            A2aInboundCallContext call,
            A2aProtocolOperation operation,
            String requestedTenant) {
        if (call == null || call.publication() == null || call.principal() == null
                || call.trustProfile() == null) {
            throw new A2aDomainException("A2A_AUTHENTICATION_REQUIRED",
                    "an authenticated A2A Principal is required");
        }
        if (!call.trustProfile().direction().accepts(A2aDirection.INBOUND)) {
            throw forbidden("A2A_DIRECTION_FORBIDDEN", "inbound A2A operations are not allowed");
        }
        if (operation == null) {
            throw new A2aDomainException("A2A_OPERATION_REQUIRED", "an A2A operation is required");
        }
        A2aAuthorizationPolicy policy = call.trustProfile().authorizationPolicy();
        requireAllowed(policy.publicationKeys(), call.publication().publicationKey(),
                "A2A_PUBLICATION_FORBIDDEN", "this Principal cannot access the publication");
        requireAllowed(policy.operations(), operation.wireName(),
                "A2A_OPERATION_FORBIDDEN", "this Principal cannot perform the requested operation");
        requireAllowed(call.principal().scopes(), operation.requiredScope(),
                "A2A_SCOPE_FORBIDDEN", "this Principal does not have the required scope");
        requireAllowed(call.trustProfile().allowedScopes(), operation.requiredScope(),
                "A2A_SCOPE_FORBIDDEN", "the Trust Profile does not allow the required scope");
        String publishedTenant = scope(call.publication().tenantScope());
        String principalTenant = scope(call.principal().tenantScope());
        String requestTenant = scope(requestedTenant);
        if (!publishedTenant.equals(principalTenant)
                || (!requestTenant.isEmpty() && !requestTenant.equals(publishedTenant))) {
            throw forbidden("A2A_TENANT_FORBIDDEN", "the requested tenant is not accessible");
        }
        if (!publishedTenant.isEmpty()) {
            requireAllowed(policy.tenantScopes(), publishedTenant,
                    "A2A_TENANT_FORBIDDEN", "the Trust Profile does not allow this tenant");
        }
    }

    public void requireSend(A2aInboundCallContext call, SendCommand command) {
        requireOperation(call, A2aProtocolOperation.MESSAGE_SEND,
                command == null ? null : command.tenant());
        if (command == null || command.canonicalMessage() == null) {
            throw new A2aDomainException("A2A_MESSAGE_REQUIRED", "message is required");
        }
        if (command.payloadBytes() <= 0
                || command.payloadBytes() > call.trustProfile().maxRequestBytes()) {
            throw new A2aDomainException("A2A_REQUEST_TOO_LARGE",
                    "the message exceeds the Trust Profile request size limit");
        }
        A2aDataPolicy data = call.trustProfile().dataPolicy();
        String classification = command.contentClassification() == null
                ? "INTERNAL" : command.contentClassification().trim().toUpperCase(java.util.Locale.ROOT);
        if (!data.allowedDataClassifications().contains("*")
                && !data.allowedDataClassifications().contains(classification)) {
            throw forbidden("A2A_DATA_CLASSIFICATION_FORBIDDEN",
                    "the message data classification is not allowed");
        }
        if (command.parts() == null) {
            throw new A2aDomainException("A2A_MESSAGE_PART_REQUIRED",
                    "at least one message Part is required");
        }
        requireMediaTypes(command.parts().mediaTypes(), call.publication().defaultInputModes(),
                "input");
        requireMediaTypes(Set.copyOf(command.acceptedOutputModes()),
                call.publication().defaultOutputModes(), "output");
        if (command.parts().containsText() && !data.allowTextParts()) {
            throw forbidden("A2A_TEXT_PART_FORBIDDEN", "text Parts are not allowed");
        }
        if ((command.parts().containsRaw() || command.parts().containsData()) && !data.allowFileParts()) {
            throw forbidden("A2A_FILE_PART_FORBIDDEN", "raw or structured Parts are not allowed");
        }
        if (command.parts().containsRaw()) {
            throw new A2aDomainException("A2A_UNSUPPORTED_OPERATION",
                    "raw Parts require a governed Runtime attachment adapter");
        }
        if (command.parts().containsUrl() && !data.allowUrlParts()) {
            throw forbidden("A2A_URL_PART_FORBIDDEN", "URL Parts are not allowed");
        }
        if (command.pushNotificationRequested()) {
            throw new A2aDomainException("A2A_PUSH_NOTIFICATION_NOT_SUPPORTED",
                    "push notifications are not enabled for this publication");
        }
    }

    private void requireMediaTypes(Set<String> requested, List<String> supported, String direction) {
        if (requested == null || requested.isEmpty()) {
            return;
        }
        for (String mediaType : requested) {
            if (!supports(supported, mediaType)) {
                throw new A2aDomainException("A2A_CONTENT_TYPE_NOT_SUPPORTED",
                        direction + " media type is not supported: " + mediaType);
            }
        }
    }

    private boolean supports(List<String> supported, String requested) {
        if (requested == null || requested.isBlank() || supported == null) {
            return false;
        }
        String normalized = requested.trim().toLowerCase(java.util.Locale.ROOT);
        for (String candidate : supported) {
            if (candidate == null) {
                continue;
            }
            String allowed = candidate.trim().toLowerCase(java.util.Locale.ROOT);
            if ("*/*".equals(allowed) || allowed.equals(normalized)) {
                return true;
            }
            int slash = allowed.indexOf('/');
            if (slash > 0 && allowed.endsWith("/*")
                    && normalized.startsWith(allowed.substring(0, slash + 1))) {
                return true;
            }
        }
        return false;
    }

    private void requireAllowed(Set<String> allowlist, String value, String code, String detail) {
        if (allowlist == null || !(allowlist.contains("*") || allowlist.contains(value))) {
            throw forbidden(code, detail);
        }
    }

    private A2aDomainException forbidden(String code, String detail) {
        return new A2aDomainException(code, detail);
    }

    private String scope(String value) {
        return value == null ? "" : value.trim();
    }
}
