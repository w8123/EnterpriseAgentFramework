package com.enterprise.ai.control.a2a.application.port;

import com.enterprise.ai.control.a2a.domain.identity.A2aCredential;
import com.enterprise.ai.control.a2a.domain.identity.A2aCredentialType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Interprets the remote Agent Card security contract without exposing credential material.
 * A requirement array is OR-ed; schemes inside one requirement object are AND-ed.
 */
public interface A2aRemoteAuthenticationPlanner {

    Assessment assess(String securitySchemesJson, String securityRequirementsJson);

    Binding requireBinding(
            String securitySchemesJson,
            String securityRequirementsJson,
            String requestedSecuritySchemeKey,
            A2aCredential credential,
            LocalDateTime now);

    record Assessment(
            boolean authenticationRequired,
            boolean anonymousAllowed,
            List<Option> options,
            String supportCode) {

        public Assessment {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    record Option(
            String securitySchemeKey,
            String schemeType,
            A2aCredentialType credentialType,
            String placement,
            String headerName,
            String authorizationScheme,
            List<String> scopes,
            boolean selectable,
            String supportCode) {

        public Option {
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }
    }

    /** Metadata-only transport binding. The secret remains in the credential vault. */
    record Binding(
            String securitySchemeKey,
            Long credentialId,
            A2aCredentialType credentialType,
            String headerName,
            String valuePrefix,
            List<String> scopes) {

        public Binding {
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }

        public static Binding anonymous() {
            return new Binding(null, null, null, null, null, List.of());
        }

        public boolean authenticated() {
            return credentialId != null;
        }
    }
}
