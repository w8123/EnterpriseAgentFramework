package com.enterprise.ai.runtime.managed;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.ArtifactView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.time.LocalDateTime;

/** Tenant-fenced, reverified reads of Runtime-owned Managed Executor evidence. */
@Service
@RequiredArgsConstructor
public class ManagedArtifactReadService {

    private static final String VIEW_SCHEMA = "reachai.managed-executor.artifact.v1";

    private final ManagedExecutionService executionService;
    private final ManagedArtifactMapper artifactMapper;
    private final ManagedArtifactStore artifactStore;
    private final ManagedArtifactVerifier verifier;

    public List<ArtifactView> list(String executionId, String tenantId) {
        executionService.get(executionId, tenantId);
        LocalDateTime now = LocalDateTime.now();
        return artifactMapper.selectList(
                        new LambdaQueryWrapper<ManagedArtifactEntity>()
                                .eq(ManagedArtifactEntity::getExecutionId, executionId)
                                .eq(ManagedArtifactEntity::getValidationStatus, "VERIFIED")
                                .eq(ManagedArtifactEntity::getScanStatus, "CLEAN")
                                .gt(ManagedArtifactEntity::getRetentionExpiresAt, now))
                .stream()
                // Defense in depth for custom mappers or stale read replicas.
                .filter(artifact -> "VERIFIED".equals(artifact.getValidationStatus())
                        && "CLEAN".equals(artifact.getScanStatus())
                        && artifact.getRetentionExpiresAt() != null
                        && artifact.getRetentionExpiresAt().isAfter(now))
                .sorted(Comparator.comparing(ManagedArtifactEntity::getArtifactType))
                .map(this::toView)
                .toList();
    }

    public ArtifactContent read(
            String executionId,
            String artifactId,
            String tenantId) {
        executionService.get(executionId, tenantId);
        String trustedArtifactId = identifier(artifactId, "artifactId", 128);
        ManagedArtifactEntity artifact = artifactMapper.selectOne(
                new LambdaQueryWrapper<ManagedArtifactEntity>()
                        .eq(ManagedArtifactEntity::getExecutionId, executionId)
                        .eq(ManagedArtifactEntity::getArtifactId, trustedArtifactId));
        if (artifact == null) {
            throw new ManagedExecutionException(404, "MANAGED_ARTIFACT_NOT_FOUND",
                    "Managed execution artifact was not found");
        }
        if (!"VERIFIED".equals(artifact.getValidationStatus())
                || !"CLEAN".equals(artifact.getScanStatus())) {
            throw new ManagedExecutionException(409, "MANAGED_ARTIFACT_NOT_VERIFIED",
                    "Managed execution artifact is not verified");
        }
        if (artifact.getRetentionExpiresAt() == null
                || !artifact.getRetentionExpiresAt().isAfter(LocalDateTime.now())) {
            throw new ManagedExecutionException(410, "MANAGED_ARTIFACT_EXPIRED",
                    "Managed execution artifact retention has expired");
        }
        if (!artifactStore.available()) {
            throw unavailable();
        }
        long declaredSize = artifact.getSizeBytes() == null ? -1L : artifact.getSizeBytes();
        long maximum = verifier.maximumBytes(artifact.getArtifactType());
        if (declaredSize < 0 || declaredSize > maximum) {
            throw new ManagedExecutionException(409, "MANAGED_ARTIFACT_METADATA_INVALID",
                    "Managed execution artifact metadata is invalid");
        }
        byte[] bytes;
        try (InputStream input = artifactStore.open(artifact.getObjectKey())) {
            bytes = readBounded(input, maximum);
        } catch (ManagedExecutionException failure) {
            throw failure;
        } catch (ManagedArtifactStoreException | IOException failure) {
            throw unavailable();
        }
        if (bytes.length != declaredSize || !sha256(bytes).equals(artifact.getSha256())) {
            throw new ManagedExecutionException(409, "MANAGED_ARTIFACT_INTEGRITY_FAILED",
                    "Managed execution artifact integrity verification failed");
        }
        return new ArtifactContent(
                toView(artifact),
                verifier.filename(artifact.getArtifactType()),
                bytes);
    }

    private ArtifactView toView(ManagedArtifactEntity artifact) {
        return new ArtifactView(
                VIEW_SCHEMA,
                artifact.getExecutionId(),
                artifact.getArtifactId(),
                artifact.getArtifactType(),
                artifact.getSha256(),
                artifact.getSizeBytes() == null ? 0L : artifact.getSizeBytes(),
                artifact.getMediaType(),
                artifact.getValidationStatus(),
                artifact.getScanStatus(),
                artifact.getCreatedAt(),
                artifact.getUpdatedAt(),
                artifact.getRetentionExpiresAt());
    }

    private byte[] readBounded(InputStream input, long maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > maximum) {
                throw new ManagedExecutionException(409, "MANAGED_ARTIFACT_INTEGRITY_FAILED",
                        "Managed execution artifact exceeds its verified size limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private String identifier(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
                || !value.matches("[A-Za-z0-9._:-]+")) {
            throw new ManagedExecutionException(400, "MANAGED_EXECUTION_REQUEST_INVALID",
                    field + " is invalid");
        }
        return value;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private ManagedExecutionException unavailable() {
        return new ManagedExecutionException(503, "MANAGED_ARTIFACT_STORE_UNAVAILABLE",
                "Managed Executor artifact store is unavailable");
    }

    public record ArtifactContent(
            ArtifactView metadata,
            String filename,
            byte[] bytes) {
    }
}
