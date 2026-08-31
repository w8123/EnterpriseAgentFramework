package com.enterprise.ai.control.a2a.application.remoteagent;

import com.enterprise.ai.control.a2a.api.management.A2aHubManagementAccess;
import com.enterprise.ai.control.a2a.application.port.A2aPrincipalRepository;
import com.enterprise.ai.control.a2a.application.port.A2aRemoteAgentRepository;
import com.enterprise.ai.control.a2a.application.port.A2aTrustProfileRepository;
import com.enterprise.ai.control.a2a.domain.A2aAuthenticationMethod;
import com.enterprise.ai.control.a2a.domain.A2aDirection;
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
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class A2aRemoteBindingSnapshotAssemblerTest {

    private static final Set<String> LIFECYCLE_OPERATIONS = Set.of(
            "message:send", "tasks:get", "tasks:cancel");

    @Test
    void createsServerOwnedSnapshotOnlyWhenTheCompleteTaskLifecycleIsAuthorized() {
        A2aRemoteBindingSnapshotAssembler assembler = assembler(
                LIFECYCLE_OPERATIONS, LIFECYCLE_OPERATIONS);

        Map<String, Object> result = assembler.assemble(
                mock(HttpServletRequest.class), draft(), "runtime-agent-1");

        List<?> bindings = (List<?>) result.get("remoteAgents");
        assertEquals(1, bindings.size());
        Map<?, ?> snapshot = (Map<?, ?>) bindings.get(0);
        assertEquals(31L, snapshot.get("remoteAgentRevisionId"));
        assertEquals("remote.fulfillment", snapshot.get("remoteAgentKey"));
        assertEquals("delegateFulfillment", snapshot.get("toolName"));
        assertEquals(List.of("fulfill-order"), snapshot.get("allowedSkillIds"));
        assertEquals(10_000L, snapshot.get("timeoutMs"));
    }

    @Test
    void rejectsBindingWhenEitherTrustProfileOmitsAnyLifecycleOperation() {
        for (String missing : LIFECYCLE_OPERATIONS) {
            for (boolean omitFromPrincipal : List.of(true, false)) {
                Set<String> principalOperations = without(
                        LIFECYCLE_OPERATIONS, omitFromPrincipal ? missing : null);
                Set<String> remoteOperations = without(
                        LIFECYCLE_OPERATIONS, omitFromPrincipal ? null : missing);
                A2aRemoteBindingSnapshotAssembler assembler = assembler(
                        principalOperations, remoteOperations);

                ResponseStatusException failure = assertThrows(
                        ResponseStatusException.class,
                        () -> assembler.assemble(
                                mock(HttpServletRequest.class), draft(), "runtime-agent-1"));

                assertEquals(400, failure.getStatusCode().value());
                assertTrue(failure.getReason().contains("allowlist"));
            }
        }
    }

    private A2aRemoteBindingSnapshotAssembler assembler(
            Set<String> principalOperations,
            Set<String> remoteOperations) {
        A2aHubManagementAccess access = mock(A2aHubManagementAccess.class);
        A2aPrincipalRepository principals = mock(A2aPrincipalRepository.class);
        A2aRemoteAgentRepository remoteAgents = mock(A2aRemoteAgentRepository.class);
        A2aTrustProfileRepository trustProfiles = mock(A2aTrustProfileRepository.class);
        when(principals.findById(11L)).thenReturn(Optional.of(principal()));
        when(remoteAgents.findById(21L)).thenReturn(Optional.of(remoteAgent()));
        when(remoteAgents.findRevision(21L, 31L)).thenReturn(Optional.of(revision()));
        when(trustProfiles.findActiveById(101L))
                .thenReturn(Optional.of(trustProfile(101L, "principal-policy", principalOperations)));
        when(trustProfiles.findActiveById(202L))
                .thenReturn(Optional.of(trustProfile(202L, "remote-policy", remoteOperations)));
        return new A2aRemoteBindingSnapshotAssembler(access, principals, remoteAgents, trustProfiles);
    }

    private A2aPrincipal principal() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 24, 0, 0);
        return new A2aPrincipal(
                11L, "local.runtime-agent-1", A2aPrincipalType.LOCAL_AGENT,
                "Runtime Agent 1", "tenant-a", "runtime-agent-1", 101L, null,
                Set.of("a2a:delegate"), Map.of("runtimeAgentId", "runtime-agent-1"),
                A2aPrincipalStatus.ACTIVE, null, 0, "tester", "tester", now, now);
    }

    private A2aRemoteAgent remoteAgent() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 24, 0, 0);
        return new A2aRemoteAgent(
                21L, "remote.fulfillment", "Remote Fulfillment", "tenant-a",
                "https://remote.example/.well-known/agent-card.json", "0".repeat(64),
                202L, null, 31L, "http-json", null,
                A2aRemoteAgentStatus.TRUSTED, A2aRemoteAgentHealth.HEALTHY, 0,
                now, now, "healthy", 0, "tester", "tester", now, now);
    }

    private A2aRemoteAgentRevision revision() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 24, 0, 0);
        A2aRemoteCardSnapshot card = new A2aRemoteCardSnapshot(
                "Remote Fulfillment", "Fulfills approved orders", "Example", null,
                null, null, "1.0.0",
                List.of(new A2aRemoteInterface(
                        "http-json", "https://remote.example/a2a", "HTTP+JSON", "1.0", null)),
                new A2aRemoteCapabilities(false, false, false),
                List.of("text/plain"), List.of("text/plain"),
                List.of(new A2aRemoteProtocolSkill(
                        "fulfill-order", "Fulfill order", "Fulfills one approved order",
                        List.of("order"), List.of(), List.of("text/plain"), List.of("text/plain"))),
                "{}", "[]", "{}", "1".repeat(64), "UNSIGNED", null);
        return new A2aRemoteAgentRevision(
                31L, 21L, 1, card, null, null, null, null,
                A2aRemoteRevisionReviewStatus.APPROVED, now, "tester", now, now);
    }

    private A2aTrustProfile trustProfile(long id, String key, Set<String> operations) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 24, 0, 0);
        return new A2aTrustProfile(
                id, key, key, null, A2aDirection.OUTBOUND, A2aEnvironment.TEST,
                A2aTrustLevel.TRUSTED, Set.of(A2aAuthenticationMethod.BEARER), Set.of("a2a:delegate"),
                new A2aAuthorizationPolicy(
                        Set.of(), Set.of("remote.fulfillment"), Set.of("fulfill-order"),
                        operations, Set.of("tenant-a")),
                new A2aDataPolicy(Set.of("INTERNAL"), true, false, false, 7),
                A2aDelegatedIdentityPolicy.DENY, A2aPersonalMemoryPolicy.DISABLED,
                60, 8, 1_048_576L, 2_097_152L, 30_000L, false,
                A2aTrustProfileStatus.ACTIVE, 0, "tester", "tester", now, now);
    }

    private Set<String> without(Set<String> values, String omitted) {
        LinkedHashSet<String> result = new LinkedHashSet<>(values);
        if (omitted != null) result.remove(omitted);
        return Set.copyOf(result);
    }

    private Map<String, Object> draft() {
        return Map.of("remoteAgents", List.of(Map.of(
                "principalId", 11L,
                "remoteAgentId", 21L,
                "remoteAgentRevisionId", 31L,
                "toolName", "delegateFulfillment",
                "allowedSkillIds", List.of("fulfill-order"),
                "riskLevel", "WRITE",
                "permissionKey", "orders:fulfill",
                "timeoutMs", 10_000L,
                "enabled", true)));
    }
}
