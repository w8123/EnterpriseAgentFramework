package com.enterprise.ai.runtime.execution.checkpoint;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Encodes, bounds and verifies durable Workflow checkpoints. */
public final class WorkflowCheckpointCodec {

    public static final int SCHEMA_VERSION = 1;
    public static final String ENGINE_VERSION = "RUNTIME_KERNEL_V2";
    public static final int DEFAULT_MAX_CHECKPOINT_BYTES = 256 * 1024;
    public static final int HARD_MAX_CHECKPOINT_BYTES = 1024 * 1024;
    /** @deprecated use {@link #DEFAULT_MAX_CHECKPOINT_BYTES}; retained for source compatibility. */
    @Deprecated
    public static final int MAX_CHECKPOINT_BYTES = DEFAULT_MAX_CHECKPOINT_BYTES;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final Set<String> RESERVED_STATE_KEYS = Set.of(
            "__workflowExecutionIdentity",
            "__runtimeEvalExecutionContext");

    private final ObjectMapper objectMapper;
    private final int maxCheckpointBytes;

    public WorkflowCheckpointCodec(ObjectMapper objectMapper) {
        this(objectMapper, DEFAULT_MAX_CHECKPOINT_BYTES);
    }

    public WorkflowCheckpointCodec(ObjectMapper objectMapper, int maxCheckpointBytes) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper is required");
        }
        if (maxCheckpointBytes <= 0 || maxCheckpointBytes > HARD_MAX_CHECKPOINT_BYTES) {
            throw new IllegalArgumentException(
                    "Workflow checkpoint max bytes must be between 1 and "
                            + HARD_MAX_CHECKPOINT_BYTES);
        }
        this.objectMapper = objectMapper;
        this.maxCheckpointBytes = maxCheckpointBytes;
    }

    public EncodedCheckpoint encode(Map<String, Object> state,
                                    String graphSpecJson,
                                    String suspendedNodeId) {
        if (graphSpecJson == null || graphSpecJson.trim().isEmpty()) {
            throw new WorkflowCheckpointException(
                    "RUNTIME_CHECKPOINT_GRAPH_REQUIRED",
                    "GraphSpec snapshot is required to create a resumable checkpoint");
        }
        if (suspendedNodeId == null || suspendedNodeId.trim().isEmpty()) {
            throw new WorkflowCheckpointException(
                    "RUNTIME_CHECKPOINT_NODE_REQUIRED",
                    "Suspended node id is required to create a resumable checkpoint");
        }
        Map<String, Object> safeState = sanitizeMap(state == null ? Map.of() : state);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("checkpointSchemaVersion", SCHEMA_VERSION);
        envelope.put("executionEngineVersion", ENGINE_VERSION);
        envelope.put("graphDigest", digest(graphSpecJson.getBytes(StandardCharsets.UTF_8)));
        envelope.put("suspendedNodeId", suspendedNodeId.trim());
        envelope.put("createdAtEpochMs", System.currentTimeMillis());
        envelope.put("state", safeState);
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(envelope);
            ensureSize(bytes.length);
            return new EncodedCheckpoint(
                    new String(bytes, StandardCharsets.UTF_8),
                    SCHEMA_VERSION,
                    ENGINE_VERSION,
                    digest(bytes),
                    bytes.length);
        } catch (WorkflowCheckpointException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new WorkflowCheckpointException(
                    "RUNTIME_CHECKPOINT_SERIALIZATION_FAILED",
                    "Workflow checkpoint serialization failed: " + bounded(ex.getMessage()));
        }
    }

    public DecodedCheckpoint decode(String json,
                                    Integer storedSchemaVersion,
                                    String storedEngineVersion,
                                    String storedDigest,
                                    Integer storedSizeBytes,
                                    String graphSpecJson,
                                    String suspendedNodeId) {
        if (json == null || json.trim().isEmpty()) {
            return new DecodedCheckpoint(Map.of(), 0, "LEGACY", true);
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ensureSize(bytes.length);
        Map<String, Object> raw;
        try {
            raw = objectMapper.readValue(bytes, MAP_TYPE);
        } catch (Exception ex) {
            throw new WorkflowCheckpointException(
                    "RUNTIME_CHECKPOINT_INVALID",
                    "Workflow checkpoint JSON is invalid: " + bounded(ex.getMessage()));
        }
        Integer envelopeVersion = integer(raw.get("checkpointSchemaVersion"));
        boolean legacy = (storedSchemaVersion == null || storedSchemaVersion <= 0)
                && envelopeVersion == null;
        if (legacy) {
            return new DecodedCheckpoint(sanitizeMap(raw), 0, "LEGACY", true);
        }
        if (envelopeVersion == null || envelopeVersion != SCHEMA_VERSION
                || storedSchemaVersion == null || storedSchemaVersion != SCHEMA_VERSION) {
            throw new WorkflowCheckpointException(
                    "RUNTIME_CHECKPOINT_VERSION_UNSUPPORTED",
                    "Unsupported Workflow checkpoint schema version");
        }
        String engineVersion = text(raw.get("executionEngineVersion"));
        if (!ENGINE_VERSION.equals(engineVersion) || !ENGINE_VERSION.equals(storedEngineVersion)) {
            throw new WorkflowCheckpointException(
                    "RUNTIME_CHECKPOINT_ENGINE_UNSUPPORTED",
                    "Workflow checkpoint execution engine is not supported: " + bounded(engineVersion));
        }
        if (storedSizeBytes == null || storedSizeBytes != bytes.length) {
            throw new WorkflowCheckpointException(
                    "RUNTIME_CHECKPOINT_SIZE_MISMATCH",
                    "Workflow checkpoint size metadata does not match the stored payload");
        }
        String actualDigest = digest(bytes);
        if (storedDigest == null || !constantTimeEquals(storedDigest, actualDigest)) {
            throw new WorkflowCheckpointException(
                    "RUNTIME_CHECKPOINT_DIGEST_MISMATCH",
                    "Workflow checkpoint payload integrity validation failed");
        }
        String graphDigest = text(raw.get("graphDigest"));
        String expectedGraphDigest = graphSpecJson == null
                ? null
                : digest(graphSpecJson.getBytes(StandardCharsets.UTF_8));
        if (expectedGraphDigest == null || !constantTimeEquals(graphDigest, expectedGraphDigest)) {
            throw new WorkflowCheckpointException(
                    "RUNTIME_CHECKPOINT_GRAPH_MISMATCH",
                    "Workflow checkpoint does not match the persisted GraphSpec snapshot");
        }
        String envelopeNodeId = text(raw.get("suspendedNodeId"));
        if (suspendedNodeId != null && !suspendedNodeId.trim().isEmpty()
                && !suspendedNodeId.trim().equals(envelopeNodeId)) {
            throw new WorkflowCheckpointException(
                    "RUNTIME_CHECKPOINT_NODE_MISMATCH",
                    "Workflow checkpoint does not match the suspended node");
        }
        if (!(raw.get("state") instanceof Map<?, ?>)) {
            throw new WorkflowCheckpointException(
                    "RUNTIME_CHECKPOINT_STATE_INVALID",
                    "Workflow checkpoint state must be a JSON object");
        }
        Map<String, Object> state = map(raw.get("state"));
        return new DecodedCheckpoint(sanitizeMap(state), SCHEMA_VERSION, ENGINE_VERSION, false);
    }

    private Map<String, Object> sanitizeMap(Map<String, Object> source) {
        Map<String, Object> safe = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && !RESERVED_STATE_KEYS.contains(key)) {
                safe.put(key, sanitizeValue(value));
            }
        });
        return safe.isEmpty() ? Map.of() : java.util.Collections.unmodifiableMap(safe);
    }

    private Object sanitizeValue(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> nested = new LinkedHashMap<>();
            raw.forEach((key, item) -> {
                if (key != null && !RESERVED_STATE_KEYS.contains(String.valueOf(key))) {
                    nested.put(String.valueOf(key), sanitizeValue(item));
                }
            });
            return nested.isEmpty() ? Map.of() : java.util.Collections.unmodifiableMap(nested);
        }
        if (value instanceof List<?> list) {
            List<Object> nested = new ArrayList<>(list.size());
            for (Object item : list) nested.add(sanitizeValue(item));
            return java.util.Collections.unmodifiableList(nested);
        }
        return value;
    }

    private void ensureSize(int size) {
        if (size > maxCheckpointBytes) {
            throw new WorkflowCheckpointException(
                    "RUNTIME_CHECKPOINT_TOO_LARGE",
                    "Workflow checkpoint exceeds configured limit of "
                            + maxCheckpointBytes + " bytes");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key != null) result.put(String.valueOf(key), item);
        });
        return result;
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            String parsed = text(value);
            return parsed == null ? null : Integer.parseInt(parsed);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String text(Object value) {
        if (value == null) return null;
        String result = String.valueOf(value).trim();
        return result.isEmpty() ? null : result;
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) return false;
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private static String bounded(String message) {
        if (message == null) return "unknown";
        String safe = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return safe.length() <= 240 ? safe : safe.substring(0, 240);
    }

    public record EncodedCheckpoint(String json,
                                    int schemaVersion,
                                    String engineVersion,
                                    String digest,
                                    int sizeBytes) {
    }

    public record DecodedCheckpoint(Map<String, Object> state,
                                    int schemaVersion,
                                    String engineVersion,
                                    boolean legacy) {
    }
}
