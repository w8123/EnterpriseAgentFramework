package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeInteractionResumeServiceTest {

    private final RuntimeInteractionSessionMapper sessionMapper = mock(RuntimeInteractionSessionMapper.class);
    private final RuntimeInteractionEventMapper eventMapper = mock(RuntimeInteractionEventMapper.class);
    private final CapturingCapabilityClient capabilityClient = new CapturingCapabilityClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuntimeGraphSpecExecutor graphSpecExecutor = new RuntimeGraphSpecExecutor(
            objectMapper,
            request -> new RuntimeModelServiceClient.ModelChatResult(0, "ok",
                    new RuntimeModelServiceClient.ModelChatData("model answer", "model-1", "openai", null, null, null,
                            "stop")),
            capabilityClient);
    private final RuntimeInteractionResumeService service = new RuntimeInteractionResumeService(
            sessionMapper,
            eventMapper,
            capabilityClient,
            graphSpecExecutor,
            objectMapper);

    @Test
    void resumesWaitingInteractionSessionAndCompletesItAfterGraphExecution() {
        RuntimeInteractionSessionEntity session = waitingSession();
        when(sessionMapper.selectById("session-1")).thenReturn(session);
        capabilityClient.composition = Map.of(
                "qualifiedName", "orders.confirmFlow",
                "capabilityCode", "orders",
                "compositionCode", "confirmFlow",
                "enabled", true,
                "graphSpecJson", """
                        {
                          "entry":"confirm",
                          "nodes":[
                            {"id":"confirm","type":"INTERACTION"},
                            {"id":"answer","type":"ANSWER","config":{"template":"确认：{{ approved }}"}}
                          ],
                          "edges":[{"from":"confirm","to":"answer"}]
                        }
                        """);

        Map<String, Object> result = service.resume("session-1",
                Map.of("values", Map.of("approved", true), "operatorId", "u1"));

        assertEquals(true, result.get("success"));
        assertEquals("RUNTIME_GRAPH_EXECUTED", result.get("code"));
        assertEquals("确认：true", result.get("answer"));
        assertEquals(List.of("orders.confirmFlow"), capabilityClient.compositionLookups);

        ArgumentCaptor<RuntimeInteractionSessionEntity> sessionUpdateCaptor =
                ArgumentCaptor.forClass(RuntimeInteractionSessionEntity.class);
        verify(sessionMapper, org.mockito.Mockito.times(2)).updateById(sessionUpdateCaptor.capture());
        assertEquals("SUBMITTED", sessionUpdateCaptor.getAllValues().get(0).getStatus());
        assertEquals("COMPLETED", sessionUpdateCaptor.getAllValues().get(1).getStatus());

        ArgumentCaptor<RuntimeInteractionEventEntity> eventCaptor =
                ArgumentCaptor.forClass(RuntimeInteractionEventEntity.class);
        verify(eventMapper, org.mockito.Mockito.times(2)).insert(eventCaptor.capture());
        assertEquals("SUBMITTED", eventCaptor.getAllValues().get(0).getEventType());
        assertEquals("u1", eventCaptor.getAllValues().get(0).getOperatorId());
        assertEquals("COMPLETED", eventCaptor.getAllValues().get(1).getEventType());
    }

    @Test
    void rejectsSessionThatIsNotWaiting() {
        RuntimeInteractionSessionEntity session = waitingSession();
        session.setStatus("COMPLETED");
        when(sessionMapper.selectById("session-1")).thenReturn(session);

        Map<String, Object> result = service.resume("session-1", Map.of("values", Map.of("approved", true)));

        assertEquals(false, result.get("success"));
        assertEquals("RUNTIME_INTERACTION_NOT_WAITING", result.get("code"));
        verify(sessionMapper, org.mockito.Mockito.never()).updateById(any());
    }

    private RuntimeInteractionSessionEntity waitingSession() {
        RuntimeInteractionSessionEntity session = new RuntimeInteractionSessionEntity();
        session.setId("session-1");
        session.setRunId("run-1");
        session.setCompositionQualifiedName("orders.confirmFlow");
        session.setNodeId("confirm");
        session.setInteractionType("CONFIRM_ACTION");
        session.setStatus("WAITING_USER");
        session.setStateJson("{\"input\":\"hello\"}");
        session.setUiRequestJson("{\"type\":\"CONFIRM_ACTION\"}");
        session.setCreateTime(LocalDateTime.of(2026, 7, 1, 1, 0));
        session.setUpdateTime(LocalDateTime.of(2026, 7, 1, 1, 0));
        session.setExpiresAt(LocalDateTime.of(2026, 7, 2, 1, 0));
        return session;
    }

    private static final class CapturingCapabilityClient implements RuntimeCapabilityCatalogClient {
        private final java.util.ArrayList<String> compositionLookups = new java.util.ArrayList<>();
        private Map<String, Object> composition = Map.of();

        @Override
        public Map<String, Object> getToolDefinition(String qualifiedName) {
            return Map.of();
        }

        @Override
        public Map<String, Object> executeTool(String qualifiedName, Map<String, Object> request) {
            return Map.of("success", true, "data", Map.of());
        }

        @Override
        public Map<String, Object> getCompositionDefinition(String qualifiedName) {
            compositionLookups.add(qualifiedName);
            return composition;
        }

        @Override
        public Map<String, Object> getProject(String projectCode) {
            return Map.of();
        }

        @Override
        public Map<String, Object> getProjectById(Long projectId) {
            return Map.of();
        }

        @Override
        public List<Map<String, Object>> listRuntimeInstances() {
            return List.of();
        }
    }
}
