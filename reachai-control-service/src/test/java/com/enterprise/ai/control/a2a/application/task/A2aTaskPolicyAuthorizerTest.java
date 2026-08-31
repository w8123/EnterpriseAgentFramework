package com.enterprise.ai.control.a2a.application.task;

import com.enterprise.ai.control.a2a.application.identity.A2aInboundCallContext;
import com.enterprise.ai.control.a2a.application.port.A2aPublicationRepository.PublishedCard;
import com.enterprise.ai.control.a2a.domain.A2aAuthenticationMethod;
import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.A2aPrincipalContext;
import com.enterprise.ai.control.a2a.domain.A2aPrincipalType;
import com.enterprise.ai.control.a2a.domain.A2aProtocolOperation;
import com.enterprise.ai.control.a2a.domain.A2aTrustLevel;
import com.enterprise.ai.control.a2a.domain.trust.A2aAuthorizationPolicy;
import com.enterprise.ai.control.a2a.domain.trust.A2aDataPolicy;
import com.enterprise.ai.control.a2a.domain.trust.A2aDelegatedIdentityPolicy;
import com.enterprise.ai.control.a2a.domain.trust.A2aEnvironment;
import com.enterprise.ai.control.a2a.domain.trust.A2aPersonalMemoryPolicy;
import com.enterprise.ai.control.a2a.domain.trust.A2aTrustProfile;
import com.enterprise.ai.control.a2a.domain.trust.A2aTrustProfileStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static com.enterprise.ai.control.a2a.application.task.A2aTaskContracts.PartProfile;
import static com.enterprise.ai.control.a2a.application.task.A2aTaskContracts.SendCommand;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class A2aTaskPolicyAuthorizerTest {

    private final A2aTaskPolicyAuthorizer authorizer = new A2aTaskPolicyAuthorizer();

    @Test
    void authorizesCanonicalTaskOperationsWithTheirLeastPrivilegeScopes() {
        A2aInboundCallContext read = call(
                Set.of("a2a:task:read"),
                Set.of("a2a:task:read"),
                Set.of("tasks:get", "tasks:list"));
        A2aInboundCallContext cancel = call(
                Set.of("a2a:task:cancel"),
                Set.of("a2a:task:cancel"),
                Set.of("tasks:cancel"));

        assertDoesNotThrow(() -> authorizer.requireOperation(
                read, A2aProtocolOperation.TASKS_GET, null));
        assertDoesNotThrow(() -> authorizer.requireOperation(
                read, A2aProtocolOperation.TASKS_LIST, null));
        assertDoesNotThrow(() -> authorizer.requireOperation(
                cancel, A2aProtocolOperation.TASKS_CANCEL, null));
    }

    @Test
    void rejectsNonCanonicalSingularTaskOperationConfiguration() {
        A2aInboundCallContext call = call(
                Set.of("a2a:task:read"),
                Set.of("a2a:task:read"),
                Set.of("task:get"));

        A2aDomainException error = assertThrows(A2aDomainException.class,
                () -> authorizer.requireOperation(call, A2aProtocolOperation.TASKS_GET, null));

        assertEquals("A2A_OPERATION_FORBIDDEN", error.code());
    }

    @Test
    void rejectsOperationWhenPrincipalDoesNotHoldItsRequiredScope() {
        A2aInboundCallContext call = call(
                Set.of("a2a:message:send"),
                Set.of("a2a:message:send", "a2a:task:read"),
                Set.of("tasks:get"));

        A2aDomainException error = assertThrows(A2aDomainException.class,
                () -> authorizer.requireOperation(call, A2aProtocolOperation.TASKS_GET, null));

        assertEquals("A2A_SCOPE_FORBIDDEN", error.code());
    }

    @Test
    void reportsUnsupportedMediaTypeBeforeApplyingPartDataPolicy() {
        A2aInboundCallContext call = call(
                Set.of("a2a:message:send"),
                Set.of("a2a:message:send"),
                Set.of("message:send"), false);
        SendCommand command = command(new PartProfile(
                false, true, false, false, Set.of("application/x-unsupported-tck-type")));

        A2aDomainException error = assertThrows(A2aDomainException.class,
                () -> authorizer.requireSend(call, command));

        assertEquals("A2A_CONTENT_TYPE_NOT_SUPPORTED", error.code());
    }

    @Test
    void appliesPartDataPolicyAfterMediaTypeIsKnownToBeSupported() {
        A2aInboundCallContext call = call(
                Set.of("a2a:message:send"),
                Set.of("a2a:message:send"),
                Set.of("message:send"), false);
        SendCommand command = command(new PartProfile(
                false, true, false, false, Set.of("text/plain")));

        A2aDomainException error = assertThrows(A2aDomainException.class,
                () -> authorizer.requireSend(call, command));

        assertEquals("A2A_FILE_PART_FORBIDDEN", error.code());
    }

    private SendCommand command(PartProfile parts) {
        byte[] canonical = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new SendCommand(null, "msg-1", null, null, canonical, "0".repeat(64),
                canonical.length, "test", "{}", "INTERNAL", parts,
                List.of("text/plain"), 0, true, false);
    }

    private A2aInboundCallContext call(
            Set<String> principalScopes,
            Set<String> trustScopes,
            Set<String> operations) {
        return call(principalScopes, trustScopes, operations, true);
    }

    private A2aInboundCallContext call(
            Set<String> principalScopes,
            Set<String> trustScopes,
            Set<String> operations,
            boolean allowFileParts) {
        PublishedCard publication = new PublishedCard(
                1L, 2L, "support-agent", "agent.example.com", "agent-1", 3L, 10L,
                null, "DEVELOPMENT", List.of("text/plain"), List.of("text/plain"),
                List.of(), "{}", "0".repeat(64),
                LocalDateTime.of(2026, 8, 23, 10, 0));
        A2aPrincipalContext principal = new A2aPrincipalContext(
                7L, "partner-principal", A2aPrincipalType.REMOTE_AGENT, null,
                A2aAuthenticationMethod.API_KEY, A2aTrustLevel.KNOWN,
                principalScopes, null);
        A2aTrustProfile trust = new A2aTrustProfile(
                10L, "partner-default", "Partner default", null, A2aDirection.INBOUND,
                A2aEnvironment.DEVELOPMENT, A2aTrustLevel.KNOWN,
                Set.of(A2aAuthenticationMethod.API_KEY), trustScopes,
                new A2aAuthorizationPolicy(Set.of("support-agent"), Set.of("*"), Set.of("*"),
                        operations, Set.of("*")),
                new A2aDataPolicy(Set.of("INTERNAL"), true, allowFileParts, false, 30),
                A2aDelegatedIdentityPolicy.DENY, A2aPersonalMemoryPolicy.DISABLED,
                60, 10, 1_048_576, 10_485_760, 300_000, false,
                A2aTrustProfileStatus.ACTIVE, 0, "test", "test", null, null);
        return new A2aInboundCallContext(publication, principal, trust);
    }
}
