package com.enterprise.ai.control.a2a.application.identity;

import com.enterprise.ai.control.a2a.application.A2aPageView;
import com.enterprise.ai.control.a2a.application.port.A2aCredentialRepository;
import com.enterprise.ai.control.a2a.application.port.A2aPrincipalRepository;
import com.enterprise.ai.control.a2a.application.port.A2aTrustProfileRepository;
import com.enterprise.ai.control.a2a.domain.A2aAuthenticationMethod;
import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.A2aPrincipalType;
import com.enterprise.ai.control.a2a.domain.identity.A2aCredential;
import com.enterprise.ai.control.a2a.domain.identity.A2aCredentialStatus;
import com.enterprise.ai.control.a2a.domain.identity.A2aPrincipal;
import com.enterprise.ai.control.a2a.domain.identity.A2aPrincipalStatus;
import com.enterprise.ai.control.a2a.domain.trust.A2aTrustProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.regex.Pattern;

import static com.enterprise.ai.control.a2a.application.identity.A2aPrincipalContracts.CreateRequest;
import static com.enterprise.ai.control.a2a.application.identity.A2aPrincipalContracts.View;

@Service
@RequiredArgsConstructor
public class A2aPrincipalApplicationService {

    private static final String RUNTIME_AGENT_ID_ATTRIBUTE = "runtimeAgentId";
    private static final Pattern RUNTIME_AGENT_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private final A2aPrincipalRepository repository;
    private final A2aCredentialRepository credentialRepository;
    private final A2aTrustProfileRepository trustProfileRepository;

    @Transactional(readOnly = true)
    public A2aPageView<View> list(
            String search, String requestedStatus, Integer requestedLimit, Integer requestedOffset) {
        int limit = requestedLimit == null ? 20 : Math.max(1, Math.min(requestedLimit, 100));
        int offset = requestedOffset == null ? 0 : Math.max(0, requestedOffset);
        String status = requestedStatus == null || requestedStatus.isBlank()
                ? null : A2aPrincipalStatus.parse(requestedStatus).name();
        A2aPrincipalRepository.Page page = repository.findPage(search, status, limit, offset);
        return A2aPageView.of("reachai.a2a-hub.principal-page.v1",
                page.items().stream().map(View::from).toList(), page.total(), limit, offset);
    }

    @Transactional(readOnly = true)
    public View detail(long id) {
        return View.from(requirePrincipal(id));
    }

    @Transactional
    public View create(CreateRequest request, String actor) {
        if (request == null || request.trustProfileId() == null) {
            throw new A2aDomainException("A2A_PRINCIPAL_REQUEST_REQUIRED",
                    "principal and trustProfileId are required");
        }
        if (repository.existsByKey(request.principalKey())) {
            throw new A2aDomainException("A2A_PRINCIPAL_KEY_CONFLICT",
                    "principalKey is already in use");
        }
        A2aTrustProfile trust = trustProfileRepository.findActiveById(request.trustProfileId())
                .orElseThrow(() -> new A2aDomainException("A2A_TRUST_PROFILE_NOT_ACTIVE",
                        "an active Trust Profile is required"));
        A2aPrincipalType principalType = A2aPrincipalType.parse(request.principalType());
        requireDirection(principalType, trust);
        A2aCredential credential = principalType == A2aPrincipalType.LOCAL_AGENT
                ? resolveLocalAgentCredential(request.credentialId())
                : resolveInboundCredential(request.credentialId(), trust);
        Set<String> scopes = request.scopes() == null ? Set.of() : Set.copyOf(request.scopes());
        if (!trust.allowedScopes().contains("*") && !trust.allowedScopes().containsAll(scopes)) {
            throw new A2aDomainException("A2A_PRINCIPAL_SCOPE_NOT_ALLOWED",
                    "principal scopes must be a subset of the Trust Profile scopes");
        }
        String tenant = request.tenantScope() == null ? "" : request.tenantScope().trim();
        if (!tenant.isEmpty()
                && !trust.authorizationPolicy().tenantScopes().contains("*")
                && !trust.authorizationPolicy().tenantScopes().contains(tenant)) {
            throw new A2aDomainException("A2A_PRINCIPAL_TENANT_NOT_ALLOWED",
                    "principal tenant is not allowed by the Trust Profile");
        }
        Map<String, String> attributes = attributes(request.attributes(), principalType,
                request.runtimeAgentId());
        String subject = principalType == A2aPrincipalType.LOCAL_AGENT
                ? "local-agent:" + attributes.get(RUNTIME_AGENT_ID_ATTRIBUTE)
                : credential == null
                    ? "anonymous:" + request.principalKey()
                    : "api-key:" + credential.fingerprint();
        A2aPrincipal principal = new A2aPrincipal(
                null, request.principalKey(), principalType,
                request.displayName(), tenant, subject, trust.id(),
                credential == null ? null : credential.id(), scopes,
                attributes,
                A2aPrincipalStatus.ACTIVE, null, 0, actor, actor, null, null);
        return View.from(repository.save(principal));
    }

