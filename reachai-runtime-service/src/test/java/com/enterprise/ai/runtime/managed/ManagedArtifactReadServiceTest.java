package com.enterprise.ai.runtime.managed;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ManagedArtifactReadServiceTest {

    private final ManagedExecutionService executionService = mock(ManagedExecutionService.class);
    private final ManagedArtifactMapper artifactMapper = mock(ManagedArtifactMapper.class);
    private final ManagedArtifactStore artifactStore = mock(ManagedArtifactStore.class);
    private ManagedArtifactReadService service;

    @BeforeEach
    void setUp() {
        ManagedArtifactStoreProperties properties = new ManagedArtifactStoreProperties();
        ManagedArtifactVerifier verifier = new ManagedArtifactVerifier(
                artifactStore, properties, new ObjectMapper());
        service = new ManagedArtifactReadService(
                executionService, artifactMapper, artifactStore, verifier);
    }

    @Test
    void listsOnlyIndependentlyVerifiedCleanArtifactsBehindTenantFence() {
        ManagedArtifactEntity verified = artifact("art_patch", "PATCH", "VERIFIED", "CLEAN",
                "diff --git a/a b/a\n".getBytes(StandardCharsets.UTF_8));
        ManagedArtifactEntity rejected = artifact("art_rejected", "TEST_REPORT", "REJECTED", "CLEAN",
                "{}".getBytes(StandardCharsets.UTF_8));
        when(artifactMapper.selectList(any())).thenReturn(List.of(rejected, verified));

        var views = service.list("mex_1", "tenant-a");

        assertThat(views).extracting(view -> view.artifactId())
                .containsExactly("art_patch");
        verify(executionService).get("mex_1", "tenant-a");
    }

    @Test
    void reopensAndReverifiesArtifactBytesBeforeReturningThem() {
        byte[] bytes = "diff --git a/a b/a\n".getBytes(StandardCharsets.UTF_8);
        ManagedArtifactEntity artifact = artifact(
                "art_patch", "PATCH", "VERIFIED", "CLEAN", bytes);
        when(artifactMapper.selectOne(any())).thenReturn(artifact);
        when(artifactStore.available()).thenReturn(true);
        when(artifactStore.open("managed/mex_1/art_patch"))
                .thenReturn(new ByteArrayInputStream(bytes));

        var content = service.read("mex_1", "art_patch", "tenant-a");

        assertThat(content.filename()).isEqualTo("workspace.patch");
        assertThat(content.bytes()).isEqualTo(bytes);
        assertThat(content.metadata().sha256()).isEqualTo(sha256(bytes));
    }

    @Test
    void failsClosedWhenStoredBytesNoLongerMatchVerifiedMetadata() {
        byte[] verifiedBytes = "diff --git a/a b/a\n".getBytes(StandardCharsets.UTF_8);
        ManagedArtifactEntity artifact = artifact(
                "art_patch", "PATCH", "VERIFIED", "CLEAN", verifiedBytes);
        when(artifactMapper.selectOne(any())).thenReturn(artifact);
        when(artifactStore.available()).thenReturn(true);
        when(artifactStore.open("managed/mex_1/art_patch"))
                .thenReturn(new ByteArrayInputStream(
                        "diff --git a/tampered b/tampered\n".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> service.read("mex_1", "art_patch", "tenant-a"))
                .isInstanceOfSatisfying(ManagedExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("MANAGED_ARTIFACT_INTEGRITY_FAILED"));
    }

    @Test
    void neverOpensAnUnverifiedArtifact() {
        ManagedArtifactEntity artifact = artifact(
                "art_patch", "PATCH", "PENDING", "PENDING", new byte[0]);
        when(artifactMapper.selectOne(any())).thenReturn(artifact);

        assertThatThrownBy(() -> service.read("mex_1", "art_patch", "tenant-a"))
                .isInstanceOfSatisfying(ManagedExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("MANAGED_ARTIFACT_NOT_VERIFIED"));
        verify(artifactStore, never()).open(any());
    }

    @Test
    void neverListsOrOpensAnExpiredArtifact() {
        ManagedArtifactEntity expired = artifact(
                "art_patch", "PATCH", "VERIFIED", "CLEAN", new byte[0]);
        expired.setRetentionExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(artifactMapper.selectList(any())).thenReturn(List.of(expired));
        when(artifactMapper.selectOne(any())).thenReturn(expired);

        assertThat(service.list("mex_1", "tenant-a")).isEmpty();
        assertThatThrownBy(() -> service.read("mex_1", "art_patch", "tenant-a"))
                .isInstanceOfSatisfying(ManagedExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("MANAGED_ARTIFACT_EXPIRED"));
        verify(artifactStore, never()).open(any());
    }

    @Test
    void stopsBeforeMetadataLookupWhenTenantFenceFails() {
        when(executionService.get("mex_1", "tenant-b"))
                .thenThrow(new ManagedExecutionException(
                        404, "MANAGED_EXECUTION_NOT_FOUND", "not found"));

        assertThatThrownBy(() -> service.read("mex_1", "art_patch", "tenant-b"))
                .isInstanceOf(ManagedExecutionException.class);
        verifyNoInteractions(artifactMapper, artifactStore);
    }

    private ManagedArtifactEntity artifact(
            String artifactId,
            String type,
            String validation,
            String scan,
            byte[] bytes) {
        ManagedArtifactEntity entity = new ManagedArtifactEntity();
        entity.setArtifactId(artifactId);
        entity.setExecutionId("mex_1");
        entity.setArtifactType(type);
        entity.setObjectKey("managed/mex_1/" + artifactId);
        entity.setSha256(sha256(bytes));
        entity.setSizeBytes((long) bytes.length);
        entity.setMediaType("PATCH".equals(type) ? "text/x-diff" : "application/json");
        entity.setValidationStatus(validation);
        entity.setScanStatus(scan);
        entity.setCreatedAt(LocalDateTime.of(2026, 8, 24, 10, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 8, 24, 10, 1));
        entity.setRetentionExpiresAt(LocalDateTime.now().plusDays(30));
        return entity;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
