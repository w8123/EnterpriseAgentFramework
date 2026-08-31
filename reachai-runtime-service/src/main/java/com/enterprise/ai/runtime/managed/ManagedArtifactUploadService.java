package com.enterprise.ai.runtime.managed;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.ArtifactUploadView;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ManagedArtifactUploadService {

    private final ManagedExecutionService executionService;
    private final ManagedArtifactMapper artifactMapper;
    private final ManagedArtifactStore artifactStore;
    private final ManagedArtifactVerifier verifier;
    private final ManagedArtifactStoreProperties artifactStoreProperties;

    @Transactional
    public ArtifactUploadView upload(String executionId,
                                     String workerToken,
                                     String workerId,
                                     String artifactType,
                                     String artifactId,
                                     String declaredSha256,
                                     String contentType,
                                     long contentLength,
                                     InputStream content) {
        ManagedExecutionEntity execution = executionService.requireArtifactUploadLease(
                executionId, workerToken, workerId);
        requireStore();
        String type = normalizedType(artifactType);
        String trustedArtifactId = identifier(artifactId, "artifactId", 128);
        String sha256 = digest(declaredSha256);
        String mediaType = normalizeMediaType(contentType);
        if (!verifier.expectedMediaType(type).equals(mediaType)) {
            throw invalid("Managed execution artifact media type is invalid");
        }
        long maximumBytes = verifier.maximumBytes(type);
        if (contentLength < 0) {
            throw new ManagedExecutionException(411, "MANAGED_ARTIFACT_LENGTH_REQUIRED",
                    "Managed execution artifact Content-Length is required");
        }
        if (contentLength > maximumBytes) {
            throw new ManagedExecutionException(413, "MANAGED_ARTIFACT_TOO_LARGE",
                    "Managed execution artifact exceeds the size limit");
        }
        String objectKey = "managed-executions/" + execution.getExecutionId()
                + "/artifacts/" + trustedArtifactId + "/uploads/" + UUID.randomUUID()
                + "/" + verifier.filename(type);

        ManagedArtifactEntity existing = artifactMapper.selectOne(
                new LambdaQueryWrapper<ManagedArtifactEntity>()
                        .eq(ManagedArtifactEntity::getExecutionId, execution.getExecutionId())
                        .eq(ManagedArtifactEntity::getArtifactType, type));
        if (existing != null) {
            if (!trustedArtifactId.equals(existing.getArtifactId())
                    || !sha256.equals(existing.getSha256())
                    || existing.getSizeBytes() == null
                    || contentLength != existing.getSizeBytes()
                    || !mediaType.equals(existing.getMediaType())
                    || !"VERIFIED".equals(existing.getValidationStatus())
                    || !"CLEAN".equals(existing.getScanStatus())) {
                throw conflict("MANAGED_ARTIFACT_CONFLICT",
                        "Managed execution artifact upload conflicts with existing evidence");
            }
            return toView(existing);
        }

        boolean stored = false;
        try {
            CountingBoundedInputStream bounded = new CountingBoundedInputStream(content, maximumBytes);
            artifactStore.put(objectKey, bounded, contentLength, mediaType);
            stored = true;
            if (bounded.count() != contentLength) {
                throw invalid("Managed execution artifact length does not match Content-Length");
            }
            ManagedArtifactVerifier.VerificationResult verified = verifier.verifyStored(
                    execution.getExecutionId(), type, objectKey, sha256, contentLength, mediaType);
            ManagedArtifactEntity artifact = new ManagedArtifactEntity();
            artifact.setArtifactId(trustedArtifactId);
            artifact.setExecutionId(execution.getExecutionId());
            artifact.setArtifactType(type);
            artifact.setObjectKey(objectKey);
            artifact.setSha256(verified.sha256());
            artifact.setSizeBytes(verified.sizeBytes());
            artifact.setMediaType(verified.mediaType());
            artifact.setValidationStatus("VERIFIED");
            artifact.setScanStatus("CLEAN");
            artifact.setCreatedAt(LocalDateTime.now());
            artifact.setRetentionExpiresAt(artifact.getCreatedAt().plusDays(
                    Math.max(1, Math.min(365, artifactStoreProperties.getRetentionDays()))));
            artifact.setUpdatedAt(artifact.getCreatedAt());
            artifactMapper.insert(artifact);
            return toView(artifact);
        } catch (DuplicateKeyException concurrentUpload) {
            if (stored) deleteQuietly(objectKey);
            ManagedArtifactEntity winner = artifactMapper.selectOne(
                    new LambdaQueryWrapper<ManagedArtifactEntity>()
                            .eq(ManagedArtifactEntity::getExecutionId, execution.getExecutionId())
                            .eq(ManagedArtifactEntity::getArtifactType, type));
            if (sameVerifiedArtifact(
                    winner, trustedArtifactId, sha256, contentLength, mediaType)) {
                return toView(winner);
            }
            throw conflict("MANAGED_ARTIFACT_CONFLICT",
                    "Managed execution artifact upload conflicts with existing evidence");
        } catch (ManagedExecutionException failure) {
            if (stored) deleteQuietly(objectKey);
            throw failure;
        } catch (ManagedArtifactStoreException failure) {
            if (stored) deleteQuietly(objectKey);
            throw new ManagedExecutionException(503, "MANAGED_ARTIFACT_STORE_UNAVAILABLE",
                    "Managed Executor artifact store is unavailable");
        } catch (RuntimeException failure) {
            if (stored) deleteQuietly(objectKey);
            throw failure;
        }
    }

    private void requireStore() {
        if (!artifactStore.available()) {
            throw new ManagedExecutionException(503, "MANAGED_ARTIFACT_STORE_UNAVAILABLE",
                    "Managed Executor artifact store is unavailable");
        }
    }

    private ArtifactUploadView toView(ManagedArtifactEntity artifact) {
        return new ArtifactUploadView(
                "reachai.managed-executor.artifact-upload.v1",
                artifact.getExecutionId(),
                artifact.getArtifactId(),
                artifact.getArtifactType(),
                artifact.getObjectKey(),
                artifact.getSha256(),
                artifact.getSizeBytes(),
                artifact.getMediaType(),
                artifact.getValidationStatus(),
                artifact.getScanStatus());
    }

    private boolean sameVerifiedArtifact(ManagedArtifactEntity artifact,
                                         String artifactId,
                                         String sha256,
                                         long sizeBytes,
                                         String mediaType) {
        return artifact != null
                && artifactId.equals(artifact.getArtifactId())
                && sha256.equals(artifact.getSha256())
                && artifact.getSizeBytes() != null
                && sizeBytes == artifact.getSizeBytes()
                && mediaType.equals(artifact.getMediaType())
                && "VERIFIED".equals(artifact.getValidationStatus())
                && "CLEAN".equals(artifact.getScanStatus());
    }

    private String normalizedType(String value) {
        if (value == null) throw invalid("Managed execution artifact type is required");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!ManagedArtifactVerifier.REQUIRED_TYPES.contains(normalized)) {
            throw invalid("Managed execution artifact type is invalid");
        }
        return normalized;
    }

    private String normalizeMediaType(String value) {
        if (value == null) return "";
        int delimiter = value.indexOf(';');
        return (delimiter < 0 ? value : value.substring(0, delimiter)).trim().toLowerCase(Locale.ROOT);
    }

    private String identifier(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
                || !value.matches("[A-Za-z0-9._:-]+")) {
            throw invalid(field + " is invalid");
        }
        return value;
    }

    private String digest(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) {
            throw invalid("Managed execution artifact SHA-256 is invalid");
        }
        return value;
    }

    private void deleteQuietly(String objectKey) {
        try {
            artifactStore.delete(objectKey);
        } catch (RuntimeException ignored) {
            // The original verification/storage failure remains authoritative.
        }
    }

    private ManagedExecutionException invalid(String message) {
        return new ManagedExecutionException(400, "MANAGED_EXECUTION_REQUEST_INVALID", message);
    }

    private ManagedExecutionException conflict(String code, String message) {
        return new ManagedExecutionException(409, code, message);
    }

    private static final class CountingBoundedInputStream extends FilterInputStream {
        private final long maximumBytes;
        private long count;

        private CountingBoundedInputStream(InputStream input, long maximumBytes) {
            super(input);
            this.maximumBytes = maximumBytes;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) increment(1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) increment(read);
            return read;
        }

        private void increment(long amount) throws IOException {
            count += amount;
            if (count > maximumBytes) throw new IOException("Managed artifact exceeds the byte limit");
        }

        private long count() {
            return count;
        }
    }
}
