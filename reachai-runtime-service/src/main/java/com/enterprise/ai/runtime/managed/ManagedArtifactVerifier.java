package com.enterprise.ai.runtime.managed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class ManagedArtifactVerifier {

    public static final Set<String> REQUIRED_TYPES = Set.of(
            "PATCH", "TEST_REPORT", "EXECUTION_SUMMARY", "EVIDENCE_MANIFEST", "EVENT_LOG");

    private static final Map<String, String> MEDIA_TYPES = Map.of(
            "PATCH", "text/x-diff",
            "TEST_REPORT", "application/json",
            "EXECUTION_SUMMARY", "application/json",
            "EVIDENCE_MANIFEST", "application/json",
            "EVENT_LOG", "application/x-ndjson");

    private static final Map<String, String> FILENAMES = Map.of(
            "PATCH", "workspace.patch",
            "TEST_REPORT", "test-report.json",
            "EXECUTION_SUMMARY", "execution-summary.json",
            "EVIDENCE_MANIFEST", "evidence-manifest.json",
            "EVENT_LOG", "events.ndjson");

    private final ManagedArtifactStore artifactStore;
    private final ManagedArtifactStoreProperties properties;
    private final ObjectMapper objectMapper;

    public VerificationResult verifyStored(String executionId,
                                           String artifactType,
                                           String objectKey,
                                           String expectedSha256,
                                           long expectedSize,
                                           String declaredMediaType) {
        String type = normalizedType(artifactType);
        String mediaType = normalizeMediaType(declaredMediaType);
        if (!MEDIA_TYPES.get(type).equals(mediaType)) {
            throw rejected("MANAGED_ARTIFACT_MEDIA_TYPE_REJECTED");
        }
        long maximumBytes = maximumBytes(type);
        if (expectedSize < 0 || expectedSize > maximumBytes) {
            throw new ManagedExecutionException(413, "MANAGED_ARTIFACT_TOO_LARGE",
                    "Managed execution artifact exceeds the size limit");
        }

        byte[] content = readBounded(objectKey, maximumBytes);
        if (content.length != expectedSize || !sha256(content).equals(expectedSha256)) {
            throw rejected("MANAGED_ARTIFACT_DIGEST_MISMATCH");
        }
        String text = decodeUtf8(content);
        JsonNode json = validateContent(executionId, type, text);
        return new VerificationResult(content.length, expectedSha256, mediaType, json);
    }

    public void verifyBundle(String executionId, List<ManagedArtifactEntity> artifacts) {
        Map<String, ManagedArtifactEntity> byType = new HashMap<>();
        for (ManagedArtifactEntity artifact : artifacts) {
            if (artifact != null) byType.put(artifact.getArtifactType(), artifact);
        }
        if (!byType.keySet().containsAll(REQUIRED_TYPES)) {
            throw rejected("MANAGED_ARTIFACT_BUNDLE_INCOMPLETE");
        }

        Map<String, VerificationResult> verified = new HashMap<>();
        for (String type : REQUIRED_TYPES) {
            ManagedArtifactEntity artifact = byType.get(type);
            if (!"VERIFIED".equals(artifact.getValidationStatus())
                    || !"CLEAN".equals(artifact.getScanStatus())) {
                throw rejected("MANAGED_ARTIFACT_NOT_VERIFIED");
            }
            verified.put(type, verifyStored(
                    executionId,
                    type,
                    artifact.getObjectKey(),
                    artifact.getSha256(),
                    artifact.getSizeBytes(),
                    artifact.getMediaType()));
        }

        JsonNode summary = verified.get("EXECUTION_SUMMARY").json();
        JsonNode report = verified.get("TEST_REPORT").json();
        JsonNode manifest = verified.get("EVIDENCE_MANIFEST").json();
        ManagedArtifactEntity patch = byType.get("PATCH");
        if (!"SUCCEEDED".equals(text(summary, "outcome"))
                || summary.path("readonlyViolation").asBoolean(true)
                || !Set.of("PASSED", "NOT_RUN").contains(text(summary, "verificationOutcome"))
                || !text(summary, "verificationOutcome").equals(text(report, "outcome"))
                || summary.path("patch").isMissingNode()
                || summary.path("patch").isNull()
                || summary.path("patch").path("bytes").asLong(-1) != patch.getSizeBytes()
                || !patch.getSha256().equals(text(summary.path("patch"), "sha256"))) {
            throw rejected("MANAGED_ARTIFACT_SUMMARY_MISMATCH");
        }

        validateManifestBundle(manifest, byType);
    }

    public long maximumBytes(String artifactType) {
        return switch (normalizedType(artifactType)) {
            case "PATCH" -> bounded(properties.getMaxPatchBytes(), 1_024, 64L * 1024L * 1024L);
            case "EVENT_LOG" -> bounded(properties.getMaxEventLogBytes(), 1_024, 64L * 1024L * 1024L);
            default -> bounded(properties.getMaxJsonBytes(), 1_024, 16L * 1024L * 1024L);
        };
    }

    public String expectedMediaType(String artifactType) {
        return MEDIA_TYPES.get(normalizedType(artifactType));
    }

    public String filename(String artifactType) {
        return FILENAMES.get(normalizedType(artifactType));
    }

    private JsonNode validateContent(String executionId, String type, String content) {
        rejectUnsafeText(content);
        if ("PATCH".equals(type)) {
            if (!content.isEmpty() && !content.startsWith("diff --git ")) {
                throw rejected("MANAGED_ARTIFACT_PATCH_INVALID");
            }
            return null;
        }
        if ("EVENT_LOG".equals(type)) {
            validateEventLog(executionId, content);
            return null;
        }
        JsonNode root = parseJson(content);
        if (!root.isObject()) throw rejected("MANAGED_ARTIFACT_JSON_INVALID");
        String expectedSchema = switch (type) {
            case "TEST_REPORT" -> "reachai.managed-executor.test-report.v1";
            case "EXECUTION_SUMMARY" -> "reachai.managed-executor.execution-summary.v1";
            case "EVIDENCE_MANIFEST" -> "reachai.managed-executor.evidence-manifest.v1";
            default -> throw rejected("MANAGED_ARTIFACT_TYPE_REJECTED");
        };
        if (!expectedSchema.equals(text(root, "schema"))
                || !executionId.equals(text(root, "executionId"))) {
            throw rejected("MANAGED_ARTIFACT_SCHEMA_REJECTED");
        }
        if ("TEST_REPORT".equals(type)) validateTestReport(root);
        if ("EXECUTION_SUMMARY".equals(type)) validateExecutionSummary(root);
        if ("EVIDENCE_MANIFEST".equals(type)) validateManifest(root);
        return root;
    }

    private void validateTestReport(JsonNode root) {
        if (!Set.of("PASSED", "FAILED", "NOT_RUN").contains(text(root, "outcome"))
                || !root.path("commands").isArray()
                || root.path("commands").size() > 100) {
            throw rejected("MANAGED_ARTIFACT_TEST_REPORT_INVALID");
        }
    }

    private void validateExecutionSummary(JsonNode root) {
        if (!Set.of("SUCCEEDED", "FAILED").contains(text(root, "outcome"))
                || !Set.of("PASSED", "FAILED", "NOT_RUN").contains(text(root, "verificationOutcome"))
                || !root.path("readonlyViolation").isBoolean()
                || !root.path("appServer").isObject()) {
            throw rejected("MANAGED_ARTIFACT_SUMMARY_INVALID");
        }
    }

    private void validateManifest(JsonNode root) {
        JsonNode entries = root.path("artifacts");
        if (!entries.isArray() || entries.isEmpty() || entries.size() > 20) {
            throw rejected("MANAGED_ARTIFACT_MANIFEST_INVALID");
        }
        Set<String> names = new HashSet<>();
        for (JsonNode entry : entries) {
            String name = text(entry, "name");
            if (!FILENAMES.containsValue(name) || !names.add(name)
                    || entry.path("bytes").asLong(-1) < 0
                    || !text(entry, "sha256").matches("[a-f0-9]{64}")) {
                throw rejected("MANAGED_ARTIFACT_MANIFEST_INVALID");
            }
        }
    }

    private void validateManifestBundle(JsonNode manifest, Map<String, ManagedArtifactEntity> byType) {
        Map<String, JsonNode> entries = new HashMap<>();
        for (JsonNode entry : manifest.path("artifacts")) entries.put(text(entry, "name"), entry);
        Set<String> listedTypes = Set.of("PATCH", "TEST_REPORT", "EXECUTION_SUMMARY", "EVENT_LOG");
        if (entries.size() != listedTypes.size()) {
            throw rejected("MANAGED_ARTIFACT_MANIFEST_MISMATCH");
        }
        for (String type : listedTypes) {
            ManagedArtifactEntity artifact = byType.get(type);
            JsonNode entry = entries.get(FILENAMES.get(type));
            if (entry == null
                    || artifact.getSizeBytes() != entry.path("bytes").asLong(-1)
                    || !artifact.getSha256().equals(text(entry, "sha256"))
                    || !artifact.getMediaType().equals(normalizeMediaType(text(entry, "mediaType")))) {
                throw rejected("MANAGED_ARTIFACT_MANIFEST_MISMATCH");
            }
        }
    }

    private void validateEventLog(String executionId, String content) {
        String[] lines = content.split("\\R");
        int expectedSequence = 1;
        boolean completed = false;
        for (String line : lines) {
            if (line.isBlank()) continue;
            if (expectedSequence > 100_000) throw rejected("MANAGED_ARTIFACT_EVENT_LOG_INVALID");
            JsonNode event = parseJson(line);
            if (!event.isObject()
                    || !"reachai.managed-execution.event.v1".equals(text(event, "schema"))
                    || !executionId.equals(text(event, "executionId"))
                    || event.path("sequence").asInt(-1) != expectedSequence
                    || !(executionId + ":" + expectedSequence).equals(text(event, "eventId"))) {
                throw rejected("MANAGED_ARTIFACT_EVENT_LOG_INVALID");
            }
            completed |= "TURN_COMPLETED".equals(text(event, "type"))
                    && "SUCCEEDED".equals(text(event, "phase"));
            expectedSequence++;
        }
        if (expectedSequence == 1 || !completed) throw rejected("MANAGED_ARTIFACT_EVENT_LOG_INVALID");
    }

    private byte[] readBounded(String objectKey, long maximumBytes) {
        try (InputStream input = artifactStore.open(objectKey);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > maximumBytes) {
                    throw new ManagedExecutionException(413, "MANAGED_ARTIFACT_TOO_LARGE",
                            "Managed execution artifact exceeds the size limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (ManagedExecutionException failure) {
            throw failure;
        } catch (IOException | ManagedArtifactStoreException failure) {
            throw rejected("MANAGED_ARTIFACT_STORAGE_READ_FAILED");
        }
    }

    private String decodeUtf8(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw rejected("MANAGED_ARTIFACT_UTF8_REQUIRED");
        }
    }

    private JsonNode parseJson(String content) {
        try {
            return objectMapper.readTree(content);
        } catch (IOException failure) {
            throw rejected("MANAGED_ARTIFACT_JSON_INVALID");
        }
    }

    private void rejectUnsafeText(String content) {
        for (int index = 0; index < content.length(); index++) {
            char character = content.charAt(index);
            if (character == 0 || (character < 0x20 && character != '\n' && character != '\r' && character != '\t')) {
                throw rejected("MANAGED_ARTIFACT_UNSAFE_TEXT");
            }
        }
    }

    private String normalizedType(String value) {
        if (value == null) throw rejected("MANAGED_ARTIFACT_TYPE_REJECTED");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!MEDIA_TYPES.containsKey(normalized)) throw rejected("MANAGED_ARTIFACT_TYPE_REJECTED");
        return normalized;
    }

    private String normalizeMediaType(String value) {
        if (value == null) return "";
        int delimiter = value.indexOf(';');
        return (delimiter >= 0 ? value.substring(0, delimiter) : value).trim().toLowerCase(Locale.ROOT);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.textValue() : "";
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private long bounded(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private ManagedExecutionException rejected(String code) {
        return new ManagedExecutionException(422, code, "Managed execution artifact verification failed");
    }

    public record VerificationResult(long sizeBytes, String sha256, String mediaType, JsonNode json) {
    }
}
