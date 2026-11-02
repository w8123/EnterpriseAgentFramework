package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.port.McpPlatformReadPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformRunOpsPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformRunOpsPort.Operation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class McpPlatformRunOpsServiceTest {
    private final McpPlatformRunOpsPort runOps = mock(McpPlatformRunOpsPort.class);
    private final McpPlatformReadPort reads = mock(McpPlatformReadPort.class);
    private final PlatformAiApprovalService approvals = mock(PlatformAiApprovalService.class);
    private final McpPlatformRunOpsService service = new McpPlatformRunOpsService(runOps, reads, approvals, new ObjectMapper());
    private final PlatformAiConnectionService.ConnectionIdentity actor = new PlatformAiConnectionService.ConnectionIdentity(19L, "CODEX",
            new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "操作员"), "session", null, List.of(), List.of(), List.of()));

    @Test void unconfirmedEvalCreateIsUnknownAndReplayIsNeverDirect() {
        doReturn(ResponseEntity.status(503).build()).when(runOps).call(actor.session(), Operation.EVAL_EXPERIMENT_CREATE, null, null, Map.of(), Map.of("targetId", "a"));
        assertEquals(202, service.direct(actor, Operation.EVAL_EXPERIMENT_CREATE, null, null, Map.of(), Map.of("targetId", "a")).getStatusCode().value());
        verify(runOps, times(1)).call(any(), any(), any(), any(), any(), any());
        assertThrows(IllegalArgumentException.class, () -> service.direct(actor, Operation.REPLAY, "t1", null, Map.of(), Map.of()));
    }

    @Test void replayFreezesTheSourceRunAndRefusesWhenItsVersionChanged() {
        doReturn(ResponseEntity.ok(Map.of("summary", summary(7)))).when(reads).getRunOpsDetail(actor.session(), "t1");
        service.requestReplay(actor, "t1", "再查一次库存", null, "00000000-0000-0000-0000-000000000001");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("RUN_REPLAY"), eq("RUN:t1"), frozen.capture());
        assertEquals(7, ((Map<?, ?>) frozen.getValue().get("source")).get("agentConfigVersionId"));
        when(approvals.claim(actor, "approval", "RUN_REPLAY"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "RUN_REPLAY", "RUN:t1", frozen.getValue()));

        doReturn(ResponseEntity.ok(Map.of("summary", summary(8)))).when(reads).getRunOpsDetail(actor.session(), "t1");
        assertEquals(409, service.executeReplay(actor, "approval").getStatusCode().value());
        verify(runOps, never()).call(any(), eq(Operation.REPLAY), any(), any(), any(), any());

        doReturn(ResponseEntity.ok(Map.of("summary", summary(7)))).when(reads).getRunOpsDetail(actor.session(), "t1");
        doReturn(ResponseEntity.status(503).build()).when(runOps).call(eq(actor.session()), eq(Operation.REPLAY), eq("t1"), isNull(), eq(Map.of()), any());
        assertEquals(202, service.executeReplay(actor, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        assertEquals(400, service.requestReplay(actor, "t1", " ", null, "00000000-0000-0000-0000-000000000002").getStatusCode().value());
    }

    private static Map<String, Object> summary(int version) {
        var summary = new LinkedHashMap<String, Object>();
        summary.put("traceId", "t1"); summary.put("agentId", "erp"); summary.put("agentConfigVersionId", version);
        summary.put("projectCode", "orders"); summary.put("status", "FAILED"); summary.put("durationMs", 1200);
        return summary;
    }
}
