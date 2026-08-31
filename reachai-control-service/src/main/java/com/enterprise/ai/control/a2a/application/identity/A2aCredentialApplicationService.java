package com.enterprise.ai.control.a2a.application.identity;

import com.enterprise.ai.control.a2a.application.A2aPageView;
import com.enterprise.ai.control.a2a.application.port.A2aCredentialRepository;
import com.enterprise.ai.control.a2a.application.port.A2aCredentialCipher;
import com.enterprise.ai.control.a2a.application.port.A2aPrincipalRepository;
import com.enterprise.ai.control.a2a.application.port.A2aRemoteAgentRepository;
import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.identity.A2aCredential;
import com.enterprise.ai.control.a2a.domain.identity.A2aCredentialStatus;
import com.enterprise.ai.control.a2a.domain.identity.A2aCredentialType;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Arrays;

import static com.enterprise.ai.control.a2a.application.identity.A2aCredentialContracts.ApiKeyIssueView;
import static com.enterprise.ai.control.a2a.application.identity.A2aCredentialContracts.CreateApiKeyRequest;
import static com.enterprise.ai.control.a2a.application.identity.A2aCredentialContracts.CredentialView;
import static com.enterprise.ai.control.a2a.application.identity.A2aCredentialContracts.OutboundSecretStoredView;
import static com.enterprise.ai.control.a2a.application.identity.A2aCredentialContracts.RotateOutboundSecretRequest;
import static com.enterprise.ai.control.a2a.application.identity.A2aCredentialContracts.StoreOutboundSecretRequest;

@Service
@RequiredArgsConstructor
public class A2aCredentialApplicationService {

    private final A2aCredentialRepository repository;
    private final A2aPrincipalRepository principalRepository;
    private final A2aRemoteAgentRepository remoteAgentRepository;
    private final A2aCredentialCipher credentialCipher;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom;
    private final Clock clock;

    @Transactional(readOnly = true)
    public A2aPageView<CredentialView> list(
            String search, String requestedStatus, Integer requestedLimit, Integer requestedOffset) {
        int limit = requestedLimit == null ? 20 : Math.max(1, Math.min(requestedLimit, 100));
        int offset = requestedOffset == null ? 0 : Math.max(0, requestedOffset);
        String status = requestedStatus == null || requestedStatus.isBlank()
                ? null : A2aCredentialStatus.parse(requestedStatus).name();
        A2aCredentialRepository.Page page = repository.findPage(search, status, limit, offset);
        return A2aPageView.of("reachai.a2a-hub.credential-page.v1",
                page.items().stream().map(CredentialView::from).toList(),
                page.total(), limit, offset);
    }

    @Transactional(readOnly = true)
    public CredentialView detail(long id) {
        return CredentialView.from(requireCredential(id));
    }

    @Transactional
    public ApiKeyIssueView createApiKey(CreateApiKeyRequest request, String actor) {
        if (request == null) {
            throw new A2aDomainException("A2A_CREDENTIAL_REQUEST_REQUIRED", "request is required");
        }
        if (repository.existsByKey(request.credentialKey())) {
            throw new A2aDomainException("A2A_CREDENTIAL_KEY_CONFLICT",
                    "credentialKey already exists; use rotation to create a new version");
        }
        return issue(request.credentialKey(), request.name(), request.expiresAt(), null, 1, actor);
    }

    @Transactional
    public ApiKeyIssueView rotate(long credentialId, String actor) {
        A2aCredential current = requireCredential(credentialId);
        if (current.direction() != A2aDirection.INBOUND) {
            throw new A2aDomainException("A2A_CREDENTIAL_ROTATION_MODE_INVALID",
                    "outbound credentials require a replacement secret");
        }
        if (current.status() != A2aCredentialStatus.ACTIVE
                && current.status() != A2aCredentialStatus.GRACE) {
            throw new A2aDomainException("A2A_CREDENTIAL_NOT_ROTATABLE",
                    "only an active credential can be rotated");
        }
        ApiKeyIssueView issued = issue(current.credentialKey(), current.name(), current.expiresAt(),
                current.id(), repository.nextVersion(current.credentialKey()), actor);
        principalRepository.rebindCredential(current.id(), issued.credential().id());
        repository.save(current.withStatus(A2aCredentialStatus.REVOKED));
        return issued;
    }

    @Transactional
    public CredentialView revoke(long credentialId) {
        A2aCredential current = requireCredential(credentialId);
        return CredentialView.from(repository.save(current.withStatus(A2aCredentialStatus.REVOKED)));
    }

    @Transactional
    public OutboundSecretStoredView storeOutboundSecret(
            StoreOutboundSecretRequest request, String actor) {
        if (request == null) {
            throw new A2aDomainException("A2A_CREDENTIAL_REQUEST_REQUIRED", "request is required");
        }
        if (repository.existsByKey(request.credentialKey())) {
            throw new A2aDomainException("A2A_CREDENTIAL_KEY_CONFLICT",
                    "credentialKey already exists; use outbound rotation to create a new version");
        }
        A2aCredentialType type = outboundType(request.credentialType());
        A2aCredential saved = issueOutbound(
                request.credentialKey(), request.name(), type, request.secret(), request.expiresAt(),
                null, 1, actor);
        return stored(saved);
    }

