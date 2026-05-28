package com.enterprise.ai.agent.debug;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.model.interactive.UiRequestPayload;
import com.enterprise.ai.agent.runtime.LangGraph4jRuntimeAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutableDebugSessionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void createStoresWaitingSessionWithUiRequestAndState() {
        ExecutableDebugSessionMapper mapper = mock(ExecutableDebugSessionMapper.class);
        LangGraph4jRuntimeAdapter adapter = mock(LangGraph4jRuntimeAdapter.class);
        when(mapper.insert(any())).thenReturn(1);
        UiRequestPayload uiRequest = UiRequestPayload.builder()
                .component("form")
                .title("补充信息")
                .message("请补充团队")
                .build();
        when(adapter.debugRun(any(), eq("查班组"), any(), any())).thenReturn(waitingRun(uiRequest));

        ExecutableDebugSessionService service = new ExecutableDebugSessionService(mapper, adapter, objectMapper);
        ExecutableDebugSessionService.ExecutableDebugSessionView view = service.create(new ExecutableDebugSessionService.CreateRequest(
                "AGENT_DRAFT",
                agentDraft(),
                "查班组",
                Map.of("question", "查班组"),
                Map.of()));

        assertEquals("WAITING", view.getStatus());
        assertEquals("collect", view.getCurrentNodeId());
        assertNotNull(view.getUiRequest());
        assertEquals(2, view.getMessages().size());
        ArgumentCaptor<ExecutableDebugSessionEntity> captor = ArgumentCaptor.forClass(ExecutableDebugSessionEntity.class);
        verify(mapper).insert(captor.capture());
        assertEquals("WAITING", captor.getValue().getStatus());
        assertNotNull(captor.getValue().getStateJson());
    }

    @Test
    void submitResumesFromWaitingNodeAndAppendsNewSteps() {
        ExecutableDebugSessionMapper mapper = mock(ExecutableDebugSessionMapper.class);
        LangGraph4jRuntimeAdapter adapter = mock(LangGraph4jRuntimeAdapter.class);
        AtomicReference<ExecutableDebugSessionEntity> stored = new AtomicReference<>();
        when(mapper.insert(any())).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        });
        when(mapper.selectById(any())).thenAnswer(invocation -> stored.get());
        when(mapper.updateById(any())).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        });
        UiRequestPayload uiRequest = UiRequestPayload.builder()
                .component("form")
                .title("补充信息")
                .message("请补充团队")
                .build();
        when(adapter.debugRun(any(), eq("查班组"), any(), any())).thenReturn(waitingRun(uiRequest));
        when(adapter.debugRun(any(), eq(""), any(), any())).thenReturn(successRun());

        ExecutableDebugSessionService service = new ExecutableDebugSessionService(mapper, adapter, objectMapper);
        ExecutableDebugSessionService.ExecutableDebugSessionView created = service.create(new ExecutableDebugSessionService.CreateRequest(
                "AGENT_DRAFT",
                agentDraft(),
                "查班组",
                Map.of("question", "查班组"),
                Map.of()));
        ExecutableDebugSessionService.ExecutableDebugSessionView submitted = service.submit(created.getSessionId(),
                new ExecutableDebugSessionService.SubmitRequest("submit", Map.of("teamName", "研发一组"), null));

        assertEquals("SUCCESS", submitted.getStatus());
        assertEquals(2, submitted.getSteps().size());
        ArgumentCaptor<Map<String, Object>> options = ArgumentCaptor.forClass(Map.class);
        verify(adapter).debugRun(any(), eq(""), any(), options.capture());
        assertEquals("collect", options.getValue().get("entryNodeId"));
        Map<String, Object> state = castMap(options.getValue().get("state"));
        assertEquals("研发一组", castMap(state.get("__requestParams")).get("teamName"));
    }

    private LangGraph4jRuntimeAdapter.WorkflowDebugRunResult waitingRun(UiRequestPayload uiRequest) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("__requestParams", Map.of("question", "查班组"));
        state.put("params", Map.of("question", "查班组"));
        return LangGraph4jRuntimeAdapter.WorkflowDebugRunResult.builder()
                .runId("run-1")
                .traceId("run-1")
                .status("WAITING")
                .success(true)
                .answer("请补充团队")
                .currentNodeId("collect")
                .uiRequest(uiRequest)
                .finalState(state)
                .steps(List.of(LangGraph4jRuntimeAdapter.WorkflowDebugStepResult.builder()
                        .index(0)
                        .nodeId("collect")
                        .nodeType("INTERACTION")
                        .status("WAITING")
                        .uiRequest(uiRequest)
                        .build()))
                .build();
    }

    private LangGraph4jRuntimeAdapter.WorkflowDebugRunResult successRun() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("__requestParams", Map.of("question", "查班组", "teamName", "研发一组"));
        state.put("answer", "研发一组负责人信息");
        return LangGraph4jRuntimeAdapter.WorkflowDebugRunResult.builder()
                .runId("run-1")
                .traceId("run-1")
                .status("SUCCESS")
                .success(true)
                .answer("研发一组负责人信息")
                .finalState(state)
                .steps(List.of(LangGraph4jRuntimeAdapter.WorkflowDebugStepResult.builder()
                        .index(0)
                        .nodeId("collect")
                        .nodeType("INTERACTION")
                        .status("SUCCESS")
                        .build()))
                .build();
    }

    private Map<String, Object> agentDraft() {
        GraphSpec graphSpec = GraphSpec.builder()
                .entry("collect")
                .node(GraphSpec.Node.builder().id("collect").type("INTERACTION").build())
                .build();
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("id", "agent-debug");
        draft.put("name", "调试草稿");
        draft.put("runtimeType", "LANGGRAPH4J");
        draft.put("graphSpec", objectMapper.convertValue(graphSpec, Map.class));
        return draft;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
