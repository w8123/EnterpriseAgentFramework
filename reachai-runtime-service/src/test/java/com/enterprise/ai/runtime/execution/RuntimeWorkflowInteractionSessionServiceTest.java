package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.execution.checkpoint.WorkflowCheckpointCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RuntimeWorkflowInteractionSessionServiceTest {

    @Test
    void persistsV1CheckpointMetadataBeforePublishingRequestedEvents() {
        RuntimeInteractionSessionMapper sessionMapper = mock(RuntimeInteractionSessionMapper.class);
        RuntimeInteractionEventMapper eventMapper = mock(RuntimeInteractionEventMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        RuntimeWorkflowInteractionSessionService service =
                new RuntimeWorkflowInteractionSessionService(sessionMapper, eventMapper, objectMapper);
        String graph = """
                {"schemaVersion":2,"entryNodeId":"form","exitNodeIds":["form"],
                 "nodes":[{"id":"form","type":"INTERACTION"}]}
                """;

        RuntimeWorkflowInteractionSessionService.WaitingSession receipt = service.createWaitingSession(
                new RuntimeWorkflowInteractionSessionService.CreateRequest(
                        "wfi_v1", "WORKFLOW", "run-1", "trace-1", "wf-1", 3L,
                        null, graph, "form", "COLLECT_INPUT",
                        Map.of("lastOutput", "safe", "__workflowExecutionIdentity", Map.of("user", "forged")),
                        Map.of("component", "form"), Map.of(),
                        "app-1", "tenant-1", "chat-1", "user-1", 60));

        ArgumentCaptor<RuntimeInteractionSessionEntity> sessionCaptor =
                ArgumentCaptor.forClass(RuntimeInteractionSessionEntity.class);
        verify(sessionMapper).insert(sessionCaptor.capture());
        RuntimeInteractionSessionEntity created = sessionCaptor.getValue();
        assertEquals("wfi_v1", receipt.interactionId());
        assertEquals(created.getUiRequestJson(), receipt.uiRequestJson());
        assertEquals(WorkflowCheckpointCodec.SCHEMA_VERSION, created.getCheckpointSchemaVersion());
        assertEquals(WorkflowCheckpointCodec.ENGINE_VERSION, created.getExecutionEngineVersion());
        assertEquals(64, created.getCheckpointDigest().length());
        assertEquals(created.getResumeCheckpointJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                created.getCheckpointSizeBytes());
        assertFalse(created.getResumeCheckpointJson().contains("__workflowExecutionIdentity"));
        assertNotNull(service.decodeCheckpoint(created, graph).state().get("lastOutput"));

        assertEquals("wfi_v1", sessionCaptor.getValue().getId());
        verify(eventMapper, times(2)).insert(org.mockito.ArgumentMatchers.any());
    }
}
