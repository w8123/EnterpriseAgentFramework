package com.enterprise.ai.runtime.interaction;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.enterprise.ai.runtime.execution.RuntimeInteractionSessionEntity;
import com.enterprise.ai.runtime.execution.RuntimeInteractionSessionMapper;
import com.enterprise.ai.runtime.supervisor.SupervisorApprovalInteractionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeHumanApprovalServiceTest {

    private final RuntimeInteractionSessionMapper sessionMapper = mock(RuntimeInteractionSessionMapper.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final RuntimeHumanApprovalService service =
            new RuntimeHumanApprovalService(sessionMapper, objectMapper);

    @Test
    void listPendingHumanApprovalsMapsSupervisorPolicySessions() throws Exception {
        RuntimeInteractionSessionEntity row = pendingRow();
        row.setUiRequestJson("""
                {"component":"confirm","title":"确认修改班组","message":"是否继续？","interactionId":"spv_confirm-1"}
                """);
        when(sessionMapper.selectList(any())).thenReturn(List.of(row));

        List<RuntimeHumanApprovalService.PendingHumanApprovalView> views =
                service.listPendingHumanApprovals("agent-7", "u-1", 500);

        assertEquals(1, views.size());
        RuntimeHumanApprovalService.PendingHumanApprovalView view = views.get(0);
        assertEquals("spv_confirm-1", view.interactionId());
        assertEquals("trace-1", view.traceId());
        assertEquals("session-1", view.sessionId());
        assertEquals("u-1", view.userId());
        assertEquals("agent-7", view.agentId());
        assertEquals("update_team", view.nodeId());
        assertEquals("WAITING_USER", view.status());
        assertEquals("确认修改班组", view.title());
        assertEquals("是否继续？", view.message());
        assertEquals("T-1", view.state().get("teamId"));
        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<QueryWrapper<RuntimeInteractionSessionEntity>> queryCaptor =
                (ArgumentCaptor) ArgumentCaptor.forClass(QueryWrapper.class);
        verify(sessionMapper).selectList(queryCaptor.capture());
        String sql = queryCaptor.getValue().getSqlSegment();
        assertTrue(sql.contains("agent_id"));
        assertTrue(sql.contains("user_id"));
        assertTrue(sql.indexOf("agent_id") < sql.indexOf("ORDER BY"));
    }

    private RuntimeInteractionSessionEntity pendingRow() throws Exception {
        RuntimeInteractionSessionEntity row = new RuntimeInteractionSessionEntity();
        row.setId("spv_confirm-1");
        row.setSourceType(SupervisorApprovalInteractionService.SOURCE_TYPE);
        row.setInteractionType(SupervisorApprovalInteractionService.INTERACTION_TYPE);
        row.setAgentId("agent-7");
        row.setTraceId("trace-1");
        row.setSessionId("session-1");
        row.setUserId("u-1");
        row.setNodeId("update_team");
        row.setStatus("WAITING_USER");
        row.setRevision(0);
        row.setResumeCheckpointJson(objectMapper.writeValueAsString(Map.of(
                "agentId", "agent-7",
                "toolName", "update_team",
                "teamId", "T-1")));
        row.setContinuationJson(objectMapper.writeValueAsString(Map.of("agentId", "agent-7")));
        row.setCreateTime(LocalDateTime.now().minusMinutes(3));
        row.setUpdateTime(LocalDateTime.now().minusMinutes(2));
        row.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        return row;
    }
}