    @Transactional
    public View transition(long id, String requestedStatus, String actor) {
        A2aPrincipal principal = requirePrincipal(id);
        return View.from(repository.save(principal.withStatus(
                A2aPrincipalStatus.parse(requestedStatus), actor)));
    }

    private A2aCredential resolveInboundCredential(Long credentialId, A2aTrustProfile trust) {
        if (trust.allowAnonymous()) {
            if (credentialId != null) {
                throw new A2aDomainException("A2A_ANONYMOUS_CREDENTIAL_FORBIDDEN",
                        "an anonymous Trust Profile cannot bind a credential");
            }
            return null;
        }
        if (!trust.authenticationMethods().equals(Set.of(A2aAuthenticationMethod.API_KEY))) {
            throw new A2aDomainException("A2A_PRINCIPAL_AUTH_METHOD_UNSUPPORTED",
                    "the initial inbound adapter supports API_KEY principals only");
        }
        if (credentialId == null) {
            throw new A2aDomainException("A2A_PRINCIPAL_CREDENTIAL_REQUIRED",
                    "credentialId is required for an authenticated principal");
        }
        A2aCredential credential = credentialRepository.findById(credentialId)
                .orElseThrow(() -> new A2aDomainException("A2A_CREDENTIAL_NOT_FOUND",
                        "credential was not found"));
        if (credential.status() != A2aCredentialStatus.ACTIVE) {
            throw new A2aDomainException("A2A_CREDENTIAL_NOT_ACTIVE",
                    "an active credential is required");
        }
        if (credential.direction() != A2aDirection.INBOUND
                || credential.credentialType()
                != com.enterprise.ai.control.a2a.domain.identity.A2aCredentialType.API_KEY
                || !"HASHED".equals(credential.materialMode())) {
            throw new A2aDomainException("A2A_PRINCIPAL_CREDENTIAL_DIRECTION_INVALID",
                    "remote principals require a hashed INBOUND API_KEY credential");
        }
        return credential;
    }

    private A2aCredential resolveLocalAgentCredential(Long credentialId) {
        if (credentialId != null) {
            throw new A2aDomainException("A2A_LOCAL_AGENT_CREDENTIAL_FORBIDDEN",
                    "LOCAL_AGENT Principals are attested by Runtime and cannot bind an inbound credential");
        }
        return null;
    }

    private void requireDirection(A2aPrincipalType type, A2aTrustProfile trust) {
        A2aDirection required = type == A2aPrincipalType.LOCAL_AGENT
                ? A2aDirection.OUTBOUND : A2aDirection.INBOUND;
        if (!trust.direction().accepts(required)) {
            throw new A2aDomainException("A2A_PRINCIPAL_DIRECTION_MISMATCH",
                    type == A2aPrincipalType.LOCAL_AGENT
                            ? "LOCAL_AGENT Principals require an OUTBOUND Trust Profile"
                            : "remote Principals require an INBOUND Trust Profile");
        }
    }

    private Map<String, String> attributes(
            Map<String, String> requested,
            A2aPrincipalType type,
            String requestedRuntimeAgentId) {
        Map<String, String> values = new LinkedHashMap<>(requested == null ? Map.of() : requested);
        if (type != A2aPrincipalType.LOCAL_AGENT) {
            if (requestedRuntimeAgentId != null && !requestedRuntimeAgentId.isBlank()) {
                throw new A2aDomainException("A2A_RUNTIME_AGENT_ID_FORBIDDEN",
                        "runtimeAgentId is valid only for LOCAL_AGENT Principals");
            }
            values.remove(RUNTIME_AGENT_ID_ATTRIBUTE);
            return Map.copyOf(values);
        }
        String runtimeAgentId = requestedRuntimeAgentId == null
                ? "" : requestedRuntimeAgentId.trim();
        if (!RUNTIME_AGENT_ID.matcher(runtimeAgentId).matches()) {
            throw new A2aDomainException("A2A_RUNTIME_AGENT_ID_INVALID",
                    "LOCAL_AGENT Principals require a valid runtimeAgentId");
        }
        String attributeValue = values.get(RUNTIME_AGENT_ID_ATTRIBUTE);
        if (attributeValue != null && !runtimeAgentId.equals(attributeValue.trim())) {
            throw new A2aDomainException("A2A_RUNTIME_AGENT_ID_CONFLICT",
                    "runtimeAgentId cannot conflict with Principal attributes");
        }
        values.put(RUNTIME_AGENT_ID_ATTRIBUTE, runtimeAgentId);
        return Map.copyOf(values);
    }

    private A2aPrincipal requirePrincipal(long id) {
        return repository.findById(id).orElseThrow(() ->
                new A2aDomainException("A2A_PRINCIPAL_NOT_FOUND", "principal was not found"));
    }
}
