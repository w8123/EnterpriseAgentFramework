package com.enterprise.ai.control.a2a.application.remoteagent;

import com.enterprise.ai.control.a2a.application.port.A2aCredentialRepository;
import com.enterprise.ai.control.a2a.application.port.A2aRemoteAuthenticationPlanner;
import com.enterprise.ai.control.a2a.application.port.A2aRemoteAgentRepository;
import com.enterprise.ai.control.a2a.application.port.A2aTrustProfileRepository;
import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.A2aAuthenticationMethod;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgent;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgentRevision;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgentStatus;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteCardSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class A2aRemoteAgentRegistry {

    private final A2aRemoteAgentRepository repository;
    private final A2aTrustProfileRepository trustProfileRepository;
    private final A2aCredentialRepository credentialRepository;
    private final A2aRemoteAuthenticationPlanner authenticationPlanner;
    private final Clock clock;

    @Transactional
    public VerificationLease beginDiscovery(
            String remoteAgentKey,
            String displayName,
            String tenantScope,
            String normalizedCardUrl,
            String cardUrlSha256,
            String actor) {
        if (repository.existsByKey(remoteAgentKey)) {
            throw new A2aDomainException("A2A_REMOTE_AGENT_KEY_CONFLICT",
                    "remoteAgentKey is already registered");
        }
        if (repository.existsByCardUrlHash(tenantScope, cardUrlSha256)) {
            throw new A2aDomainException("A2A_REMOTE_AGENT_URL_CONFLICT",
                    "the Agent Card URL is already registered in this tenant scope");
        }
        A2aRemoteAgent saved = repository.save(A2aRemoteAgent.discovered(
                remoteAgentKey, displayName, tenantScope, normalizedCardUrl, cardUrlSha256, actor));
        return new VerificationLease(saved.id(), A2aRemoteAgentStatus.DISCOVERED,
                saved.cardUrl(), actor);
    }

    @Transactional
    public VerificationLease beginRediscovery(long remoteAgentId, String actor) {
        A2aRemoteAgent existing = requireAgent(remoteAgentId);
        A2aRemoteAgentStatus previousStatus = existing.status();
        A2aRemoteAgent verifying = repository.save(existing.startVerification(actor));
        return new VerificationLease(verifying.id(), previousStatus, verifying.cardUrl(), actor);
    }

    @Transactional
    public Completion completeVerification(
            VerificationLease lease,
            A2aRemoteCardSnapshot card,
            String tlsIdentitySha256,
            String networkEvidenceJson,
            String httpEtag,
            String httpLastModified) {
        A2aRemoteAgent agent = requireVerifying(lease.remoteAgentId());
        LocalDateTime now = LocalDateTime.now(clock);
        var latest = repository.findLatestRevision(agent.id());
        if (latest.isPresent()
                && latest.get().card().agentCardSha256().equals(card.agentCardSha256())
                && (latest.get().reviewStatus()
                        == com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteRevisionReviewStatus.PENDING
                    || latest.get().reviewStatus()
                        == com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteRevisionReviewStatus.APPROVED)) {
            A2aRemoteAgent unchanged = repository.save(agent.verifiedUnchanged(
                    now, "Agent Card verified; content hash is unchanged",
                    lease.statusBeforeVerification(), latest.get().reviewStatus(), lease.actor()));
            return new Completion("UNCHANGED", null, unchanged, latest.get());
        }
        repository.supersedePendingRevisions(agent.id(), null);
        A2aRemoteAgentRevision revision = repository.saveRevision(new A2aRemoteAgentRevision(
                null, agent.id(), repository.nextRevisionNo(agent.id()), card,
                tlsIdentitySha256, networkEvidenceJson, httpEtag, httpLastModified,
                com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteRevisionReviewStatus.PENDING,
                now, null, null, null));
        A2aRemoteAgent review = repository.save(agent.reviewRequired(
                now, "New Agent Card revision requires administrator review", lease.actor()));
        return new Completion("REVIEW_REQUIRED", null, review, revision);
    }

    @Transactional
    public Completion verificationFailed(VerificationLease lease, String reasonCode) {
        A2aRemoteAgent agent = requireAgent(lease.remoteAgentId());
        if (agent.status() != A2aRemoteAgentStatus.VERIFYING) {
            throw new A2aDomainException("A2A_REMOTE_VERIFICATION_CONFLICT",
                    "remote Agent verification state changed concurrently");
        }
        String normalizedReason = safeReasonCode(reasonCode);
        A2aRemoteAgent failed = repository.save(agent.verificationFailed(
                LocalDateTime.now(clock), "Agent Card verification failed: " + normalizedReason,
                lease.actor()));
        return new Completion("QUARANTINED", normalizedReason, failed, null);
    }

    @Transactional
    public A2aRemoteAgent approve(
            long remoteAgentId,
            long revisionId,
            String preferredInterfaceKey,
            String preferredSecuritySchemeKey,
            long trustProfileId,
            Long credentialId,
            String actor) {
        var profile = trustProfileRepository.findActiveById(trustProfileId)
                .orElseThrow(() -> new A2aDomainException("A2A_TRUST_PROFILE_NOT_FOUND",
                        "an active trust profile was not found"));
        if (!profile.direction().accepts(A2aDirection.OUTBOUND)) {
            throw new A2aDomainException("A2A_TRUST_PROFILE_DIRECTION_INVALID",
                    "the trust profile must allow OUTBOUND A2A traffic");
        }
        A2aRemoteAgent agent = requireAgent(remoteAgentId);
        if (agent.status() != A2aRemoteAgentStatus.REVIEW_REQUIRED) {
            throw new A2aDomainException("A2A_REMOTE_REVIEW_REQUIRED",
                    "the remote Agent must be awaiting review");
        }
        A2aRemoteAgentRevision revision = requireRevision(remoteAgentId, revisionId);
        revision.requireInterface(preferredInterfaceKey);
        LocalDateTime now = LocalDateTime.now(clock);
        var credential = credentialId == null ? null : credentialRepository.findById(credentialId)
                .orElseThrow(() -> new A2aDomainException("A2A_OUTBOUND_CREDENTIAL_NOT_FOUND",
                        "the selected outbound credential was not found"));
        A2aRemoteAuthenticationPlanner.Binding authentication =
                authenticationPlanner.requireBinding(
                        revision.card().securitySchemesJson(),
                        revision.card().securityRequirementsJson(),
                        preferredSecuritySchemeKey,
                        credential,
                        now);
        A2aAuthenticationMethod authenticationMethod = authentication.authenticated()
                ? A2aAuthenticationMethod.valueOf(authentication.credentialType().name())
                : A2aAuthenticationMethod.ANONYMOUS;
        if (!profile.authenticationMethods().contains(authenticationMethod)) {
            throw new A2aDomainException("A2A_REMOTE_AUTH_NOT_ALLOWED_BY_TRUST",
                    "the selected remote authentication method is not allowed by the Trust Profile");
        }
        var remoteAgentKeys = profile.authorizationPolicy().remoteAgentKeys();
        if (!(remoteAgentKeys.contains("*") || remoteAgentKeys.contains(agent.remoteAgentKey()))) {
            throw new A2aDomainException("A2A_REMOTE_AGENT_NOT_ALLOWED_BY_TRUST",
                    "the remote Agent is not allowed by the Trust Profile");
        }
        A2aRemoteAgentRevision approved = repository.saveRevision(revision.approve(actor, now));
        repository.supersedeApprovedRevisions(remoteAgentId, approved.id());
        return repository.save(agent.trust(
                approved.id(), preferredInterfaceKey, authentication.securitySchemeKey(),
                trustProfileId, authentication.credentialId(), now, actor));
    }

    @Transactional
    public A2aRemoteAgent reject(
            long remoteAgentId,
            long revisionId,
            String reason,
            String actor) {
        A2aRemoteAgent agent = requireAgent(remoteAgentId);
        if (agent.status() != A2aRemoteAgentStatus.REVIEW_REQUIRED) {
            throw new A2aDomainException("A2A_REMOTE_REVIEW_REQUIRED",
                    "the remote Agent must be awaiting review");
        }
        A2aRemoteAgentRevision revision = requireRevision(remoteAgentId, revisionId);
        LocalDateTime now = LocalDateTime.now(clock);
        repository.saveRevision(revision.reject(actor, now));
        String safeReason = reason == null || reason.isBlank()
                ? "Remote Agent revision rejected by administrator"
                : "Remote Agent revision rejected: " + reason.trim();
        if (safeReason.length() > 1000) safeReason = safeReason.substring(0, 1000);
        return repository.save(agent.revisionRejected(now, safeReason, actor));
    }

    @Transactional
    public A2aRemoteAgent disable(long remoteAgentId, String actor) {
        return repository.save(requireAgent(remoteAgentId).disable(actor));
    }

    @Transactional
    public A2aRemoteAgent enable(long remoteAgentId, String actor) {
        return repository.save(requireAgent(remoteAgentId).enable(actor));
    }

    private A2aRemoteAgent requireVerifying(long id) {
        A2aRemoteAgent agent = requireAgent(id);
        if (agent.status() != A2aRemoteAgentStatus.VERIFYING) {
            throw new A2aDomainException("A2A_REMOTE_VERIFICATION_CONFLICT",
                    "remote Agent verification state changed concurrently");
        }
        return agent;
    }

    private A2aRemoteAgent requireAgent(long id) {
        return repository.findById(id).orElseThrow(() ->
                new A2aDomainException("A2A_REMOTE_AGENT_NOT_FOUND",
                        "remote Agent was not found"));
    }

    private A2aRemoteAgentRevision requireRevision(long remoteAgentId, long revisionId) {
        return repository.findRevision(remoteAgentId, revisionId).orElseThrow(() ->
                new A2aDomainException("A2A_REMOTE_REVISION_NOT_FOUND",
                        "remote Agent revision was not found"));
    }

    private String safeReasonCode(String value) {
        if (value == null || !value.matches("[A-Z0-9_]{3,96}")) {
            return "A2A_REMOTE_VERIFICATION_FAILED";
        }
        return value;
    }

    public record VerificationLease(
            long remoteAgentId,
            A2aRemoteAgentStatus statusBeforeVerification,
            String cardUrl,
            String actor) {
    }

    public record Completion(
            String outcome,
            String reasonCode,
            A2aRemoteAgent remoteAgent,
            A2aRemoteAgentRevision revision) {
    }
}
