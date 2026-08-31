package com.enterprise.ai.runtime.execution.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkflowCheckpointCodecTest {

    private static final String GRAPH = """
            {"schemaVersion":2,"entryNodeId":"form","exitNodeIds":["form"],
             "nodes":[{"id":"form","type":"INTERACTION"}]}
            """;

    private final WorkflowCheckpointCodec codec = new WorkflowCheckpointCodec(new ObjectMapper());

    @Test
    void roundTripsV1AndRemovesTrustedRuntimeMarkersRecursively() {
        WorkflowCheckpointCodec.EncodedCheckpoint encoded = codec.encode(Map.of(
                "runId", "run-1",
                "__workflowExecutionIdentity", Map.of("userId", "forged"),
                "nested", Map.of(
                        "value", 7,
                        "__runtimeEvalExecutionContext", Map.of("mode", "unsafe"))), GRAPH, "form");

        WorkflowCheckpointCodec.DecodedCheckpoint decoded = codec.decode(
                encoded.json(), encoded.schemaVersion(), encoded.engineVersion(), encoded.digest(),
                encoded.sizeBytes(), GRAPH, "form");

        assertFalse(decoded.legacy());
        assertEquals("run-1", decoded.state().get("runId"));
        assertFalse(decoded.state().containsKey("__workflowExecutionIdentity"));
        Map<?, ?> nested = (Map<?, ?>) decoded.state().get("nested");
        assertEquals(7, nested.get("value"));
        assertFalse(nested.containsKey("__runtimeEvalExecutionContext"));
    }

    @Test
    void rejectsPayloadTamperingAndGraphDrift() {
        WorkflowCheckpointCodec.EncodedCheckpoint encoded = codec.encode(
                Map.of("value", "A"), GRAPH, "form");
        String tampered = encoded.json().replace("\"A\"", "\"B\"");
        WorkflowCheckpointException digestFailure = assertThrows(WorkflowCheckpointException.class,
                () -> codec.decode(tampered, 1, WorkflowCheckpointCodec.ENGINE_VERSION,
                        encoded.digest(), tampered.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                        GRAPH, "form"));
        assertEquals("RUNTIME_CHECKPOINT_DIGEST_MISMATCH", digestFailure.code());

        WorkflowCheckpointException graphFailure = assertThrows(WorkflowCheckpointException.class,
                () -> codec.decode(encoded.json(), 1, WorkflowCheckpointCodec.ENGINE_VERSION,
                        encoded.digest(), encoded.sizeBytes(), GRAPH + " ", "form"));
        assertEquals("RUNTIME_CHECKPOINT_GRAPH_MISMATCH", graphFailure.code());
    }

    @Test
    void supportsLegacyMapButRejectsOversizedV1() {
        WorkflowCheckpointCodec.DecodedCheckpoint legacy = codec.decode(
                "{\"runId\":\"legacy\",\"__workflowExecutionIdentity\":{\"forged\":true}}",
                0, null, null, null, GRAPH, "form");
        assertEquals(true, legacy.legacy());
        assertEquals(Map.of("runId", "legacy"), legacy.state());

        String oversized = "x".repeat(WorkflowCheckpointCodec.DEFAULT_MAX_CHECKPOINT_BYTES);
        WorkflowCheckpointException failure = assertThrows(WorkflowCheckpointException.class,
                () -> codec.encode(Map.of("payload", oversized), GRAPH, "form"));
        assertEquals("RUNTIME_CHECKPOINT_TOO_LARGE", failure.code());
    }

    @Test
    void supportsBoundedConfigurationButRejectsValuesAboveHardCeiling() {
        WorkflowCheckpointCodec small = new WorkflowCheckpointCodec(new ObjectMapper(), 1024);
        WorkflowCheckpointException tooLarge = assertThrows(WorkflowCheckpointException.class,
                () -> small.encode(Map.of("payload", "x".repeat(1024)), GRAPH, "form"));
        assertEquals("RUNTIME_CHECKPOINT_TOO_LARGE", tooLarge.code());

        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowCheckpointCodec(new ObjectMapper(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowCheckpointCodec(new ObjectMapper(),
                        WorkflowCheckpointCodec.HARD_MAX_CHECKPOINT_BYTES + 1));
    }

    @Test
    void rejectsIncompleteOrMismatchedV1Metadata() throws Exception {
        WorkflowCheckpointException missingNode = assertThrows(WorkflowCheckpointException.class,
                () -> codec.encode(Map.of(), GRAPH, " "));
        assertEquals("RUNTIME_CHECKPOINT_NODE_REQUIRED", missingNode.code());

        WorkflowCheckpointCodec.EncodedCheckpoint encoded = codec.encode(
                Map.of("value", "A"), GRAPH, "form");
        WorkflowCheckpointException nodeMismatch = assertThrows(WorkflowCheckpointException.class,
                () -> codec.decode(encoded.json(), encoded.schemaVersion(), encoded.engineVersion(),
                        encoded.digest(), encoded.sizeBytes(), GRAPH, "other"));
        assertEquals("RUNTIME_CHECKPOINT_NODE_MISMATCH", nodeMismatch.code());

        @SuppressWarnings("unchecked")
        Map<String, Object> envelope = new ObjectMapper().readValue(encoded.json(), Map.class);
        envelope.put("state", "not-an-object");
        byte[] invalidBytes = new ObjectMapper().writeValueAsBytes(envelope);
        WorkflowCheckpointException invalidState = assertThrows(WorkflowCheckpointException.class,
                () -> codec.decode(new String(invalidBytes, java.nio.charset.StandardCharsets.UTF_8),
                        encoded.schemaVersion(), encoded.engineVersion(), sha256(invalidBytes),
                        invalidBytes.length, GRAPH, "form"));
        assertEquals("RUNTIME_CHECKPOINT_STATE_INVALID", invalidState.code());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
