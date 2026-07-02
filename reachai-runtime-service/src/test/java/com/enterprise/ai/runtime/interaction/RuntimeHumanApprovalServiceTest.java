package com.enterprise.ai.runtime.interaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
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

class RuntimeHumanApprovalServiceTest {

    private final RuntimeSkillInteractionMapper mapper = mock(RuntimeSkillInteractionMapper.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final RuntimeHumanApprovalService service = new RuntimeHumanApprovalService(mapper, objectMapper);

    @Test
    void listPendingHumanApprovalsMapsSkillInteractionRows() {
        RuntimeSkillInteractionEntity row = pendingRow();
        row.setUiPayload("""
                {"component":"confirm","title":"Approve order","message":"Confirm order?","interactionId":"approval-1"}
                """);
        row.setSlotState("{\"orderId\":\"A-1\"}");
        when(mapper.selectList(any())).thenReturn(List.of(row));

        List<RuntimeHumanApprovalService.PendingHumanApprovalView> views =
                service.listPendingHumanApprovals(7L, "u-1", 500);

        assertEquals(1, views.size());
        RuntimeHumanApprovalService.PendingHumanApprovalView view = views.get(0);
        assertEquals("approval-1", view.interactionId());
        assertEquals("trace-1", view.traceId());
        assertEquals("session-1", view.sessionId());
        assertEquals("u-1", view.userId());
        assertEquals(7L, view.agentId());
        assertEquals("approveNode", view.nodeId());
        assertEquals("PENDING", view.status());
        assertEquals("Approve order", view.title());
        assertEquals("Confirm order?", view.message());
        assertEquals("A-1", view.state().get("orderId"));
    }

    @Test
    void submitHumanApprovalMarksSubmittedAndReturnsAgentResultShape() {
        RuntimeSkillInteractionEntity row = pendingRow();
        when(mapper.selectById("approval-1")).thenReturn(row);

        RuntimeHumanApprovalService.AgentResultView result = service.submitHumanApproval(
                "approval-1",
                new RuntimeHumanApprovalService.SubmitRequest("reject", Map.of("confirm", false), "reviewer", "session-1"));

        ArgumentCaptor<RuntimeSkillInteractionEntity> captor =
                ArgumentCaptor.forClass(RuntimeSkillInteractionEntity.class);
        verify(mapper).updateById(captor.capture());
        RuntimeSkillInteractionEntity update = captor.getValue();
        assertEquals("SUBMITTED", update.getStatus());
        assertEquals(true, update.getSlotState().contains("\"route\":\"rejected\""));
        assertEquals(true, result.success());
        assertEquals("审批已拒绝", result.answer());
        assertEquals("approval-1", result.metadata().get("interactionId"));
        assertEquals("rejected", result.metadata().get("route"));
    }

    @Test
    void cancelHumanApprovalMarksCancelled() {
        RuntimeSkillInteractionEntity row = pendingRow();
        when(mapper.selectById("approval-1")).thenReturn(row);

        RuntimeHumanApprovalService.AgentResultView result = service.cancelHumanApproval("approval-1", "reviewer");

        verify(mapper).updateById(row);
        assertEquals("CANCELLED", row.getStatus());
        assertEquals(true, result.success());
        assertEquals("已取消审批", result.answer());
        assertEquals("cancelled", result.metadata().get("route"));
    }

    private RuntimeSkillInteractionEntity pendingRow() {
        RuntimeSkillInteractionEntity row = new RuntimeSkillInteractionEntity();
        row.setId("approval-1");
        row.setTraceId("trace-1");
        row.setSessionId("session-1");
        row.setUserId("u-1");
        row.setAgentId(7L);
        row.setSkillName("graph-approval:approveNode");
        row.setStatus("PENDING");
        row.setSlotState("{}");
        row.setCreatedAt(LocalDateTime.now().minusMinutes(3));
        row.setUpdatedAt(LocalDateTime.now().minusMinutes(2));
        row.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        return row;
    }
}
