package com.enterprise.ai.control.a2a.application.trust;

import com.enterprise.ai.control.a2a.application.A2aPageView;
import com.enterprise.ai.control.a2a.application.port.A2aTrustProfileRepository;
import com.enterprise.ai.control.a2a.domain.A2aAuthenticationMethod;
import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.A2aTrustLevel;
import com.enterprise.ai.control.a2a.domain.trust.A2aAuthorizationPolicy;
import com.enterprise.ai.control.a2a.domain.trust.A2aDataPolicy;
import com.enterprise.ai.control.a2a.domain.trust.A2aDelegatedIdentityPolicy;
import com.enterprise.ai.control.a2a.domain.trust.A2aEnvironment;
import com.enterprise.ai.control.a2a.domain.trust.A2aPersonalMemoryPolicy;
import com.enterprise.ai.control.a2a.domain.trust.A2aTrustProfile;
import com.enterprise.ai.control.a2a.domain.trust.A2aTrustProfileStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static com.enterprise.ai.control.a2a.application.trust.A2aTrustProfileContracts.UpsertRequest;
import static com.enterprise.ai.control.a2a.application.trust.A2aTrustProfileContracts.View;

@Service
@RequiredArgsConstructor
public class A2aTrustProfileApplicationService {

    private static final String PAGE_SCHEMA = "reachai.a2a-hub.trust-profile-page.v1";
    private final A2aTrustProfileRepository repository;

    @Transactional(readOnly = true)
    public A2aPageView<View> list(String search, String status, Integer requestedLimit, Integer requestedOffset) {
        int limit = requestedLimit == null ? 20 : Math.max(1, Math.min(requestedLimit, 100));
        int offset = requestedOffset == null ? 0 : Math.max(0, requestedOffset);
        String normalizedStatus = status == null || status.isBlank()
                ? null
                : A2aTrustProfileStatus.parse(status).name();
        A2aTrustProfileRepository.Page page = repository.findPage(search, normalizedStatus, limit, offset);
        return A2aPageView.of(PAGE_SCHEMA, page.items().stream().map(View::from).toList(),
                page.total(), limit, offset);
    }

    @Transactional(readOnly = true)
    public View detail(long id) {
        return View.from(requireProfile(id));
    }

    @Transactional
    public View create(UpsertRequest request, String actor) {
        A2aTrustProfile profile = fromRequest(null, request, actor, null);
        requireUniqueKey(profile.profileKey(), null);
        return View.from(repository.save(profile));
    }

    @Transactional
    public View update(long id, UpsertRequest request, String actor) {
        A2aTrustProfile existing = requireProfile(id);
        if (request == null || request.profileKey() == null
                || !existing.profileKey().equals(request.profileKey().trim())) {
            throw new A2aDomainException("A2A_TRUST_PROFILE_KEY_IMMUTABLE",
                    "profileKey is immutable after creation");
        }
        A2aTrustProfile updated = fromRequest(existing, request, actor, existing.status());
        requireUniqueKey(updated.profileKey(), id);
        return View.from(repository.save(updated));
    }

    @Transactional
    public View transition(long id, String requestedStatus, String actor) {
        A2aTrustProfile existing = requireProfile(id);
        A2aTrustProfileStatus next = A2aTrustProfileStatus.parse(requestedStatus);
        return View.from(repository.save(existing.withStatus(next, actor)));
    }

    private A2aTrustProfile fromRequest(
            A2aTrustProfile existing,
            UpsertRequest request,
            String actor,
            A2aTrustProfileStatus existingStatus) {
        if (request == null) {
            throw new A2aDomainException("A2A_REQUEST_REQUIRED", "request body is required");
        }
        Set<A2aAuthenticationMethod> authenticationMethods = values(request.authenticationMethods()).stream()
                .map(A2aAuthenticationMethod::parse)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        A2aTrustProfileContracts.AuthorizationPolicyRequest authorization = request.authorizationPolicy();
        A2aTrustProfileContracts.DataPolicyRequest data = request.dataPolicy();
        if (authorization == null) {
            throw new A2aDomainException("A2A_AUTHORIZATION_POLICY_REQUIRED",
                    "authorizationPolicy is required");
        }
        if (data == null) {
            throw new A2aDomainException("A2A_DATA_POLICY_REQUIRED", "dataPolicy is required");
        }
        return new A2aTrustProfile(
                existing == null ? null : existing.id(),
                request.profileKey(),
                request.name(),
                blankToNull(request.description()),
                A2aDirection.parse(request.direction()),
                A2aEnvironment.parse(request.environment()),
                A2aTrustLevel.parse(request.trustLevel()),
                authenticationMethods,
                Set.copyOf(values(request.allowedScopes())),
                new A2aAuthorizationPolicy(
                        Set.copyOf(values(authorization.publicationKeys())),
                        Set.copyOf(values(authorization.remoteAgentKeys())),
                        Set.copyOf(values(authorization.protocolSkillIds())),
                        Set.copyOf(values(authorization.operations())),
                        Set.copyOf(values(authorization.tenantScopes()))),
                new A2aDataPolicy(
                        Set.copyOf(values(data.allowedDataClassifications())),
                        Boolean.TRUE.equals(data.allowTextParts()),
                        Boolean.TRUE.equals(data.allowFileParts()),
                        Boolean.TRUE.equals(data.allowUrlParts()),
                        data.payloadRetentionDays() == null ? 30 : data.payloadRetentionDays()),
                A2aDelegatedIdentityPolicy.parse(request.delegatedIdentityPolicy()),
                A2aPersonalMemoryPolicy.parse(request.personalMemoryPolicy()),
                positiveOrDefault(request.rateLimitPerMinute(), 60),
                positiveOrDefault(request.maxConcurrentTasks(), 10),
                positiveOrDefault(request.maxRequestBytes(), 1_048_576L),
                positiveOrDefault(request.maxArtifactBytes(), 10_485_760L),
                positiveOrDefault(request.taskTimeoutMs(), 300_000L),
                Boolean.TRUE.equals(request.allowAnonymous()),
                existingStatus == null ? A2aTrustProfileStatus.ACTIVE : existingStatus,
                existing == null ? 0 : existing.version(),
                existing == null ? actor : existing.createdBy(),
                actor,
                existing == null ? null : existing.createdAt(),
                existing == null ? null : existing.updatedAt());
    }

    private A2aTrustProfile requireProfile(long id) {
        return repository.findById(id).orElseThrow(() ->
                new A2aDomainException("A2A_TRUST_PROFILE_NOT_FOUND",
                        "trust profile was not found"));
    }

    private void requireUniqueKey(String profileKey, Long excludingId) {
        if (repository.existsByProfileKey(profileKey, excludingId)) {
            throw new A2aDomainException("A2A_TRUST_PROFILE_KEY_CONFLICT",
                    "profileKey is already in use");
        }
    }

    private static List<String> values(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static int positiveOrDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static long positiveOrDefault(Long value, long fallback) {
        return value == null ? fallback : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
