package com.enterprise.ai.control.a2a.application.publication;

import com.enterprise.ai.control.a2a.application.A2aPageView;
import com.enterprise.ai.control.a2a.application.port.A2aAgentCardGenerator;
import com.enterprise.ai.control.a2a.application.port.A2aLocalAgentCatalog;
import com.enterprise.ai.control.a2a.application.port.A2aPublicationRepository;
import com.enterprise.ai.control.a2a.application.port.A2aTrustProfileRepository;
import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.publication.A2aAgentCardSnapshot;
import com.enterprise.ai.control.a2a.domain.publication.A2aProtocolSkill;
import com.enterprise.ai.control.a2a.domain.publication.A2aPublication;
import com.enterprise.ai.control.a2a.domain.publication.A2aPublicationAddress;
import com.enterprise.ai.control.a2a.domain.publication.A2aPublicationRevision;
import com.enterprise.ai.control.a2a.domain.publication.A2aPublicationRevisionStatus;
import com.enterprise.ai.control.a2a.domain.publication.A2aPublicationStatus;
import com.enterprise.ai.control.a2a.domain.trust.A2aEnvironment;
import com.enterprise.ai.control.a2a.domain.trust.A2aTrustProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static com.enterprise.ai.control.a2a.application.publication.A2aPublicationContracts.CreateRequest;
import static com.enterprise.ai.control.a2a.application.publication.A2aPublicationContracts.DetailView;
import static com.enterprise.ai.control.a2a.application.publication.A2aPublicationContracts.ProtocolSkillRequest;
import static com.enterprise.ai.control.a2a.application.publication.A2aPublicationContracts.PublicationView;
import static com.enterprise.ai.control.a2a.application.publication.A2aPublicationContracts.RevisionDraftRequest;
import static com.enterprise.ai.control.a2a.application.publication.A2aPublicationContracts.RevisionView;

@Service
@RequiredArgsConstructor
public class A2aPublicationApplicationService {

    private static final String PAGE_SCHEMA = "reachai.a2a-hub.publication-page.v1";
    private final A2aPublicationRepository repository;
    private final A2aTrustProfileRepository trustProfileRepository;
    private final A2aLocalAgentCatalog localAgentCatalog;
    private final A2aAgentCardGenerator cardGenerator;
    private final A2aPublishedCapabilityPolicy capabilityPolicy;
    private final Clock clock;

    @Transactional(readOnly = true)
    public A2aPageView<PublicationView> list(
            String search, String requestedStatus, Integer requestedLimit, Integer requestedOffset) {
        int limit = requestedLimit == null ? 20 : Math.max(1, Math.min(requestedLimit, 100));
        int offset = requestedOffset == null ? 0 : Math.max(0, requestedOffset);
        String status = requestedStatus == null || requestedStatus.isBlank()
                ? null : A2aPublicationStatus.parse(requestedStatus).name();
        A2aPublicationRepository.Page page = repository.findPage(search, status, limit, offset);
        return A2aPageView.of(PAGE_SCHEMA,
                page.items().stream().map(PublicationView::from).toList(),
                page.total(), limit, offset);
    }

    @Transactional(readOnly = true)
    public DetailView detail(long publicationId) {
        A2aPublication publication = requirePublication(publicationId);
        return detail(publication);
    }

