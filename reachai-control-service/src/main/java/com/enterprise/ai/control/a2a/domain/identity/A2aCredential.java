package com.enterprise.ai.control.a2a.domain.identity;

import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.A2aDomainText;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

public record A2aCredential(
        Long id,
        String credentialKey,
        String name,
        A2aDirection direction,
        A2aCredentialType credentialType,
        String materialMode,
        String materialHash,
        String materialCiphertext,
        String encryptionKeyId,
        String encryptionNonce,
        String externalRef,
        String fingerprint,
        int versionNo,
        A2aCredentialStatus status,
        LocalDateTime notBefore,
        LocalDateTime expiresAt,
        LocalDateTime lastUsedAt,
        Long rotatedFromId,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z][a-z0-9-]{2,95}");

    public A2aCredential {
        credentialKey = A2aDomainText.requireText(credentialKey, "credentialKey");
        if (!KEY_PATTERN.matcher(credentialKey).matches()) {
            throw new A2aDomainException("A2A_CREDENTIAL_KEY_INVALID",
                    "credentialKey must use 3-96 lowercase letters, digits, or hyphens");
        }
        name = A2aDomainText.requireText(name, "name");
        if (name.length() > 128 || direction == null || credentialType == null) {
            throw new A2aDomainException("A2A_CREDENTIAL_INVALID", "credential metadata is invalid");
        }
        materialMode = A2aDomainText.requireText(materialMode, "materialMode");
        boolean inboundHashed = direction == A2aDirection.INBOUND
                && credentialType == A2aCredentialType.API_KEY
                && "HASHED".equals(materialMode)
                && materialHash != null && !materialHash.isBlank()
                && materialCiphertext == null && externalRef == null;
        boolean outboundEncrypted = direction == A2aDirection.OUTBOUND
                && (credentialType == A2aCredentialType.API_KEY
                    || credentialType == A2aCredentialType.BEARER)
                && "ENCRYPTED".equals(materialMode)
                && materialHash == null && externalRef == null
                && materialCiphertext != null && !materialCiphertext.isBlank()
                && encryptionKeyId != null && !encryptionKeyId.isBlank()
                && encryptionNonce != null && !encryptionNonce.isBlank();
        if (!inboundHashed && !outboundEncrypted) {
            throw new A2aDomainException("A2A_CREDENTIAL_MODE_UNSUPPORTED",
                    "credentials must be inbound HASHED API_KEY or outbound ENCRYPTED API_KEY/BEARER");
        }
        fingerprint = A2aDomainText.requireText(fingerprint, "fingerprint");
        if (fingerprint.length() != 64 || versionNo <= 0 || status == null) {
            throw new A2aDomainException("A2A_CREDENTIAL_INVALID", "credential metadata is invalid");
        }
        if (notBefore != null && expiresAt != null && !expiresAt.isAfter(notBefore)) {
            throw new A2aDomainException("A2A_CREDENTIAL_TIME_INVALID",
                    "expiresAt must be after notBefore");
        }
    }

    public A2aCredential withStatus(A2aCredentialStatus next) {
        if (status == A2aCredentialStatus.REVOKED && next != A2aCredentialStatus.REVOKED) {
            throw new A2aDomainException("A2A_CREDENTIAL_REVOKED",
                    "a revoked credential cannot be reactivated");
        }
        return new A2aCredential(id, credentialKey, name, direction, credentialType, materialMode,
                materialHash, materialCiphertext, encryptionKeyId, encryptionNonce, externalRef,
                fingerprint, versionNo, next, notBefore, expiresAt, lastUsedAt, rotatedFromId,
                createdBy, createdAt, updatedAt);
    }

    public boolean usableAt(LocalDateTime now) {
        return (status == A2aCredentialStatus.ACTIVE || status == A2aCredentialStatus.GRACE)
                && (notBefore == null || !now.isBefore(notBefore))
                && (expiresAt == null || now.isBefore(expiresAt));
    }
}
