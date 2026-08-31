package com.enterprise.ai.runtime.managed;

import com.enterprise.ai.runtime.managed.ManagedExecutionViews.ArtifactUploadView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagedArtifactUploadServiceTest {

    @Mock
    private ManagedExecutionService executionService;
    @Mock
    private ManagedArtifactMapper artifactMapper;

    private ManagedArtifactVerifierTest.InMemoryArtifactStore store;
    private ManagedArtifactUploadService uploadService;

    @BeforeEach
    void setUp() {
        store = new ManagedArtifactVerifierTest.InMemoryArtifactStore();
        ManagedArtifactStoreProperties properties = new ManagedArtifactStoreProperties();
        ManagedArtifactVerifier verifier = new ManagedArtifactVerifier(
                store, properties, new ObjectMapper());
        uploadService = new ManagedArtifactUploadService(
                executionService, artifactMapper, store, verifier, properties);
    }

    @Test
    void generatesTheObjectKeyAndVerifiesStoredBytesBeforePersistingMetadata() {
        ManagedExecutionEntity execution = execution();
        when(executionService.requireArtifactUploadLease("mex_upload", "token", "worker-1"))
                .thenReturn(execution);
        when(artifactMapper.selectOne(any())).thenReturn(null);
        when(artifactMapper.insert(any())).thenReturn(1);
        byte[] patch = "diff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n".getBytes(StandardCharsets.UTF_8);

        ArtifactUploadView uploaded = uploadService.upload(
                "mex_upload", "token", "worker-1", "patch", "artifact-patch",
                sha256(patch), "text/x-diff; charset=utf-8", patch.length,
                new ByteArrayInputStream(patch));

        assertThat(uploaded.objectKey())
                .startsWith("managed-executions/mex_upload/artifacts/artifact-patch/uploads/")
                .endsWith("/workspace.patch");
        assertThat(uploaded.validationStatus()).isEqualTo("VERIFIED");
        assertThat(uploaded.scanStatus()).isEqualTo("CLEAN");
        assertThat(store.objects.get(uploaded.objectKey())).isEqualTo(patch);
        ArgumentCaptor<ManagedArtifactEntity> persisted = ArgumentCaptor.forClass(ManagedArtifactEntity.class);
        verify(artifactMapper).insert(persisted.capture());
        assertThat(persisted.getValue().getSha256()).isEqualTo(sha256(patch));
        assertThat(persisted.getValue().getSizeBytes()).isEqualTo((long) patch.length);
        assertThat(persisted.getValue().getRetentionExpiresAt())
                .isEqualTo(persisted.getValue().getCreatedAt().plusDays(30));
    }

    @Test
    void deletesRejectedContentAndDoesNotPersistIt() {
        when(executionService.requireArtifactUploadLease("mex_upload", "token", "worker-1"))
                .thenReturn(execution());
        when(artifactMapper.selectOne(any())).thenReturn(null);
        byte[] unsafe = new byte[]{'d', 'i', 'f', 'f', 0, 'x'};

        assertThatThrownBy(() -> uploadService.upload(
                "mex_upload", "token", "worker-1", "PATCH", "artifact-patch",
                sha256(unsafe), "text/x-diff", unsafe.length, new ByteArrayInputStream(unsafe)))
                .isInstanceOfSatisfying(ManagedExecutionException.class, failure ->
                        assertThat(failure.code()).isEqualTo("MANAGED_ARTIFACT_UNSAFE_TEXT"));

        assertThat(store.objects).isEmpty();
    }

    @Test
    void aConcurrentIdempotentUploadCannotDeleteTheWinningObject() {
        when(executionService.requireArtifactUploadLease("mex_upload", "token", "worker-1"))
                .thenReturn(execution());
        byte[] patch = "diff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n"
                .getBytes(StandardCharsets.UTF_8);
        ManagedArtifactEntity winner = verifiedArtifact(
                "managed-executions/mex_upload/artifacts/artifact-patch/uploads/winner/workspace.patch",
                patch);
        store.objects.put(winner.getObjectKey(), patch);
        when(artifactMapper.selectOne(any())).thenReturn(null, winner);
        doThrow(new DuplicateKeyException("concurrent insert"))
                .when(artifactMapper).insert(any());

        ArtifactUploadView uploaded = uploadService.upload(
                "mex_upload", "token", "worker-1", "PATCH", "artifact-patch",
                sha256(patch), "text/x-diff", patch.length, new ByteArrayInputStream(patch));

        assertThat(uploaded.objectKey()).isEqualTo(winner.getObjectKey());
        assertThat(store.objects).containsOnlyKeys(winner.getObjectKey());
        assertThat(store.objects.get(winner.getObjectKey())).isEqualTo(patch);
    }

    private ManagedExecutionEntity execution() {
        ManagedExecutionEntity execution = new ManagedExecutionEntity();
        execution.setExecutionId("mex_upload");
        execution.setStatus("FINALIZING");
        return execution;
    }

    private ManagedArtifactEntity verifiedArtifact(String objectKey, byte[] content) {
        ManagedArtifactEntity artifact = new ManagedArtifactEntity();
        artifact.setArtifactId("artifact-patch");
        artifact.setExecutionId("mex_upload");
        artifact.setArtifactType("PATCH");
        artifact.setObjectKey(objectKey);
        artifact.setSha256(sha256(content));
        artifact.setSizeBytes((long) content.length);
        artifact.setMediaType("text/x-diff");
        artifact.setValidationStatus("VERIFIED");
        artifact.setScanStatus("CLEAN");
        artifact.setCreatedAt(java.time.LocalDateTime.now());
        artifact.setUpdatedAt(artifact.getCreatedAt());
        artifact.setRetentionExpiresAt(artifact.getCreatedAt().plusDays(30));
        return artifact;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
