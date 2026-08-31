package com.enterprise.ai.control.a2a.application.identity;

import com.enterprise.ai.control.a2a.application.port.A2aCredentialRepository;
import com.enterprise.ai.control.a2a.application.port.A2aPrincipalRepository;
import com.enterprise.ai.control.a2a.application.port.A2aPublicationRepository.PublishedCard;
import com.enterprise.ai.control.a2a.application.port.A2aTrustProfileRepository;
import com.enterprise.ai.control.a2a.application.publication.A2aPublishedCardService;
import com.enterprise.ai.control.a2a.domain.A2aAuthenticationMethod;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.A2aPrincipalContext;
import com.enterprise.ai.control.a2a.domain.identity.A2aCredential;
import com.enterprise.ai.control.a2a.domain.identity.A2aPrincipal;
import com.enterprise.ai.control.a2a.domain.trust.A2aTrustProfile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Set;

@Service
public class A2aInboundAuthenticationService {

    public static final String API_KEY_HEADER = "X-ReachAI-A2A-Key";

    private final A2aPublishedCardService publishedCardService;
    private final A2aTrustProfileRepository trustProfileRepository;
    private final A2aCredentialRepository credentialRepository;
    private final A2aPrincipalRepository principalRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final String dummyHash;

    public A2aInboundAuthenticationService(
            A2aPublishedCardService publishedCardService,
            A2aTrustProfileRepository trustProfileRepository,
            A2aCredentialRepository credentialRepository,
            A2aPrincipalRepository principalRepository,
            PasswordEncoder passwordEncoder,
            Clock clock) {
        this.publishedCardService = publishedCardService;
        this.trustProfileRepository = trustProfileRepository;
        this.credentialRepository = credentialRepository;
        this.principalRepository = principalRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("reachai-a2a-invalid-api-key");
    }

    @Transactional
    public A2aInboundCallContext authenticate(String hostHeader, String rawApiKey) {
        PublishedCard publication = publishedCardService.findByHost(hostHeader)
                .orElseThrow(() -> new A2aDomainException("A2A_PUBLICATION_NOT_FOUND",
                        "published A2A endpoint was not found"));
        A2aTrustProfile trust = trustProfileRepository.findActiveById(publication.trustProfileId())
                .orElseThrow(() -> new A2aDomainException("A2A_TRUST_PROFILE_NOT_ACTIVE",
                        "the publication Trust Profile is not active"));
        if (trust.allowAnonymous()) {
            if (rawApiKey != null && !rawApiKey.isBlank()) {
                throw authenticationFailed();
            }
            A2aPrincipal principal = principalRepository.findActiveAnonymous(trust.id())
                    .orElseThrow(this::authenticationFailed);
            return authenticated(publication, trust, principal, A2aAuthenticationMethod.ANONYMOUS, null);
        }
        if (!trust.authenticationMethods().equals(Set.of(A2aAuthenticationMethod.API_KEY))) {
            throw new A2aDomainException("A2A_AUTHENTICATION_METHOD_UNAVAILABLE",
                    "the publication authentication method is not available");
        }
        ApiKeyReference reference = parse(rawApiKey);
        LocalDateTime now = LocalDateTime.now(clock);
        A2aCredential credential = reference == null ? null
                : credentialRepository.findUsableByKeyAndVersion(
                        reference.credentialKey(), reference.versionNo(), now).orElse(null);
        String storedHash = credential == null ? dummyHash : credential.materialHash();
        boolean matches = passwordEncoder.matches(rawApiKey == null ? "" : rawApiKey, storedHash);
        if (!matches || credential == null) {
            throw authenticationFailed();
        }
        A2aPrincipal principal = principalRepository
                .findActiveByCredential(credential.id(), trust.id())
                .orElseThrow(this::authenticationFailed);
        return authenticated(publication, trust, principal, A2aAuthenticationMethod.API_KEY, credential);
    }

    private A2aInboundCallContext authenticated(
            PublishedCard publication,
            A2aTrustProfile trust,
            A2aPrincipal principal,
            A2aAuthenticationMethod method,
            A2aCredential credential) {
        if (!publication.tenantScope().isBlank()
                && !publication.tenantScope().equals(principal.tenantScope())) {
            throw new A2aDomainException("A2A_PRINCIPAL_TENANT_FORBIDDEN",
                    "the authenticated principal is not authorized for this publication tenant");
        }
        if (!trust.allowedScopes().contains("*")
                && !trust.allowedScopes().containsAll(principal.scopes())) {
            throw new A2aDomainException("A2A_PRINCIPAL_SCOPE_FORBIDDEN",
                    "the authenticated principal scopes exceed the Trust Profile");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        principalRepository.markAuthenticated(principal.id(), now);
        if (credential != null) {
            credentialRepository.markUsed(credential.id(), now);
        }
        return new A2aInboundCallContext(publication,
                new A2aPrincipalContext(
                        principal.id(), principal.principalKey(), principal.principalType(),
                        principal.tenantScope(), method, trust.trustLevel(), principal.scopes(), null),
                trust);
    }

    private ApiKeyReference parse(String rawApiKey) {
        if (rawApiKey == null || !rawApiKey.startsWith("ra2a_") || rawApiKey.length() > 512) {
            return null;
        }
        String[] parts = rawApiKey.substring("ra2a_".length()).split("\\.", -1);
        if (parts.length != 3 || parts[0].isBlank() || parts[2].length() < 32) {
            return null;
        }
        try {
            int version = Integer.parseInt(parts[1]);
            return version <= 0 ? null : new ApiKeyReference(parts[0], version);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private A2aDomainException authenticationFailed() {
        return new A2aDomainException("A2A_AUTHENTICATION_FAILED",
                "A2A authentication failed");
    }

    private record ApiKeyReference(String credentialKey, int versionNo) {
    }
}
