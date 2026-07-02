package com.enterprise.ai.runtime.debug;

import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDebugService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeExecutableDebugSessionServiceTest {

    private final RuntimeExecutableDebugSessionMapper mapper = mock(RuntimeExecutableDebugSessionMapper.class);
    private final RuntimeWorkflowDebugService workflowDebugService = mock(RuntimeWorkflowDebugService.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final RuntimeExecutableDebugSessionService service =
            new RuntimeExecutableDebugSessionService(mapper, workflowDebugService, objectMapper);

    @Test
    void createPersistsSessionAndReturnsFrontendCompatibleView() {
        RuntimeWorkflowDebugService.DebugRunResult run = new RuntimeWorkflowDebugService.DebugRunResult(
                "run-1",
                "trace-1",
                null,
                "WORKFLOW",
                true,
                "SUCCESS",
                "answer ok",
                "answer",
                List.of(),
                null,
                List.of(new RuntimeWorkflowDebugService.DebugStepResult(
                        0, "answer", "ANSWER", "Answer", "SUCCESS", null, null, 0L,
                        Map.of(), Map.of("answer", "answer ok"), null, Map.of(), Map.of(),
                        "execute-node", null, null, null, null, null, null, null)),
                Map.of("lastOutput", "answer ok"),
                null,
                null);
        when(workflowDebugService.debugRun(any())).thenReturn(run);

        RuntimeExecutableDebugSessionService.SessionView view = service.create(
                new RuntimeExecutableDebugSessionService.CreateRequest(
                        "WORKFLOW_DRAFT",
                        Map.of("graphSpecJson", "{\"entry\":\"answer\",\"nodes\":[{\"id\":\"answer\",\"type\":\"ANSWER\"}]}"),
                        "hello",
                        Map.of("channel", "studio"),
                        Map.of()));

        ArgumentCaptor<RuntimeExecutableDebugSessionEntity> captor =
                ArgumentCaptor.forClass(RuntimeExecutableDebugSessionEntity.class);
        verify(mapper).insert(captor.capture());
        RuntimeExecutableDebugSessionEntity inserted = captor.getValue();
        assertNotNull(inserted.getId());
        assertEquals(inserted.getId(), view.sessionId());
        assertEquals("run-1", view.runId());
        assertEquals("trace-1", view.traceId());
        assertEquals("WORKFLOW_DRAFT", view.targetType());
        assertEquals("SUCCESS", view.status());
        assertEquals("answer ok", view.answer());
        assertEquals(2, view.messages().size());
        assertEquals("user", view.messages().get(0).role());
        assertEquals("assistant", view.messages().get(1).role());
        assertEquals(1, view.steps().size());
        assertEquals("answer ok", view.finalState().get("lastOutput"));
    }

    @Test
    void submitContinuesWaitingSessionFromCurrentNode() {
        RuntimeExecutableDebugSessionEntity existing = new RuntimeExecutableDebugSessionEntity();
        existing.setId("session-1");
        existing.setRunId("run-1");
        existing.setTraceId("trace-1");
        existing.setTargetType("WORKFLOW_DRAFT");
        existing.setStatus("WAITING");
        existing.setCurrentNodeId("confirm");
        existing.setDraftDefinitionJson("{\"graphSpecJson\":\"{\\\"entry\\\":\\\"confirm\\\",\\\"nodes\\\":[{\\\"id\\\":\\\"confirm\\\",\\\"type\\\":\\\"INTERACTION\\\"}]}\"}");
        existing.setDebugOptionsJson("{}");
        existing.setStateJson("{\"input\":\"hello\"}");
        existing.setMessagesJson("[]");
        existing.setStepsJson("[]");
        when(mapper.selectById("session-1")).thenReturn(existing);
        RuntimeWorkflowDebugService.DebugRunResult run = new RuntimeWorkflowDebugService.DebugRunResult(
                "run-1",
                "trace-1",
                null,
                "WORKFLOW",
                true,
                "SUCCESS",
                "{approved=true}",
                "confirm",
                List.of(),
                null,
                List.of(),
                Map.of("lastOutput", "{approved=true}"),
                null,
                null);
        when(workflowDebugService.debugRun(any())).thenReturn(run);

        RuntimeExecutableDebugSessionService.SessionView view = service.submit(
                "session-1",
                new RuntimeExecutableDebugSessionService.SubmitRequest(
                        "submit",
                        Map.of("approved", true),
                        null));

        ArgumentCaptor<RuntimeWorkflowDebugService.DebugRunRequest> requestCaptor =
                ArgumentCaptor.forClass(RuntimeWorkflowDebugService.DebugRunRequest.class);
        verify(workflowDebugService).debugRun(requestCaptor.capture());
        assertEquals("confirm", requestCaptor.getValue().debugOptions().get("entryNodeId"));
        assertEquals(Map.of("action", "submit", "values", Map.of("approved", true)),
                requestCaptor.getValue().debugOptions().get("submittedPayload"));
        verify(mapper).updateById(existing);
        assertEquals("SUCCESS", view.status());
        assertEquals("{approved=true}", view.answer());
    }
}
