package com.enterprise.ai.runtime.execution;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeInteractionResumeServiceTest {

    private final RuntimeInteractionSessionMapper sessionMapper = mock(RuntimeInteractionSessionMapper.class);
    private final RuntimeInteractionEventMapper eventMapper = mock(RuntimeInteractionEventMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuntimeGraphSpecExecutor graphSpecExecutor = new RuntimeGraphSpecExecutor(
            objectMapper,
            request -> new RuntimeModelServiceClient.ModelChatResult(0, "ok",
                    new RuntimeModelServiceClient.ModelChatData("model answer", "model-1", "openai", null, null, null,
                            "stop")),
            mock(RuntimeCapabilityCatalogClient.class),
            mock(com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient.class));
    private RuntimeWorkflowInteractionSessionService sessionService;
    private RuntimeInteractionExpiryProcessor expiryProcessor;
    private RuntimeInteractionResumeService service;

    @BeforeEach
    void setUp() {
        sessionService = new RuntimeWorkflowInteractionSessionService(sessionMapper, eventMapper, objectMapper);
        expiryProcessor = mock(RuntimeInteractionExpiryProcessor.class);
        when(expiryProcessor.expireOne(any(), any())).thenReturn(true);
        service = new RuntimeInteractionResumeService(
                sessionMapper,
                sessionService,
                mock(RuntimeCapabilityCatalogClient.class),
                graphSpecExecutor,
                objectMapper,
                expiryProcessor);
    }

    @Test
    void resumesFromGraphSpecSnapshotNotLatestWorkflow() {
        RuntimeInteractionSessionEntity session = waitingSession();
        when(sessionMapper.selectById("wfi_session1")).thenReturn(session);
        when(sessionMapper.update(any(), any())).thenReturn(1);

        Map<String, Object> result = service.resume("wfi_session1", Map.of(
                "values", Map.of("q", "hello"),
                "idempotencyKey", "idem-1"), owner());

        assertEquals(true, result.get("success"));
        assertEquals("RUNTIME_GRAPH_EXECUTED", result.get("code"));
        assertEquals("got:hello", result.get("answer"));
        assertEquals("COMPLETED", result.get("status"));
        assertEquals("trace-1", result.get("traceId"));
        assertEquals("run-1", result.get("runId"));
        assertFalse(result.containsKey("finalState"));
        assertFalse(result.containsKey("resumeCheckpoint"));
    }

    @Test
    void rejectsNullOwnershipForWorkflow() {
        RuntimeInteractionSessionEntity session = waitingSession();
        when(sessionMapper.selectById("wfi_session1")).thenReturn(session);

        Map<String, Object> result = service.resume("wfi_session1", Map.of("values", Map.of("q", "x")));

        assertEquals(false, result.get("success"));
        assertEquals("RUNTIME_INTERACTION_FORBIDDEN", result.get("code"));
        verify(sessionMapper, never()).update(any(), any());
    }

    @Test
    void ownershipMatrixRejectsWrongSessionAppTenantUser() {
        RuntimeInteractionSessionEntity session = waitingSession();
        session.setTenantId("tenant-a");
        when(sessionMapper.selectById("wfi_session1")).thenReturn(session);

        assertForbidden(service.resume("wfi_session1", Map.of("values", Map.of("q", "x")),
                new RuntimeInteractionResumeService.Ownership("app-1", "tenant-a", "chat-wrong", "user-1")));
        assertForbidden(service.resume("wfi_session1", Map.of("values", Map.of("q", "x")),
                new RuntimeInteractionResumeService.Ownership("app-wrong", "tenant-a", "chat-1", "user-1")));
        assertForbidden(service.resume("wfi_session1", Map.of("values", Map.of("q", "x")),
                new RuntimeInteractionResumeService.Ownership("app-1", "tenant-wrong", "chat-1", "user-1")));
        assertForbidden(service.resume("wfi_session1", Map.of("values", Map.of("q", "x")),
                new RuntimeInteractionResumeService.Ownership("app-1", "tenant-a", "chat-1", "user-wrong")));
    }

    @Test
    void rejectsOwnershipMismatch() {
        RuntimeInteractionSessionEntity session = waitingSession();
        when(sessionMapper.selectById("wfi_session1")).thenReturn(session);

        Map<String, Object> result = service.resume("wfi_session1",
                Map.of("values", Map.of("q", "x")),
                new RuntimeInteractionResumeService.Ownership("app-b", null, "chat-1", "user-1"));

        assertEquals(false, result.get("success"));
        assertEquals("RUNTIME_INTERACTION_FORBIDDEN", result.get("code"));
        verify(sessionMapper, never()).update(any(), any());
    }

    @Test
    void rejectsExpiredSession() {
        RuntimeInteractionSessionEntity session = waitingSession();
        session.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(sessionMapper.selectById("wfi_session1")).thenReturn(session);

        Map<String, Object> result = service.resume("wfi_session1", Map.of("values", Map.of("q", "x")), owner());

        assertEquals(false, result.get("success"));
        assertEquals("RUNTIME_INTERACTION_EXPIRED", result.get("code"));
    }

    @Test
    void concurrentClaimOnlyOneWins() {
        RuntimeInteractionSessionEntity session = waitingSession();
        when(sessionMapper.selectById("wfi_session1")).thenReturn(session);
        AtomicInteger updates = new AtomicInteger();
        when(sessionMapper.update(any(), any())).thenAnswer(invocation -> updates.getAndIncrement() == 0 ? 1 : 0);

        Map<String, Object> first = service.resume("wfi_session1", Map.of(
                "values", Map.of("q", "a"),
                "idempotencyKey", "k1"), owner());
        RuntimeInteractionSessionEntity latest = waitingSession();
        latest.setStatus("COMPLETED");
        latest.setIdempotencyKey("k1");
        latest.setSubmittedPayloadJson("{\"q\":\"a\"}");
        latest.setResultJson("{\"code\":\"RUNTIME_GRAPH_EXECUTED\",\"answer\":\"got:a\"}");
        when(sessionMapper.selectById("wfi_session1")).thenReturn(latest);
        Map<String, Object> second = service.resume("wfi_session1", Map.of(
                "values", Map.of("q", "a"),
                "idempotencyKey", "k1"), owner());

        assertEquals(true, first.get("success"));
        assertEquals(true, second.get("idempotentReplay"));
        ArgumentCaptor<UpdateWrapper<RuntimeInteractionSessionEntity>> captor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(sessionMapper, atLeastOnce()).update(any(), captor.capture());
        String sql = String.valueOf(captor.getAllValues().get(0).getSqlSegment());
        assertTrue(sql.contains("status") || sql.toLowerCase().contains("waiting"),
                "CAS update must constrain status/revision, got: " + sql);
    }

    @Test
    void differentPayloadSameIdempotencyKeyConflicts() {
        RuntimeInteractionSessionEntity session = waitingSession();
        session.setIdempotencyKey("k1");
        session.setSubmittedPayloadJson("{\"q\":\"a\"}");
        when(sessionMapper.selectById("wfi_session1")).thenReturn(session);
        when(sessionMapper.update(any(), any())).thenReturn(0);

        Map<String, Object> result = service.resume("wfi_session1", Map.of(
                "values", Map.of("q", "b"),
                "idempotencyKey", "k1"), owner());

        assertEquals(false, result.get("success"));
        assertEquals("RUNTIME_INTERACTION_CONFLICT", result.get("code"));
    }

    @Test
    void duplicateWhileOriginalResumeIsStillRunningDoesNotReportExecutionSuccess() {
        RuntimeInteractionSessionEntity session = waitingSession();
        session.setStatus("RESUMING");
        session.setRevision(1);
        session.setIdempotencyKey("k1");
        session.setSubmittedPayloadJson("{\"q\":\"a\"}");
        session.setResultJson(null);
        when(sessionMapper.selectById("wfi_session1")).thenReturn(session);

        Map<String, Object> result = service.resume("wfi_session1", Map.of(
                "values", Map.of("q", "a"),
                "idempotencyKey", "k1"), owner());

        assertEquals(false, result.get("success"));
        assertEquals("RUNTIME_INTERACTION_CONFLICT", result.get("code"));
        assertEquals("RESUMING", result.get("status"));
        verify(sessionMapper, never()).update(any(), any());
    }

    @Test
    void validationFailureRollsBackToWaiting() {
        RuntimeInteractionSessionEntity session = waitingSession();
        session.setGraphSpecSnapshotJson("""
                {"schemaVersion":2,"entryNodeId":"form","exitNodeIds":["form"],"nodes":[{"id":"form","type":"INTERACTION","config":{
                  "interactionType":"COLLECT_INPUT",
                  "fields":[{"key":"q","required":true}]
                }}]}
                """);
        when(sessionMapper.selectById("wfi_session1")).thenReturn(session);
        when(sessionMapper.update(any(), any())).thenReturn(1);

        Map<String, Object> result = service.resume("wfi_session1", Map.of(
                "values", Map.of(),
                "idempotencyKey", "bad-1"), owner());

        assertEquals(false, result.get("success"));
        assertEquals(true, result.get("validationFailed"));
        assertEquals("WAITING_USER", result.get("status"));
        assertTrue(result.get("uiRequest") instanceof Map<?, ?>);
        assertNull(result.get("finalState"));
        verify(sessionMapper, atLeastOnce()).update(any(), any());
    }

    @Test
    void preservesSnapshotOutputsAcrossResume() {
        RuntimeInteractionSessionEntity session = waitingSession();
        session.setResumeCheckpointJson("""
                {
                  "traceId":"trace-1",
                  "runId":"run-1",
                  "lastOutput":{"echo":"tool-once"},
                  "nodeOutput":{"tool":{"echo":"tool-once"}},
                  "__pendingInteractionId":"wfi_session1",
                  "__pendingInteractionNodeId":"form"
                }
                """);
        session.setGraphSpecSnapshotJson("""
                {
                  "schemaVersion":2,
                  "entryNodeId":"form",
                  "exitNodeIds":["answer"],
                  "nodes":[
                    {"id":"form","type":"INTERACTION","config":{
                      "interactionType":"COLLECT_INPUT",
                      "fields":[{"key":"q","required":true}]
                    }},
                    {"id":"answer","type":"ANSWER","config":{"template":"{{ nodeOutput.tool.echo }}:{{ q }}"}}
                  ],
                  "edges":[{"from":"form","to":"answer"}]
                }
                """);
        when(sessionMapper.selectById("wfi_session1")).thenReturn(session);
        when(sessionMapper.update(any(), any())).thenReturn(1);

        Map<String, Object> result = service.resume("wfi_session1", Map.of(
                "values", Map.of("q", "user"),
                "idempotencyKey", "snap-1"), owner());

        assertEquals(true, result.get("success"));
        assertEquals("tool-once:user", result.get("answer"));
        assertEquals("trace-1", result.get("traceId"));
        assertEquals("run-1", result.get("runId"));
    }

    @Test
    void returnsContinuationOnCompletedWorkflowResume() {
        RuntimeInteractionSessionEntity session = waitingSession();
        session.setContinuationJson("""
                {
                  "agentId":"agent-1",
                  "agentConfigVersionId":12,
                  "toolName":"wf_tool",
                  "waitingToolName":"wf_tool",
                  "traceId":"trace-1",
                  "runId":"run-1",
                  "completedWorkflowToolNames":[],
                  "recordedPlan":{"steps":["call wf_tool"]}
                }
                """);
        when(sessionMapper.selectById("wfi_session1")).thenReturn(session);
        when(sessionMapper.update(any(), any())).thenReturn(1);

        Map<String, Object> result = service.resume("wfi_session1", Map.of(
                "values", Map.of("q", "hello"),
                "idempotencyKey", "cont-1"), owner());

        assertEquals(true, result.get("success"));
        assertTrue(result.get("continuation") instanceof Map<?, ?>);
        @SuppressWarnings("unchecked")
        Map<String, Object> continuation = (Map<String, Object>) result.get("continuation");
        assertEquals("agent-1", continuation.get("agentId"));
        assertEquals("trace-1", continuation.get("traceId"));
    }

    private static void assertForbidden(Map<String, Object> result) {
        assertEquals(false, result.get("success"));
        assertEquals("RUNTIME_INTERACTION_FORBIDDEN", result.get("code"));
    }

    private static RuntimeInteractionResumeService.Ownership owner() {
        return new RuntimeInteractionResumeService.Ownership("app-1", null, "chat-1", "user-1");
    }

    private RuntimeInteractionSessionEntity waitingSession() {
        RuntimeInteractionSessionEntity session = new RuntimeInteractionSessionEntity();
        session.setId("wfi_session1");
        session.setSourceType("WORKFLOW");
        session.setRunId("run-1");
        session.setTraceId("trace-1");
        session.setWorkflowId("wf-1");
        session.setWorkflowVersionId(9L);
        session.setNodeId("form");
        session.setInteractionType("COLLECT_INPUT");
        session.setStatus("WAITING_USER");
        session.setRevision(0);
        session.setResumeCheckpointJson("{\"traceId\":\"trace-1\",\"runId\":\"run-1\"}");
        session.setUiRequestJson("{\"interactionId\":\"wfi_session1\",\"component\":\"form\",\"nodeId\":\"form\"}");
        session.setSessionId("chat-1");
        session.setAppId("app-1");
        session.setUserId("user-1");
        session.setCreateTime(LocalDateTime.now());
        session.setUpdateTime(LocalDateTime.now());
        session.setExpiresAt(LocalDateTime.now().plusHours(1));
        session.setGraphSpecSnapshotJson("""
                {
                  "schemaVersion":2,
                  "entryNodeId":"form",
                  "exitNodeIds":["answer"],
                  "nodes":[
                    {"id":"form","type":"INTERACTION","config":{
                      "interactionType":"COLLECT_INPUT",
                      "fields":[{"key":"q","required":true}]
                    }},
                    {"id":"answer","type":"ANSWER","config":{"template":"got:{{ q }}"}}
                  ],
                  "edges":[{"from":"form","to":"answer"}]
                }
                """);
        return session;
    }
}
