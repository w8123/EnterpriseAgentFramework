package com.enterprise.ai.runtime.runops;

import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionService;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsDetailView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsSummaryView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeRunOpsReplayServiceTest {

    private final RuntimeRunOpsQueryService queryService = mock(RuntimeRunOpsQueryService.class);
    private final RuntimeAgentExecutionService executionService = mock(RuntimeAgentExecutionService.class);
    private final RuntimeRunOpsReplayService service = new RuntimeRunOpsReplayService(queryService, executionService);

    @Test
    void replayUsesRootInputAndExactPublishedConfigWithoutSnapshotSwitch() {
        assertEquals(
                List.of("messageOverride", "sessionId", "userId", "roles"),
                java.util.Arrays.stream(RuntimeRunOpsReplayService.ReplayRequest.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .toList());
        when(queryService.detail("trace-1")).thenReturn(detail("查询班组信息", 11L));
        when(executionService.executePublishedConfig(eq("agent-1"), eq(11L), any(), eq(true)))
                .thenReturn(Map.of(
                        "success", true,
                        "answer", "第一条班组信息",
                        "metadata", Map.of("traceId", "trace-replay")));

        RuntimeRunOpsReplayService.ReplayResult result = service.replay(
                "trace-1",
                new RuntimeRunOpsReplayService.ReplayRequest(
                        null, "session-replay", "user-replay", List.of("operator")));

        assertEquals("trace-replay", result.replayTraceId());
        assertEquals(11L, result.agentConfigVersionId());
        assertEquals(3, result.agentConfigVersion());
        assertEquals("查询班组信息", result.message());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> requestCaptor = ArgumentCaptor.forClass(Map.class);
        verify(executionService).executePublishedConfig(
                eq("agent-1"), eq(11L), requestCaptor.capture(), eq(true));
        Map<String, Object> request = requestCaptor.getValue();
        assertEquals("REPLAY", request.get("entryType"));
        assertEquals("trace-1", request.get("replayOfTraceId"));
        assertEquals("查询班组信息", request.get("message"));
        assertEquals(List.of("operator"), request.get("roles"));
        assertFalse(request.containsKey("useSnapshot"));
        Map<?, ?> metadata = (Map<?, ?>) request.get("metadata");
        assertEquals("REPLAY", metadata.get("entryType"));
        assertEquals("trace-1", metadata.get("replayOfTraceId"));
        assertFalse(metadata.containsKey("useSnapshot"));
        assertFalse(metadata.containsKey("replayUseSnapshot"));
    }

    @Test
    void replayRequiresRuntimeRunInputOrMessageOverride() {
        when(queryService.detail("trace-1")).thenReturn(detail(null, 11L));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.replay("trace-1", new RuntimeRunOpsReplayService.ReplayRequest(
                        null, null, null, List.of())));

        assertEquals("Unable to restore replay input from runtime_run; provide messageOverride", ex.getMessage());
    }

    @Test
    void replayRejectsRunWithoutExactConfigVersion() {
        when(queryService.detail("trace-1")).thenReturn(detail("hello", null));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.replay("trace-1", null));

        assertEquals("Replay source has no published Agent config version: trace-1", ex.getMessage());
    }

    private RuntimeRunOpsDetailView detail(String inputSummary, Long configVersionId) {
        RuntimeRunOpsSummaryView summary = new RuntimeRunOpsSummaryView(
                "trace-1",
                "AGENT",
                "DEBUG",
                "SUCCESS",
                "qmssmp",
                "tenant-1",
                "session-1",
                "user-1",
                "agent-1",
                "team-assistant",
                "班组助手",
                configVersionId,
                3,
                null,
                null,
                null,
                null,
                null,
                "AGENTSCOPE",
                inputSummary,
                "answer",
                null,
                null,
                LocalDateTime.parse("2026-07-14T08:00:00"),
                LocalDateTime.parse("2026-07-14T08:00:01"),
                1000,
                10,
                1,
                0,
                0,
                0,
                0,
                0,
                null,
                Map.of());
        return new RuntimeRunOpsDetailView(
                summary, List.of(), List.of(), List.of(), null, List.of(), List.of());
    }
}
