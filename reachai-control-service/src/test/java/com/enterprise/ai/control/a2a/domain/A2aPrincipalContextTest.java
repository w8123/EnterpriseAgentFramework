package com.enterprise.ai.control.a2a.domain;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2aPrincipalContextTest {

    @Test
    void createsImmutableServerAttestedPrincipalScope() {
        Set<String> sourceScopes = new HashSet<>(Set.of("task:send"));
        A2aPrincipalContext context = new A2aPrincipalContext(
                7L,
                "partner-order-agent",
                A2aPrincipalType.REMOTE_AGENT,
                "tenant-a",
                A2aAuthenticationMethod.OAUTH2_CLIENT_CREDENTIALS,
                A2aTrustLevel.TRUSTED,
                sourceScopes,
                null);

        sourceScopes.add("task:admin");

        assertTrue(context.hasScope("task:send"));
        assertFalse(context.hasScope("task:admin"));
        assertThrows(UnsupportedOperationException.class,
                () -> context.scopes().add("task:cancel"));
    }

    @Test
    void rejectsSyntheticOrIncompletePrincipalIdentity() {
        A2aDomainException exception = assertThrows(A2aDomainException.class,
                () -> new A2aPrincipalContext(
                        0,
                        "from-message-metadata",
                        A2aPrincipalType.REMOTE_AGENT,
                        "",
                        A2aAuthenticationMethod.ANONYMOUS,
                        A2aTrustLevel.UNTRUSTED,
                        Set.of(),
                        null));

        assertEquals("A2A_PRINCIPAL_INVALID", exception.code());
    }
}
