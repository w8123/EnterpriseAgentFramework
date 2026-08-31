package com.enterprise.ai.control.a2a.application.remoteagent;

import com.enterprise.ai.control.a2a.application.A2aPageView;
import com.enterprise.ai.control.a2a.application.port.A2aRemoteAgentRepository;
import com.enterprise.ai.control.a2a.application.port.A2aRemoteAuthenticationPlanner;
import com.enterprise.ai.control.a2a.application.port.A2aRemoteCardFetcher;
import com.enterprise.ai.control.a2a.application.port.A2aRemoteCardParser;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgentHealth;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static com.enterprise.ai.control.a2a.application.remoteagent.A2aRemoteAgentContracts.DetailView;
import static com.enterprise.ai.control.a2a.application.remoteagent.A2aRemoteAgentContracts.DiscoverRequest;
import static com.enterprise.ai.control.a2a.application.remoteagent.A2aRemoteAgentContracts.DiscoveryResult;
import static com.enterprise.ai.control.a2a.application.remoteagent.A2aRemoteAgentContracts.RemoteAgentView;
import static com.enterprise.ai.control.a2a.application.remoteagent.A2aRemoteAgentContracts.RemoteRevisionView;
import static com.enterprise.ai.control.a2a.application.remoteagent.A2aRemoteAgentContracts.ReviewRequest;

@Service
@RequiredArgsConstructor
public class A2aRemoteAgentApplicationService {

    private static final String PAGE_SCHEMA = "reachai.a2a-hub.remote-agent-page.v1";
    private final A2aRemoteAgentRepository repository;
    private final A2aRemoteAgentRegistry registry;
    private final A2aRemoteAuthenticationPlanner authenticationPlanner;
    private final A2aRemoteCardFetcher cardFetcher;
    private final A2aRemoteCardParser cardParser;

    @Transactional(readOnly = true)
    public A2aPageView<RemoteAgentView> list(
            String search, String status, String health, Integer requestedLimit, Integer requestedOffset) {
        int limit = requestedLimit == null ? 20 : Math.max(1, Math.min(requestedLimit, 100));
        int offset = requestedOffset == null ? 0 : Math.max(0, requestedOffset);
        String normalizedStatus = status == null || status.isBlank()
                ? null : A2aRemoteAgentStatus.parse(status).name();
        String normalizedHealth = health == null || health.isBlank()
                ? null : A2aRemoteAgentHealth.parse(health).name();
        A2aRemoteAgentRepository.Page page = repository.findPage(
                search, normalizedStatus, normalizedHealth, limit, offset);
        return A2aPageView.of(PAGE_SCHEMA, page.items().stream().map(RemoteAgentView::from).toList(),
                page.total(), limit, offset);
    }

    @Transactional(readOnly = true)
    public DetailView detail(long remoteAgentId) {
        var agent = repository.findById(remoteAgentId).orElseThrow(() ->
                new A2aDomainException("A2A_REMOTE_AGENT_NOT_FOUND", "remote Agent was not found"));
        return new DetailView(
                "reachai.a2a-hub.remote-agent-detail.v1",
                RemoteAgentView.from(agent),
                repository.findRevisions(remoteAgentId).stream().map(revision ->
                        RemoteRevisionView.from(revision, authenticationPlanner.assess(
                                revision.card().securitySchemesJson(),
                                revision.card().securityRequirementsJson()))).toList());
    }

    public DiscoveryResult discover(DiscoverRequest request, String actor) {
        if (request == null) {
            throw new A2aDomainException("A2A_REQUEST_REQUIRED", "request body is required");
        }
        URI normalized = normalize(request.agentCardUrl());
        var lease = registry.beginDiscovery(
                request.remoteAgentKey(), request.displayName(), request.tenantScope(),
                normalized.toASCIIString(), sha256(normalized.toASCIIString()), actor);
        return verify(lease);
    }

    public DiscoveryResult rediscover(long remoteAgentId, String actor) {
        return verify(registry.beginRediscovery(remoteAgentId, actor));
    }

    public DetailView approve(
            long remoteAgentId, long revisionId, ReviewRequest request, String actor) {
        if (request == null || request.trustProfileId() == null) {
            throw new A2aDomainException("A2A_REMOTE_REVIEW_INVALID",
                    "preferredInterfaceKey and trustProfileId are required");
        }
        registry.approve(remoteAgentId, revisionId, request.preferredInterfaceKey(),
                request.preferredSecuritySchemeKey(), request.trustProfileId(),
                request.credentialId(), actor);
        return detail(remoteAgentId);
    }

    public DetailView reject(long remoteAgentId, long revisionId, String reason, String actor) {
        registry.reject(remoteAgentId, revisionId, reason, actor);
        return detail(remoteAgentId);
    }

    public DetailView disable(long remoteAgentId, String actor) {
        registry.disable(remoteAgentId, actor);
        return detail(remoteAgentId);
    }

    public DetailView enable(long remoteAgentId, String actor) {
        registry.enable(remoteAgentId, actor);
        return detail(remoteAgentId);
    }

    private DiscoveryResult verify(A2aRemoteAgentRegistry.VerificationLease lease) {
        A2aRemoteCardFetcher.FetchResult fetched;
        com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteCardSnapshot parsed;
        try {
            fetched = cardFetcher.fetch(URI.create(lease.cardUrl()));
            parsed = cardParser.parse(fetched.body());
        } catch (A2aDomainException failure) {
            return discoveryResult(registry.verificationFailed(lease, failure.code()));
        } catch (RuntimeException failure) {
            return discoveryResult(registry.verificationFailed(
                    lease, "A2A_REMOTE_VERIFICATION_FAILED"));
        }
        return discoveryResult(registry.completeVerification(
                lease, parsed, fetched.tlsIdentitySha256(), fetched.networkEvidenceJson(),
                fetched.httpEtag(), fetched.httpLastModified()));
    }

    private DiscoveryResult discoveryResult(A2aRemoteAgentRegistry.Completion completion) {
        return new DiscoveryResult(
                "reachai.a2a-hub.remote-agent-discovery.v1", completion.outcome(),
                completion.reasonCode(), detail(completion.remoteAgent().id()));
    }

    private URI normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new A2aDomainException("A2A_REMOTE_CARD_URL_REQUIRED",
                    "agentCardUrl is required");
        }
        try {
            return cardFetcher.normalize(URI.create(value.trim()));
        } catch (A2aDomainException known) {
            throw known;
        } catch (IllegalArgumentException failure) {
            throw new A2aDomainException("A2A_REMOTE_CARD_URL_INVALID",
                    "agentCardUrl is invalid");
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
