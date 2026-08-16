package com.enterprise.ai.runtime.memory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.supervisor.RuntimeContextEngineeringProperties;
import io.agentscope.core.agent.RuntimeContext;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RuntimeToolResultArtifactService {

    public static final String ACTIVE = "ACTIVE";
    public static final String EXPIRED = "EXPIRED";
    public static final String DELETED = "DELETED";

    private final RuntimeToolResultArtifactMapper mapper;
    private final RuntimeToolResultArtifactCipher cipher;
    private final RuntimeContextEngineeringProperties properties;

    public Optional<ArtifactPointer> offload(
            RuntimeContext runtimeContext,
            String agentName,
            String traceId,
            String toolCallId,
            String toolName,
            String content) {
        if (!properties.toolResultOffloadActive()
                || !StringUtils.hasText(toolCallId)
                || content == null) {
            return Optional.empty();
        }
        Optional<ArtifactScope> scope = resolveScope(runtimeContext);
        if (scope.isEmpty()) {
            return Optional.empty();
        }
        byte[] plaintext = content.getBytes(StandardCharsets.UTF_8);
        if (plaintext.length > properties.artifactMaxBytes()) {
            return Optional.empty();
        }
        String contentDigest = cipher.fingerprint(plaintext);
        String boundedToolCallId = bounded(toolCallId, 128);

        String artifactRef = "tra_" + UUID.randomUUID().toString().replace("-", "");
        String aad = aad(artifactRef, scope.get(), contentDigest);
        RuntimeToolResultArtifactCipher.EncryptedPayload encrypted = cipher.encrypt(plaintext, aad);
        LocalDateTime now = LocalDateTime.now();
        RuntimeToolResultArtifactEntity entity = new RuntimeToolResultArtifactEntity();
        entity.setArtifactRef(artifactRef);
        entity.setOwnerScopeHash(scope.get().ownerScopeHash());
        entity.setSessionScopeHash(scope.get().sessionScopeHash());
        entity.setAgentName(bounded(
                StringUtils.hasText(agentName) ? agentName : "unknown-agent", 128));
        entity.setTraceId(bounded(traceId, 64));
        entity.setToolCallId(boundedToolCallId);
        entity.setToolName(bounded(toolName, 128));
        entity.setContentHmacSha256(contentDigest);
        entity.setActiveSlot(1);
        entity.setContentChars(content.length());
        entity.setContentBytes(plaintext.length);
        entity.setEncryptionKeyId(encrypted.keyId());
        entity.setEncryptionNonce(encrypted.nonce());
        entity.setContentCiphertext(encrypted.ciphertext());
        entity.setStatus(ACTIVE);
        entity.setExpiresAt(now.plusHours(properties.artifactRetentionHours()));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        DuplicateKeyException lastDuplicate = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            RuntimeToolResultArtifactEntity existing = findActiveSlot(
                    scope.get(), boundedToolCallId, contentDigest);
            if (readable(existing, LocalDateTime.now())) {
                return Optional.of(pointer(existing));
            }
            if (existing != null && existing.getId() != null) {
                scrubById(existing.getId(), EXPIRED);
            }
            try {
                mapper.insert(entity);
                return Optional.of(pointer(entity));
            } catch (DuplicateKeyException duplicate) {
                lastDuplicate = duplicate;
            }
        }
        throw lastDuplicate == null
                ? new IllegalStateException("tool-result artifact insert failed")
                : lastDuplicate;
    }

    public Optional<ArtifactChunk> read(
            RuntimeContext runtimeContext,
            String agentName,
            String artifactRef,
            int offset,
            int requestedMaxChars) {
        if (!properties.toolResultOffloadActive() || !validArtifactRef(artifactRef)) {
            return Optional.empty();
        }
        Optional<ArtifactScope> scope = resolveScope(runtimeContext);
        if (scope.isEmpty()) {
            return Optional.empty();
        }
        RuntimeToolResultArtifactEntity entity = mapper.selectOne(
                Wrappers.<RuntimeToolResultArtifactEntity>lambdaQuery()
                        .eq(RuntimeToolResultArtifactEntity::getArtifactRef, artifactRef)
                        .eq(RuntimeToolResultArtifactEntity::getOwnerScopeHash,
                                scope.get().ownerScopeHash())
                        .eq(RuntimeToolResultArtifactEntity::getSessionScopeHash,
                                scope.get().sessionScopeHash())
                        .eq(RuntimeToolResultArtifactEntity::getStatus, ACTIVE)
                        .gt(RuntimeToolResultArtifactEntity::getExpiresAt, LocalDateTime.now())
                        .last("LIMIT 1"));
        if (entity == null) {
            return Optional.empty();
        }
        byte[] plaintext = cipher.decrypt(
                entity.getContentCiphertext(),
                entity.getEncryptionNonce(),
                entity.getEncryptionKeyId(),
                aad(entity.getArtifactRef(), scope.get(), entity.getContentHmacSha256()));
        if (!constantTimeEquals(
                entity.getContentHmacSha256(), cipher.fingerprint(plaintext))) {
            throw new IllegalStateException("tool-result artifact integrity verification failed");
        }
        String content = new String(plaintext, StandardCharsets.UTF_8);
        if (offset < 0 || offset > content.length()) {
            throw new IllegalArgumentException("offset is outside the artifact content");
        }
        int maxChars = Math.max(1, Math.min(
                requestedMaxChars <= 0
                        ? properties.artifactReadChunkChars()
                        : requestedMaxChars,
                properties.artifactReadChunkChars()));
        int end = Math.min(content.length(), offset + maxChars);
        return Optional.of(new ArtifactChunk(
                entity.getArtifactRef(),
                offset,
                end,
                end < content.length(),
                content.length(),
                content.substring(offset, end)));
    }

    public void scrubSession(String stateUserKey, String stateSessionKey, String agentName) {
        if (!properties.toolResultArtifactStoreConfigured()) {
            return;
        }
        Optional<ArtifactScope> scope = resolveScope(stateUserKey, stateSessionKey);
        scope.ifPresent(value -> scrub(
                Wrappers.<RuntimeToolResultArtifactEntity>lambdaUpdate()
                        .eq(RuntimeToolResultArtifactEntity::getOwnerScopeHash,
                                value.ownerScopeHash())
                        .eq(RuntimeToolResultArtifactEntity::getSessionScopeHash,
                                value.sessionScopeHash())
                        .eq(RuntimeToolResultArtifactEntity::getStatus, ACTIVE),
                DELETED));
    }

    /**
     * Physical retention/manual-erasure path. Unlike user clear, this also removes
     * bounded metadata tombstones after the session has been lifecycle-claimed.
     */
    public void purgeSession(String stateUserKey, String stateSessionKey, String agentName) {
        if (!properties.toolResultArtifactStoreConfigured()) {
            return;
        }
        Optional<ArtifactScope> scope = resolveScope(stateUserKey, stateSessionKey);
        scope.ifPresent(value -> mapper.delete(
                Wrappers.<RuntimeToolResultArtifactEntity>lambdaQuery()
                        .eq(RuntimeToolResultArtifactEntity::getOwnerScopeHash,
                                value.ownerScopeHash())
                        .eq(RuntimeToolResultArtifactEntity::getSessionScopeHash,
                                value.sessionScopeHash())));
    }

    public int scrubExpired() {
        if (!properties.toolResultArtifactStoreConfigured()) {
            return 0;
        }
        List<RuntimeToolResultArtifactEntity> expired = mapper.selectList(
                Wrappers.<RuntimeToolResultArtifactEntity>lambdaQuery()
                        .select(RuntimeToolResultArtifactEntity::getId)
                        .eq(RuntimeToolResultArtifactEntity::getStatus, ACTIVE)
                        .le(RuntimeToolResultArtifactEntity::getExpiresAt, LocalDateTime.now())
                        .orderByAsc(RuntimeToolResultArtifactEntity::getExpiresAt)
                        .last("LIMIT " + properties.artifactCleanupBatchSize()));
        List<Long> ids = expired.stream()
                .map(RuntimeToolResultArtifactEntity::getId)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (ids.isEmpty()) {
            return 0;
        }
        return scrub(
                Wrappers.<RuntimeToolResultArtifactEntity>lambdaUpdate()
                        .in(RuntimeToolResultArtifactEntity::getId, ids)
                        .eq(RuntimeToolResultArtifactEntity::getStatus, ACTIVE),
                EXPIRED);
    }

    private int scrub(
            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<RuntimeToolResultArtifactEntity> update,
            String status) {
        LocalDateTime now = LocalDateTime.now();
        return mapper.update(null, update
                .set(RuntimeToolResultArtifactEntity::getContentCiphertext, null)
                .set(RuntimeToolResultArtifactEntity::getEncryptionNonce, null)
                .set(RuntimeToolResultArtifactEntity::getActiveSlot, null)
                .set(RuntimeToolResultArtifactEntity::getStatus, status)
                .set(RuntimeToolResultArtifactEntity::getDeletedAt, now)
                .set(RuntimeToolResultArtifactEntity::getUpdatedAt, now));
    }

    private int scrubById(long id, String status) {
        return scrub(Wrappers.<RuntimeToolResultArtifactEntity>lambdaUpdate()
                .eq(RuntimeToolResultArtifactEntity::getId, id)
                .eq(RuntimeToolResultArtifactEntity::getStatus, ACTIVE), status);
    }

    private RuntimeToolResultArtifactEntity findActiveSlot(
            ArtifactScope scope, String toolCallId, String contentDigest) {
        return mapper.selectOne(Wrappers.<RuntimeToolResultArtifactEntity>lambdaQuery()
                .eq(RuntimeToolResultArtifactEntity::getOwnerScopeHash, scope.ownerScopeHash())
                .eq(RuntimeToolResultArtifactEntity::getSessionScopeHash, scope.sessionScopeHash())
                .eq(RuntimeToolResultArtifactEntity::getToolCallId, toolCallId)
                .eq(RuntimeToolResultArtifactEntity::getContentHmacSha256, contentDigest)
                .eq(RuntimeToolResultArtifactEntity::getStatus, ACTIVE)
                .eq(RuntimeToolResultArtifactEntity::getActiveSlot, 1)
                .last("LIMIT 1"));
    }

    private static boolean readable(
            RuntimeToolResultArtifactEntity entity, LocalDateTime now) {
        return entity != null
                && entity.getExpiresAt() != null
                && entity.getExpiresAt().isAfter(now)
                && entity.getContentCiphertext() != null
                && entity.getEncryptionNonce() != null;
    }

    private Optional<ArtifactScope> resolveScope(RuntimeContext context) {
        if (context == null) {
            return Optional.empty();
        }
        return resolveScope(context.getUserId(), context.getSessionId());
    }

    private Optional<ArtifactScope> resolveScope(
            String stateUserKey, String stateSessionKey) {
        if (!StringUtils.hasText(stateUserKey)
                || !StringUtils.hasText(stateSessionKey)) {
            return Optional.empty();
        }
        String ownerHash = sha256("owner\n" + stateUserKey.trim());
        String sessionHash = sha256(
                "session\n" + stateUserKey.trim() + "\n" + stateSessionKey.trim());
        return Optional.of(new ArtifactScope(ownerHash, sessionHash));
    }

    private static ArtifactPointer pointer(RuntimeToolResultArtifactEntity entity) {
        return new ArtifactPointer(
                entity.getArtifactRef(),
                entity.getContentChars() == null ? 0 : entity.getContentChars(),
                entity.getExpiresAt());
    }

    private static String aad(String artifactRef, ArtifactScope scope, String contentDigest) {
        return "reachai-runtime-tool-result-v1\n" + artifactRef + "\n"
                + scope.ownerScopeHash() + "\n" + scope.sessionScopeHash() + "\n" + contentDigest;
    }

    private static boolean validArtifactRef(String value) {
        return value != null && value.matches("tra_[a-f0-9]{32}");
    }

    private static String bounded(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, maxLength);
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value))
                    .toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private record ArtifactScope(String ownerScopeHash, String sessionScopeHash) {
    }

    public record ArtifactPointer(String artifactRef, int contentChars, LocalDateTime expiresAt) {
    }

    public record ArtifactChunk(
            String artifactRef,
            int offset,
            int nextOffset,
            boolean hasMore,
            int totalChars,
            String content) {
    }
}
