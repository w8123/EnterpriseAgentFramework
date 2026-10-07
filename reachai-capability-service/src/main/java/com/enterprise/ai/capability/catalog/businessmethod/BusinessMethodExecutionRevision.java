package com.enterprise.ai.capability.catalog.businessmethod;

import com.enterprise.ai.agent.registry.RegistryCredentialEntity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;

/** Safe binding identity. Credential material never enters the digest or an owner DTO. */
public final class BusinessMethodExecutionRevision {
    private BusinessMethodExecutionRevision() { }

    public static String of(BusinessMethodDefinition method, RegistryCredentialEntity credential) {
        if (method == null || method.asset().getId() == null || method.asset().getId() <= 0
                || method.revision().getId() == null || method.revision().getId() <= 0
                || method.revision().getBindingHash() == null || !method.revision().getBindingHash().matches("[0-9a-f]{64}")
                || credential == null || credential.getId() == null || credential.getId() <= 0
                || credential.getRevision() == null || credential.getRevision() <= 0
                || !Objects.equals(method.asset().getProjectId(), credential.getProjectId())
                || !Objects.equals(method.asset().getProjectCode(), credential.getProjectCode())
                || !"ACTIVE".equals(credential.getStatus())
                || credential.getExpiresAt() != null && !credential.getExpiresAt().isAfter(LocalDateTime.now())
                || credential.getAppKey() == null || credential.getAppKey().isBlank()
                || credential.getAppSecret() == null || credential.getAppSecret().isBlank()) return null;
        String identity = "business-method-binding-v1\n" + method.asset().getId() + "\n"
                + method.revision().getId() + "\n" + method.revision().getBindingHash() + "\n"
                + credential.getId() + "\n" + credential.getRevision();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception unavailable) { throw new IllegalStateException("Business method binding fingerprint unavailable", unavailable); }
    }
}