    @Transactional
    public DetailView create(CreateRequest request, String actor) {
        if (request == null || request.revision() == null) {
            throw new A2aDomainException("A2A_PUBLICATION_REQUEST_REQUIRED",
                    "publication and initial revision are required");
        }
        A2aEnvironment environment = A2aEnvironment.parse(request.environment());
        A2aPublicationAddress address = A2aPublicationAddress.of(
                request.revision().publicOrigin(), request.publicHost(), environment);
        A2aTrustProfile trustProfile = requireApplicableTrust(
                request.trustProfileId(), environment, request.publicationKey(),
                request.tenantScope(), request.revision());
        A2aLocalAgentCatalog.PublishedAgent agent = requireAgent(
                request.agentId(), request.revision().agentConfigVersionId());
        if (repository.existsByPublicationKey(request.publicationKey())) {
            throw new A2aDomainException("A2A_PUBLICATION_KEY_CONFLICT",
                    "publicationKey is already in use");
        }
        String tenantScope = trimToEmpty(request.tenantScope());
        if (repository.existsByAgentScope(agent.agentId(), environment.name(), tenantScope)) {
            throw new A2aDomainException("A2A_PUBLICATION_AGENT_SCOPE_CONFLICT",
                    "this Agent already has a publication in the requested environment and tenant scope");
        }
        A2aPublication publication = repository.save(new A2aPublication(
                null, request.publicationKey(), agent.agentId(), agent.projectId(), agent.projectCode(),
                environment, tenantScope, address.publicHost(), request.trustProfileId(), null,
                A2aPublicationStatus.DRAFT, 0, null, null, actor, actor, null, null));
        A2aPublicationRevision revision = buildRevision(
                publication, request.revision(), trustProfile, agent, 1, actor, address);
        repository.saveRevision(revision);
        return detail(repository.findById(publication.id()).orElseThrow());
    }

    @Transactional
    public RevisionView createRevision(long publicationId, RevisionDraftRequest request, String actor) {
        A2aPublication publication = requirePublication(publicationId);
        if (publication.status() == A2aPublicationStatus.ARCHIVED) {
            throw new A2aDomainException("A2A_PUBLICATION_ARCHIVED",
                    "an archived publication cannot receive a new revision");
        }
        A2aPublicationAddress address = A2aPublicationAddress.of(
                request == null ? null : request.publicOrigin(),
                publication.publicHost(), publication.environment());
        A2aTrustProfile trustProfile = requireApplicableTrust(
                publication.trustProfileId(), publication.environment(), publication.publicationKey(),
                publication.tenantScope(), request);
        A2aLocalAgentCatalog.PublishedAgent agent = requireAgent(
                publication.agentId(), request == null ? null : request.agentConfigVersionId());
        int nextRevision = repository.nextRevisionNo(publicationId);
        return RevisionView.from(repository.saveRevision(buildRevision(
                publication, request, trustProfile, agent, nextRevision, actor, address)));
    }

    @Transactional
    public DetailView preflight(long publicationId, long revisionId, String actor) {
        A2aPublication publication = requirePublication(publicationId);
        A2aPublicationRevision revision = requireRevision(publicationId, revisionId);
        A2aTrustProfile trust = requireApplicableTrust(
                publication.trustProfileId(), publication.environment(), publication.publicationKey(),
                publication.tenantScope(), revision);
        requireAgent(publication.agentId(), revision.agentConfigVersionId());
        A2aAgentCardSnapshot regenerated = cardGenerator.generate(source(revision), trust);
        repository.saveRevision(revision.markReady(regenerated));
        if (publication.status() != A2aPublicationStatus.PUBLISHED
                && publication.status() != A2aPublicationStatus.SUSPENDED) {
            repository.save(publication.markReady(actor));
        }
        return detail(requirePublication(publicationId));
    }

