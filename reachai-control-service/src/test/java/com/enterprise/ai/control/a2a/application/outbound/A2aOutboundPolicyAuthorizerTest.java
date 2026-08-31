package com.enterprise.ai.control.a2a.application.outbound;

import com.enterprise.ai.control.a2a.application.port.A2aRemoteAuthenticationPlanner;
import com.enterprise.ai.control.a2a.domain.A2aAuthenticationMethod;
import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.A2aPrincipalType;
import com.enterprise.ai.control.a2a.domain.A2aTrustLevel;
import com.enterprise.ai.control.a2a.domain.identity.A2aPrincipal;
import com.enterprise.ai.control.a2a.domain.identity.A2aPrincipalStatus;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgent;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgentHealth;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgentRevision;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgentStatus;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteCapabilities;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteCardSnapshot;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteInterface;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteProtocolSkill;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteRevisionReviewStatus;
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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class A2aOutboundPolicyAuthorizerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 24, 1, 0);
    private final A2aOutboundPolicyAuthorizer authorizer = new A2aOutboundPolicyAuthorizer();
    private final A2aRemoteInterface remoteInterface = new A2aRemoteInterface(
            "http-json", "https://agent.example/a2a", "HTTP+JSON", "1.0", "tenant-a");

    @Test
    void taskOperationRequiresBothTrustProfilesToAllowTheExactOperation() {
        A2aDomainException failure = assertThrows(A2aDomainException.class,
                () -> authorizer.authorizeTaskOperation(
                        "tasks:cancel", principal(), trust(11L, Set.of("tasks:get")),
                        remoteAgent(), revision(), remoteInterface,
                        trust(12L, Set.of("tasks:get", "tasks:cancel")),
                        A2aRemoteAuthenticationPlanner.Binding.anonymous(), accepted(), NOW));

        assertEquals("A2A_OUTBOUND_OPERATION_FORBIDDEN", failure.code());
    }

    @Test
    void acceptedTaskSnapshotCapsCurrentPolicyAndPreventsAuthorityExpansion() {
        var policy = authorizer.authorizeTaskOperation(
                "tasks:get", principal(), trust(11L, Set.of("tasks:get", "tasks:cancel")),
                remoteAgent(), revision(), remoteInterface,
                trust(12L, Set.of("tasks:get", "tasks:cancel")),
                A2aRemoteAuthenticationPlanner.Binding.anonymous(), accepted(), NOW);

        assertEquals(List.of("text/plain"), policy.acceptedOutputModes());
        assertEquals(10_000L, policy.timeoutMs());
        assertEquals(1_000L, policy.maxRequestBytes());
        assertEquals(1_500, policy.maxResponseBytes());
        assertEquals(2_000L, policy.maxArtifactBytes());
    }

    private A2aOutboundPolicyAuthorizer.AcceptedPolicy accepted() {
        return new A2aOutboundPolicyAuthorizer.AcceptedPolicy(
                "review", "INTERNAL", List.of("text/plain"),
                10_000L, 1_000L, 1_500, 2_000L);
    }

    private A2aPrincipal principal() {
        return new A2aPrincipal(
                21L, "local.agent", A2aPrincipalType.LOCAL_AGENT, "Local Agent",
                "tenant-a", "runtime-agent-a", 11L, null, Set.of(),
                Map.of("runtimeAgentId", "agent-a"), A2aPrincipalStatus.ACTIVE,
                null, 1, "creator", "creator", NOW, NOW);
    }

    private A2aTrustProfile trust(long id, Set<String> operations) {
        return new A2aTrustProfile(
                id, "outbound-" + id, "Outbound " + id, null,
                A2aDirection.OUTBOUND, A2aEnvironment.DEVELOPMENT, A2aTrustLevel.KNOWN,
                Set.of(A2aAuthenticationMethod.ANONYMOUS), Set.of(),
                new A2aAuthorizationPolicy(
                        Set.of(), Set.of("remote-reviewer"), Set.of("review"),
                        operations, Set.of("tenant-a")),
                new A2aDataPolicy(Set.of("INTERNAL"), true, false, false, 7),
                A2aDelegatedIdentityPolicy.DENY, A2aPersonalMemoryPolicy.DISABLED,
                60, 10, 4_000L, 8_000L, 60_000L, true,
                A2aTrustProfileStatus.ACTIVE, 1, "creator", "creator", NOW, NOW);
    }

    private A2aRemoteAgent remoteAgent() {
        return new A2aRemoteAgent(
                31L, "remote-reviewer", "Remote Reviewer", "tenant-a",
                "https://agent.example/.well-known/agent-card.json", "a".repeat(64),
                12L, null, 32L, "http-json", null,
                A2aRemoteAgentStatus.TRUSTED, A2aRemoteAgentHealth.HEALTHY,
                0, NOW, NOW, "healthy", 1, "creator", "creator", NOW, NOW);
    }

    private A2aRemoteAgentRevision revision() {
        A2aRemoteCardSnapshot card = new A2aRemoteCardSnapshot(
                "Remote Reviewer", "Reviews changes", null, null, null, null, "1.0.0",
                List.of(remoteInterface), new A2aRemoteCapabilities(false, false, false),
                List.of("text/plain"), List.of("text/plain", "application/json"),
                List.of(new A2aRemoteProtocolSkill(
                        "review", "Review", "Review a change", List.of(), List.of(),
                        List.of("text/plain"), List.of("text/plain", "application/json"))),
                null, null, "{}", "b".repeat(64), "NOT_PRESENT", null);
        return new A2aRemoteAgentRevision(
                32L, 31L, 1, card, "c".repeat(64), "{}", null, null,
                A2aRemoteRevisionReviewStatus.APPROVED, NOW, "reviewer", NOW, NOW);
    }
}
