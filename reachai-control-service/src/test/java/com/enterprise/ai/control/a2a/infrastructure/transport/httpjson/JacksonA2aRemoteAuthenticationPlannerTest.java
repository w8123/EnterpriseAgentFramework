package com.enterprise.ai.control.a2a.infrastructure.transport.httpjson;

import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.identity.A2aCredential;
import com.enterprise.ai.control.a2a.domain.identity.A2aCredentialStatus;
import com.enterprise.ai.control.a2a.domain.identity.A2aCredentialType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JacksonA2aRemoteAuthenticationPlannerTest {

    private final JacksonA2aRemoteAuthenticationPlanner planner =
            new JacksonA2aRemoteAuthenticationPlanner(new ObjectMapper());
    private final LocalDateTime now = LocalDateTime.of(2026, 8, 24, 0, 0);

    @Test
    void bindsAnExplicitBearerAlternativeToAnEncryptedOutboundCredential() {
        var assessment = planner.assess(
                "{\"partnerBearer\":{\"httpAuthSecurityScheme\":{\"scheme\":\"Bearer\"}}}",
                "[{\"partnerBearer\":[]}]");

        assertTrue(assessment.authenticationRequired());
        assertEquals("SUPPORTED", assessment.supportCode());
        assertTrue(assessment.options().get(0).selectable());

        var binding = planner.requireBinding(
                "{\"partnerBearer\":{\"httpAuthSecurityScheme\":{\"scheme\":\"Bearer\"}}}",
                "[{\"partnerBearer\":[]}]", "partnerBearer",
                credential(A2aCredentialType.BEARER), now);

        assertTrue(binding.authenticated());
        assertEquals("Authorization", binding.headerName());
        assertEquals("Bearer ", binding.valuePrefix());
        assertEquals(41L, binding.credentialId());
    }

    @Test
    void supportsOnlySafeHeaderApiKeysAndRejectsTypeMismatch() {
        String schemes = "{\"partnerKey\":{\"apiKeySecurityScheme\":"
                + "{\"location\":\"header\",\"name\":\"X-Partner-Key\"}}}";
        String requirements = "[{\"partnerKey\":[]}]";

        var option = planner.assess(schemes, requirements).options().get(0);
        assertTrue(option.selectable());
        assertEquals("X-Partner-Key", option.headerName());

        A2aDomainException mismatch = assertThrows(A2aDomainException.class,
                () -> planner.requireBinding(schemes, requirements, "partnerKey",
                        credential(A2aCredentialType.BEARER), now));
        assertEquals("A2A_OUTBOUND_CREDENTIAL_TYPE_MISMATCH", mismatch.code());

        var unsafe = planner.assess(
                "{\"bad\":{\"apiKeySecurityScheme\":"
                        + "{\"location\":\"header\",\"name\":\"Host\"}}}",
                "[{\"bad\":[]}]").options().get(0);
        assertFalse(unsafe.selectable());
        assertEquals("A2A_API_KEY_HEADER_UNSAFE", unsafe.supportCode());
    }

    @Test
    void neverSilentlyDropsCompositeRequirementsAndAllowsExplicitAnonymousAlternative() {
        String schemes = "{"
                + "\"one\":{\"httpAuthSecurityScheme\":{\"scheme\":\"Bearer\"}},"
                + "\"two\":{\"apiKeySecurityScheme\":{\"location\":\"header\",\"name\":\"X-Key\"}}"
                + "}";
        var composite = planner.assess(schemes, "[{\"one\":[],\"two\":[]}]");
        assertTrue(composite.authenticationRequired());
        assertFalse(composite.options().stream().anyMatch(value -> value.selectable()));
        A2aDomainException failure = assertThrows(A2aDomainException.class,
                () -> planner.requireBinding(schemes, "[{\"one\":[],\"two\":[]}]",
                        "one", credential(A2aCredentialType.BEARER), now));
        assertEquals("A2A_COMPOSITE_SECURITY_REQUIREMENT_UNSUPPORTED", failure.code());

        var optional = planner.assess(schemes, "[{}, {\"one\":[]}]");
        assertFalse(optional.authenticationRequired());
        assertTrue(optional.anonymousAllowed());
        assertFalse(planner.requireBinding(schemes, "[{}, {\"one\":[]}]",
                null, null, now).authenticated());
    }

    private A2aCredential credential(A2aCredentialType type) {
        return new A2aCredential(
                41L, "partner-secret", "Partner Secret", A2aDirection.OUTBOUND, type,
                "ENCRYPTED", null, "ciphertext", "credential-key-v1", "nonce", null,
                "a".repeat(64), 1, A2aCredentialStatus.ACTIVE,
                now.minusDays(1), now.plusDays(30), null, null, "operator", now, now);
    }
}
