package com.enterprise.ai.control.a2a.application.remoteagent;

import com.enterprise.ai.control.a2a.application.port.A2aCredentialRepository;
import com.enterprise.ai.control.a2a.application.port.A2aRemoteAuthenticationPlanner;
import com.enterprise.ai.control.a2a.application.port.A2aRemoteAgentRepository;
import com.enterprise.ai.control.a2a.application.port.A2aTrustProfileRepository;
import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgent;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgentHealth;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgentRevision;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgentStatus;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteCapabilities;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteCardSnapshot;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteInterface;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteRevisionReviewStatus;
import com.enterprise.ai.control.a2a.domain.trust.A2aTrustProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class A2aRemoteAgentRegistryTest {

    private final A2aRemoteAgentRepository repository = mock(A2aRemoteAgentRepository.class);
    private final A2aTrustProfileRepository trusts = mock(A2aTrustProfileRepository.class);
    private final A2aCredentialRepository credentials = mock(A2aCredentialRepository.class);
    private final A2aRemoteAuthenticationPlanner authenticationPlanner =
            mock(A2aRemoteAuthenticationPlanner.class);
    private A2aRemoteAgentRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new A2aRemoteAgentRegistry(repository, trusts, credentials, authenticationPlanner,
                Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void createsAPendingImmutableRevisionWhenVerifiedCardChanges() {
        A2aRemoteAgent verifying = agent(A2aRemoteAgentStatus.VERIFYING, null, 3);
        when(repository.findById(11L)).thenReturn(Optional.of(verifying));
        when(repository.findLatestRevision(11L)).thenReturn(Optional.empty());
        when(repository.nextRevisionNo(11L)).thenReturn(1);
        when(repository.saveRevision(any())).thenAnswer(invocation -> {
            A2aRemoteAgentRevision value = invocation.getArgument(0);
            return new A2aRemoteAgentRevision(
                    21L, value.remoteAgentId(), value.revisionNo(), value.card(),
                    value.tlsIdentitySha256(), value.networkEvidenceJson(), value.httpEtag(),
                    value.httpLastModified(), value.reviewStatus(), value.discoveredAt(),
                    null, null, LocalDateTime.of(2026, 8, 24, 0, 0));
        });
        when(repository.save(any(A2aRemoteAgent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = registry.completeVerification(
                new A2aRemoteAgentRegistry.VerificationLease(
                        11L, A2aRemoteAgentStatus.DISCOVERED, verifying.cardUrl(), "operator"),
                card("a".repeat(64)), "b".repeat(64), "{}", null, null);

        assertEquals("REVIEW_REQUIRED", result.outcome());
        assertEquals(A2aRemoteRevisionReviewStatus.PENDING, result.revision().reviewStatus());
        assertEquals(A2aRemoteAgentStatus.REVIEW_REQUIRED, result.remoteAgent().status());
        verify(repository).supersedePendingRevisions(11L, null);
    }

    @Test
    void doesNotAutoTrustARejectedUnchangedHash() {
        A2aRemoteAgent verifying = agent(A2aRemoteAgentStatus.VERIFYING, 20L, 4);
        A2aRemoteAgentRevision rejected = revision(20L, "a".repeat(64),
                A2aRemoteRevisionReviewStatus.REJECTED);
        when(repository.findById(11L)).thenReturn(Optional.of(verifying));
        when(repository.findLatestRevision(11L)).thenReturn(Optional.of(rejected));
        when(repository.nextRevisionNo(11L)).thenReturn(2);
        when(repository.saveRevision(any())).thenAnswer(invocation -> {
            A2aRemoteAgentRevision value = invocation.getArgument(0);
            return new A2aRemoteAgentRevision(
                    22L, value.remoteAgentId(), value.revisionNo(), value.card(),
                    value.tlsIdentitySha256(), value.networkEvidenceJson(), null, null,
                    value.reviewStatus(), value.discoveredAt(), null, null,
                    LocalDateTime.of(2026, 8, 24, 0, 0));
        });
        when(repository.save(any(A2aRemoteAgent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = registry.completeVerification(
                new A2aRemoteAgentRegistry.VerificationLease(
                        11L, A2aRemoteAgentStatus.QUARANTINED, verifying.cardUrl(), "operator"),
                card("a".repeat(64)), "b".repeat(64), "{}", null, null);

        assertEquals("REVIEW_REQUIRED", result.outcome());
        assertEquals(22L, result.revision().id());
    }

    @Test
    void requiresAnActiveOutboundTrustProfile() {
        A2aRemoteAgent reviewing = agent(A2aRemoteAgentStatus.REVIEW_REQUIRED, null, 2);
        when(repository.findById(11L)).thenReturn(Optional.of(reviewing));

        A2aTrustProfile inbound = mock(A2aTrustProfile.class);
        when(inbound.direction()).thenReturn(A2aDirection.INBOUND);
        when(trusts.findActiveById(31L)).thenReturn(Optional.of(inbound));
        A2aDomainException directionFailure = assertThrows(A2aDomainException.class,
                () -> registry.approve(11L, 21L, "iface", null,
                        31L, null, "operator"));
        assertEquals("A2A_TRUST_PROFILE_DIRECTION_INVALID", directionFailure.code());
        verify(authenticationPlanner, never()).requireBinding(any(), any(), any(), any(), any());
    }

    private A2aRemoteAgent agent(A2aRemoteAgentStatus status, Long revisionId, int version) {
        return new A2aRemoteAgent(
                11L, "remote-reviewer", "Remote Reviewer", "tenant-a",
                "https://8.8.8.8/agent-card.json", "c".repeat(64),
                revisionId == null ? null : 31L, null, revisionId,
                revisionId == null ? null : "iface", null,
                status, A2aRemoteAgentHealth.UNKNOWN,
                0, null, null, null, version, "creator", "operator",
                LocalDateTime.of(2026, 8, 23, 0, 0), LocalDateTime.of(2026, 8, 23, 0, 0));
    }

    private A2aRemoteAgentRevision revision(
            long id, String hash, A2aRemoteRevisionReviewStatus status) {
        return new A2aRemoteAgentRevision(
                id, 11L, 1, card(hash), "b".repeat(64), "{}", null, null,
                status, LocalDateTime.of(2026, 8, 23, 0, 0), null, null,
                LocalDateTime.of(2026, 8, 23, 0, 0));
    }

    private A2aRemoteCardSnapshot card(String hash) {
        return new A2aRemoteCardSnapshot(
                "Remote Reviewer", "Reviews requests", null, null, null, null, "1.0.0",
                List.of(new A2aRemoteInterface("iface", "https://8.8.8.8/a2a",
                        "HTTP+JSON", "1.0", null)),
                new A2aRemoteCapabilities(false, false, false),
                List.of("text/plain"), List.of("text/plain"), List.of(),
                null, null, "{}", hash, "NOT_PRESENT", null);
    }
}