    @Transactional
    public DetailView publish(long publicationId, long revisionId, String actor) {
        A2aPublication publication = requirePublication(publicationId);
        A2aPublicationRevision revision = requireRevision(publicationId, revisionId);
        requireApplicableTrust(publication.trustProfileId(), publication.environment(),
                publication.publicationKey(), publication.tenantScope(), revision);
        if (publication.environment().isProduction()
                && !"PASSED".equalsIgnoreCase(revision.conformanceStatus())) {
            throw new A2aDomainException("A2A_PRODUCTION_CONFORMANCE_REQUIRED",
                    "production publication requires a passing A2A conformance run");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        A2aPublicationRevision publishedRevision = repository.saveRevision(revision.markPublished(now));
        repository.save(publication.publish(publishedRevision.id(), now, actor));
        return detail(requirePublication(publicationId));
    }

    @Transactional
    public DetailView suspend(long publicationId, String actor) {
        A2aPublication publication = requirePublication(publicationId);
        repository.save(publication.suspend(LocalDateTime.now(clock), actor));
        return detail(requirePublication(publicationId));
    }

    @Transactional
    public DetailView resume(long publicationId, String actor) {
        A2aPublication publication = requirePublication(publicationId);
        requireApplicableTrust(publication.trustProfileId(), publication.environment(),
                publication.publicationKey(), publication.tenantScope(),
                requireRevision(publicationId, publication.currentRevisionId()));
        repository.save(publication.resume(actor));
        return detail(requirePublication(publicationId));
    }

    private A2aPublicationRevision buildRevision(
            A2aPublication publication,
            RevisionDraftRequest request,
            A2aTrustProfile trustProfile,
            A2aLocalAgentCatalog.PublishedAgent agent,
            int revisionNo,
            String actor,
            A2aPublicationAddress address) {
        if (request == null) {
            throw new A2aDomainException("A2A_REVISION_REQUEST_REQUIRED", "revision is required");
        }
        capabilityPolicy.validate(request);
        List<A2aProtocolSkill> skills = protocolSkills(request.protocolSkills());
        List<String> inputModes = copy(request.defaultInputModes());
        List<String> outputModes = copy(request.defaultOutputModes());
        String name = firstText(request.name(), agent.name());
        String description = firstText(request.description(), agent.description());
        A2aAgentCardGenerator.Source source = new A2aAgentCardGenerator.Source(
                name, description, firstText(request.agentVersion(), "config-" + agent.configVersionNo()),
                trimToNull(request.providerOrganization()), trimToNull(request.providerUrl()),
                trimToNull(request.documentationUrl()), trimToNull(request.iconUrl()),
                address.publicOrigin(), "/a2a/v1",
                Boolean.TRUE.equals(request.streamingSupported()),
                Boolean.TRUE.equals(request.pushNotificationsSupported()),
                Boolean.TRUE.equals(request.extendedCardSupported()),
                inputModes, outputModes, skills);
        A2aAgentCardSnapshot card = cardGenerator.generate(source, trustProfile);
        return new A2aPublicationRevision(
                null, publication.id(), revisionNo, agent.configVersionId(), source.agentVersion(),
                source.name(), source.description(), source.providerOrganization(), source.providerUrl(),
                source.documentationUrl(), source.iconUrl(), source.publicOrigin(), source.protocolBasePath(),
                "HTTP+JSON", "1.0", source.streamingSupported(),
                source.pushNotificationsSupported(), source.extendedCardSupported(),
                inputModes, outputModes, skills, card, "NOT_CONFIGURED", "NOT_RUN",
                A2aPublicationRevisionStatus.DRAFT, actor, null, null);
    }

    private A2aTrustProfile requireApplicableTrust(
            Long trustProfileId,
            A2aEnvironment environment,
            String publicationKey,
            String tenantScope,
            RevisionDraftRequest revision) {
        if (revision == null) {
            throw new A2aDomainException("A2A_REVISION_REQUEST_REQUIRED", "revision is required");
        }
        return requireApplicableTrust(trustProfileId, environment, publicationKey, tenantScope,
                protocolSkills(revision.protocolSkills()));
    }

    private A2aTrustProfile requireApplicableTrust(
            Long trustProfileId,
            A2aEnvironment environment,
            String publicationKey,
            String tenantScope,
            A2aPublicationRevision revision) {
        return requireApplicableTrust(trustProfileId, environment, publicationKey, tenantScope,
                revision.protocolSkills());
    }

    private A2aTrustProfile requireApplicableTrust(
            Long trustProfileId,
            A2aEnvironment environment,
            String publicationKey,
            String tenantScope,
            List<A2aProtocolSkill> skills) {
        if (trustProfileId == null || trustProfileId <= 0) {
            throw new A2aDomainException("A2A_TRUST_PROFILE_REQUIRED", "trustProfileId is required");
        }
        A2aTrustProfile profile = trustProfileRepository.findActiveById(trustProfileId)
                .orElseThrow(() -> new A2aDomainException("A2A_TRUST_PROFILE_NOT_ACTIVE",
                        "an active Trust Profile is required"));
        if (!profile.direction().accepts(A2aDirection.INBOUND)) {
            throw new A2aDomainException("A2A_TRUST_PROFILE_DIRECTION_MISMATCH",
                    "the Trust Profile does not allow inbound A2A requests");
        }
        if (profile.environment() != environment) {
            throw new A2aDomainException("A2A_TRUST_PROFILE_ENVIRONMENT_MISMATCH",
                    "the Trust Profile environment does not match the publication");
        }
        requireAllowed(profile.authorizationPolicy().publicationKeys(), publicationKey,
                "A2A_PUBLICATION_NOT_ALLOWED", "the Trust Profile does not allow this publication");
        requireAllowed(profile.authorizationPolicy().operations(), "message:send",
                "A2A_SEND_NOT_ALLOWED", "the Trust Profile must allow message:send");
        if (tenantScope != null && !tenantScope.isBlank()) {
            requireAllowed(profile.authorizationPolicy().tenantScopes(), tenantScope.trim(),
                    "A2A_TENANT_NOT_ALLOWED", "the Trust Profile does not allow this tenant scope");
        }
        for (A2aProtocolSkill skill : skills) {
            requireAllowed(profile.authorizationPolicy().protocolSkillIds(), skill.id(),
                    "A2A_PROTOCOL_SKILL_NOT_ALLOWED",
                    "the Trust Profile does not allow protocol skill: " + skill.id());
        }
        return profile;
    }

    private void requireAllowed(Set<String> allowlist, String value, String code, String detail) {
        if (allowlist == null || !(allowlist.contains("*") || allowlist.contains(value))) {
            throw new A2aDomainException(code, detail);
        }
    }

    private A2aLocalAgentCatalog.PublishedAgent requireAgent(String agentId, Long configVersionId) {
        if (configVersionId == null || configVersionId <= 0) {
            throw new A2aDomainException("A2A_AGENT_CONFIG_REQUIRED",
                    "agentConfigVersionId is required");
        }
        return localAgentCatalog.requirePublished(agentId, configVersionId);
    }

    private A2aPublication requirePublication(long publicationId) {
        return repository.findById(publicationId).orElseThrow(() ->
                new A2aDomainException("A2A_PUBLICATION_NOT_FOUND", "publication was not found"));
    }

    private A2aPublicationRevision requireRevision(long publicationId, Long revisionId) {
        if (revisionId == null) {
            throw new A2aDomainException("A2A_PUBLICATION_REVISION_REQUIRED", "revisionId is required");
        }
        return repository.findRevision(publicationId, revisionId).orElseThrow(() ->
                new A2aDomainException("A2A_PUBLICATION_REVISION_NOT_FOUND",
                        "publication revision was not found"));
    }

    private DetailView detail(A2aPublication publication) {
        return new DetailView("reachai.a2a-hub.publication-detail.v1",
                PublicationView.from(publication),
                repository.findRevisions(publication.id()).stream().map(RevisionView::from).toList());
    }

    private A2aAgentCardGenerator.Source source(A2aPublicationRevision revision) {
        return new A2aAgentCardGenerator.Source(
                revision.name(), revision.description(), revision.agentVersion(),
                revision.providerOrganization(), revision.providerUrl(), revision.documentationUrl(),
                revision.iconUrl(), revision.publicOrigin(), revision.protocolBasePath(),
                revision.streamingSupported(), revision.pushNotificationsSupported(),
                revision.extendedCardSupported(), revision.defaultInputModes(),
                revision.defaultOutputModes(), revision.protocolSkills());
    }

    private List<A2aProtocolSkill> protocolSkills(List<ProtocolSkillRequest> requests) {
        if (requests == null) {
            return List.of();
        }
        return requests.stream().map(request -> new A2aProtocolSkill(
                request.id(), request.name(), request.description(), copy(request.tags()),
                copy(request.examples()), copy(request.inputModes()), copy(request.outputModes())))
                .toList();
    }

    private List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private String firstText(String primary, String fallback) {
        String value = trimToNull(primary);
        if (value == null) {
            value = trimToNull(fallback);
        }
        if (value == null) {
            throw new A2aDomainException("A2A_REQUIRED_FIELD", "required publication text is missing");
        }
        return value;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
