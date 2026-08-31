package com.enterprise.ai.runtime.managed;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedArtifactVerifierTest {

    private static final String EXECUTION_ID = "mex_artifact_test";

    private InMemoryArtifactStore store;
    private ManagedArtifactVerifier verifier;

    @BeforeEach
    void setUp() {
        store = new InMemoryArtifactStore();
        verifier = new ManagedArtifactVerifier(store, new ManagedArtifactStoreProperties(), new ObjectMapper());
    }

    @Test
    void independentlyReReadsAndCrossChecksACompleteEvidenceBundle() throws Exception {
        EvidenceBundle bundle = bundle(false);

        verifier.verifyBundle(EXECUTION_ID, bundle.artifacts());
    }

    @Test
    void rejectsContentChangedAfterWorkerUpload() throws Exception {
        EvidenceBundle bundle = bundle(false);
        ManagedArtifactEntity patch = byType(bundle.artifacts(), "PATCH");
        store.objects.put(patch.getObjectKey(), "tampered".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> verifier.verifyBundle(EXECUTION_ID, bundle.artifacts()))
                .isInstanceOfSatisfying(ManagedExecutionException.class, failure ->
                        org.assertj.core.api.Assertions.assertThat(failure.code())
                                .isEqualTo("MANAGED_ARTIFACT_DIGEST_MISMATCH"));
    }

    @Test
    void rejectsAManifestThatDoesNotMatchStoredEvidenceMetadata() throws Exception {
        EvidenceBundle bundle = bundle(true);

        assertThatThrownBy(() -> verifier.verifyBundle(EXECUTION_ID, bundle.artifacts()))
                .isInstanceOfSatisfying(ManagedExecutionException.class, failure ->
                        org.assertj.core.api.Assertions.assertThat(failure.code())
                                .isEqualTo("MANAGED_ARTIFACT_MANIFEST_MISMATCH"));
    }

    @Test
    void rejectsBinaryOrMalformedEvidenceEvenWhenItsDigestMatches() {
        byte[] unsafe = new byte[]{'d', 'i', 'f', 'f', 0, 'x'};
        String key = "managed-executions/" + EXECUTION_ID + "/unsafe.patch";
        store.objects.put(key, unsafe);

        assertThatThrownBy(() -> verifier.verifyStored(
                EXECUTION_ID, "PATCH", key, sha256(unsafe), unsafe.length, "text/x-diff"))
                .isInstanceOfSatisfying(ManagedExecutionException.class, failure ->
                        org.assertj.core.api.Assertions.assertThat(failure.code())
                                .isEqualTo("MANAGED_ARTIFACT_UNSAFE_TEXT"));
    }

    private EvidenceBundle bundle(boolean corruptManifestEntry) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        LinkedHashMap<String, byte[]> content = new LinkedHashMap<>();
        content.put("PATCH", ("diff --git a/README.md b/README.md\n"
                + "--- a/README.md\n+++ b/README.md\n@@ -1 +1 @@\n-old\n+new\n")
                .getBytes(StandardCharsets.UTF_8));
        content.put("TEST_REPORT", json(mapper, Map.of(
                "schema", "reachai.managed-executor.test-report.v1",
                "executionId", EXECUTION_ID,
                "outcome", "PASSED",
                "commands", List.of())));
        content.put("EVENT_LOG", (mapper.writeValueAsString(Map.of(
                "schema", "reachai.managed-execution.event.v1",
                "executionId", EXECUTION_ID,
                "sequence", 1,
                "eventId", EXECUTION_ID + ":1",
                "type", "TURN_COMPLETED",
                "phase", "SUCCEEDED")) + "\n").getBytes(StandardCharsets.UTF_8));

        byte[] patch = content.get("PATCH");
        content.put("EXECUTION_SUMMARY", json(mapper, Map.of(
                "schema", "reachai.managed-executor.execution-summary.v1",
                "executionId", EXECUTION_ID,
                "outcome", "SUCCEEDED",
                "verificationOutcome", "PASSED",
                "readonlyViolation", false,
                "appServer", Map.of("threadId", "thr-1", "turnId", "turn-1", "status", "completed"),
                "patch", Map.of("bytes", patch.length, "sha256", sha256(patch)))));

        List<Map<String, Object>> entries = new ArrayList<>();
        for (String type : List.of("EVENT_LOG", "EXECUTION_SUMMARY", "PATCH", "TEST_REPORT")) {
            byte[] bytes = content.get(type);
            entries.add(Map.of(
                    "name", verifier.filename(type),
                    "mediaType", verifier.expectedMediaType(type),
                    "bytes", bytes.length,
                    "sha256", corruptManifestEntry && "PATCH".equals(type)
                            ? "0".repeat(64) : sha256(bytes)));
        }
        content.put("EVIDENCE_MANIFEST", json(mapper, Map.of(
                "schema", "reachai.managed-executor.evidence-manifest.v1",
                "executionId", EXECUTION_ID,
                "artifacts", entries)));

        List<ManagedArtifactEntity> artifacts = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : content.entrySet()) {
            String type = entry.getKey();
            String objectKey = "managed-executions/" + EXECUTION_ID + "/" + verifier.filename(type);
            store.objects.put(objectKey, entry.getValue());
            artifacts.add(artifact(type, objectKey, entry.getValue()));
        }
        return new EvidenceBundle(artifacts);
    }

    private ManagedArtifactEntity artifact(String type, String objectKey, byte[] content) {
        ManagedArtifactEntity artifact = new ManagedArtifactEntity();
        artifact.setArtifactId("artifact_" + type.toLowerCase());
        artifact.setExecutionId(EXECUTION_ID);
        artifact.setArtifactType(type);
        artifact.setObjectKey(objectKey);
        artifact.setSha256(sha256(content));
        artifact.setSizeBytes((long) content.length);
        artifact.setMediaType(verifier.expectedMediaType(type));
        artifact.setValidationStatus("VERIFIED");
        artifact.setScanStatus("CLEAN");
        return artifact;
    }

    private byte[] json(ObjectMapper mapper, Object value) throws IOException {
        return (mapper.writeValueAsString(value) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private ManagedArtifactEntity byType(List<ManagedArtifactEntity> artifacts, String type) {
        return artifacts.stream().filter(artifact -> type.equals(artifact.getArtifactType())).findFirst().orElseThrow();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record EvidenceBundle(List<ManagedArtifactEntity> artifacts) {
    }

    static final class InMemoryArtifactStore implements ManagedArtifactStore {
        final Map<String, byte[]> objects = new HashMap<>();

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public void put(String objectKey, InputStream content, long contentLength, String mediaType) {
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                content.transferTo(output);
                objects.put(objectKey, output.toByteArray());
            } catch (IOException failure) {
                throw new ManagedArtifactStoreException("put failed", failure);
            }
        }

        @Override
        public InputStream open(String objectKey) {
            byte[] content = objects.get(objectKey);
            if (content == null) throw new ManagedArtifactStoreException("missing object");
            return new ByteArrayInputStream(content);
        }

        @Override
        public void delete(String objectKey) {
            objects.remove(objectKey);
        }
    }
}
