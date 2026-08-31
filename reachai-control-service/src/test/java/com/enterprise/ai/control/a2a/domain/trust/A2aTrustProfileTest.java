package com.enterprise.ai.control.a2a.domain.trust;

import com.enterprise.ai.control.a2a.domain.A2aAuthenticationMethod;
import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.A2aTrustLevel;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class A2aTrustProfileTest {

    @Test
    void rejectsAnonymousAuthenticationOutsideDevelopment() {
        A2aDomainException error = assertThrows(A2aDomainException.class, () -> profile(
                A2aEnvironment.PRODUCTION,
                A2aTrustLevel.KNOWN,
                Set.of(A2aAuthenticationMethod.ANONYMOUS),
                true,
                A2aDelegatedIdentityPolicy.DENY,
                A2aPersonalMemoryPolicy.DISABLED));

        assertEquals("A2A_ANONYMOUS_ENVIRONMENT_FORBIDDEN", error.code());
    }

    @Test
    void requiresExplicitAnonymousFlagToMatchTheAdvertisedAuthenticationMethod() {
        A2aDomainException error = assertThrows(A2aDomainException.class, () -> profile(
                A2aEnvironment.DEVELOPMENT,
                A2aTrustLevel.KNOWN,
                Set.of(A2aAuthenticationMethod.ANONYMOUS),
                false,
                A2aDelegatedIdentityPolicy.DENY,
                A2aPersonalMemoryPolicy.DISABLED));

        assertEquals("A2A_ANONYMOUS_CONTRACT_MISMATCH", error.code());
    }

    @Test
    void requiresAttestedDelegationBeforePersonalMemoryCanBeEnabled() {
        A2aDomainException error = assertThrows(A2aDomainException.class, () -> profile(
                A2aEnvironment.PRODUCTION,
                A2aTrustLevel.TRUSTED,
                Set.of(A2aAuthenticationMethod.MTLS),
                false,
                A2aDelegatedIdentityPolicy.DENY,
                A2aPersonalMemoryPolicy.ATTESTED_USER_ONLY));

        assertEquals("A2A_MEMORY_IDENTITY_REQUIRED", error.code());
    }

    @Test
    void acceptsAProductionProfileWithStrongAuthenticationAndSafeDefaults() {
        A2aTrustProfile profile = profile(
                A2aEnvironment.PRODUCTION,
                A2aTrustLevel.TRUSTED,
                Set.of(A2aAuthenticationMethod.MTLS, A2aAuthenticationMethod.HMAC),
                false,
                A2aDelegatedIdentityPolicy.DENY,
                A2aPersonalMemoryPolicy.DISABLED);

        assertEquals(A2aTrustProfileStatus.ACTIVE, profile.status());
        assertEquals(2, profile.authenticationMethods().size());
    }

    private A2aTrustProfile profile(
            A2aEnvironment environment,
            A2aTrustLevel trustLevel,
            Set<A2aAuthenticationMethod> methods,
            boolean allowAnonymous,
            A2aDelegatedIdentityPolicy delegatedIdentityPolicy,
            A2aPersonalMemoryPolicy personalMemoryPolicy) {
        return new A2aTrustProfile(
                null,
                "partner-default",
                "Partner default",
                null,
                A2aDirection.INBOUND,
                environment,
                trustLevel,
                methods,
                Set.of("a2a:message:send"),
                new A2aAuthorizationPolicy(
                        Set.of("support-agent"),
                        Set.of("*"),
                        Set.of("answer-question"),
                        Set.of("message:send", "tasks:get"),
                        Set.of("tenant-a")),
                new A2aDataPolicy(Set.of("INTERNAL"), true, false, false, 30),
                delegatedIdentityPolicy,
                personalMemoryPolicy,
                60,
                10,
                1_048_576,
                10_485_760,
                300_000,
                allowAnonymous,
                A2aTrustProfileStatus.ACTIVE,
                0,
                "tester",
                "tester",
                null,
                null);
    }
}