    @Transactional
    public OutboundSecretStoredView rotateOutbound(
            long credentialId, RotateOutboundSecretRequest request, String actor) {
        A2aCredential current = requireCredential(credentialId);
        if (current.direction() != A2aDirection.OUTBOUND
                || !"ENCRYPTED".equals(current.materialMode())
                || (current.status() != A2aCredentialStatus.ACTIVE
                    && current.status() != A2aCredentialStatus.GRACE)) {
            throw new A2aDomainException("A2A_CREDENTIAL_NOT_ROTATABLE",
                    "only an active encrypted outbound credential can be rotated");
        }
        if (request == null) {
            throw new A2aDomainException("A2A_CREDENTIAL_REQUEST_REQUIRED", "request is required");
        }
        A2aCredential saved = issueOutbound(
                current.credentialKey(), current.name(), current.credentialType(), request.secret(),
                request.expiresAt() == null ? current.expiresAt() : request.expiresAt(),
                current.id(), repository.nextVersion(current.credentialKey()), actor);
        remoteAgentRepository.rebindCredential(current.id(), saved.id());
        repository.save(current.withStatus(A2aCredentialStatus.REVOKED));
        return stored(saved);
    }

    private ApiKeyIssueView issue(
            String credentialKey,
            String name,
            LocalDateTime requestedExpiry,
            Long rotatedFromId,
            int versionNo,
            String actor) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expiresAt = requestedExpiry == null ? now.plusDays(90) : requestedExpiry;
        byte[] secretBytes = new byte[32];
        secureRandom.nextBytes(secretBytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        String raw = "ra2a_" + credentialKey + "." + versionNo + "." + secret;
        A2aCredential saved = repository.save(new A2aCredential(
                null, credentialKey, name, A2aDirection.INBOUND, A2aCredentialType.API_KEY,
                "HASHED", passwordEncoder.encode(raw), null, null, null, null,
                sha256(raw), versionNo,
                A2aCredentialStatus.ACTIVE, now, expiresAt, null, rotatedFromId,
                actor, null, null));
        return new ApiKeyIssueView(
                "reachai.a2a-hub.api-key-issue.v1",
                CredentialView.from(saved), raw,
                "COPY_AND_STORE_SECURELY_NOW");
    }

    private A2aCredential issueOutbound(
            String credentialKey,
            String name,
            A2aCredentialType type,
            String secret,
            LocalDateTime requestedExpiry,
            Long rotatedFromId,
            int versionNo,
            String actor) {
        if (secret == null || secret.length() < 16 || secret.length() > 8192) {
            throw new A2aDomainException("A2A_OUTBOUND_SECRET_INVALID",
                    "outbound secret must contain between 16 and 8192 characters");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expiresAt = requestedExpiry == null ? now.plusDays(90) : requestedExpiry;
        if (!expiresAt.isAfter(now)) {
            throw new A2aDomainException("A2A_CREDENTIAL_TIME_INVALID",
                    "expiresAt must be in the future");
        }
        byte[] plaintext = secret.getBytes(StandardCharsets.UTF_8);
        try {
            A2aCredentialCipher.EncryptedSecret encrypted = credentialCipher.encrypt(
                    plaintext, A2aCredentialBinding.outbound(credentialKey, versionNo));
            return repository.save(new A2aCredential(
                    null, credentialKey, name, A2aDirection.OUTBOUND, type, "ENCRYPTED", null,
                    encrypted.ciphertext(), encrypted.keyId(), encrypted.nonce(), null,
                    sha256(secret), versionNo, A2aCredentialStatus.ACTIVE, now, expiresAt,
                    null, rotatedFromId, actor, null, null));
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private A2aCredentialType outboundType(String value) {
        A2aCredentialType type = A2aCredentialType.parse(value);
        if (type != A2aCredentialType.API_KEY && type != A2aCredentialType.BEARER) {
            throw new A2aDomainException("A2A_OUTBOUND_CREDENTIAL_TYPE_UNSUPPORTED",
                    "the first outbound transport supports API_KEY and BEARER credentials");
        }
        return type;
    }

    private OutboundSecretStoredView stored(A2aCredential credential) {
        return new OutboundSecretStoredView(
                "reachai.a2a-hub.outbound-secret-stored.v1", CredentialView.from(credential),
                "BIND_TO_REVIEWED_REMOTE_AGENT");
    }

    private A2aCredential requireCredential(long id) {
        return repository.findById(id).orElseThrow(() ->
                new A2aDomainException("A2A_CREDENTIAL_NOT_FOUND", "credential was not found"));
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
