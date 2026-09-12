package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigSnapshot;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolSnapshot;
import com.enterprise.ai.runtime.execution.RuntimeInteractionEventMapper;
import com.enterprise.ai.runtime.execution.RuntimeSupervisorApprovalService;
import com.enterprise.ai.runtime.execution.RuntimeInteractionSessionEntity;
import com.enterprise.ai.runtime.execution.RuntimeInteractionSessionMapper;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupervisorApprovalInteractionServiceTest {

    private final RuntimeInteractionSessionMapper sessionMapper = mock(RuntimeInteractionSessionMapper.class);
    private final RuntimeInteractionEventMapper eventMapper = mock(RuntimeInteractionEventMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuntimeSupervisorApprovalService approvals =
            new RuntimeSupervisorApprovalService(sessionMapper, eventMapper, objectMapper,
                    mock(com.enterprise.ai.runtime.execution.RuntimeInteractionExpiryTracePort.class), 900);
    private final SupervisorApprovalInteractionService service =
            new SupervisorApprovalInteractionService(approvals, objectMapper);

    @Test
    void persistsApprovalAndResumesOnlyTheConfirmedToolAndArgs() {
        RuntimeAgentConfigSnapshot config = RuntimeAgentConfigSnapshot.builder()
                .id(12L)
                .build();
        RuntimeAgentWorkflowToolSnapshot tool = RuntimeAgentWorkflowToolSnapshot.builder()
                .toolName("update_team")
                .permissionKey("team:write")
                .riskLevel("WRITE")
                .build();
        Map<String, Object> args = Map.of("teamId", "T-1", "name", "一班", "token", "secret-value");
        Map<String, Object> input = Map.of(
                "agentId", "agent-1", "message", "修改班组", "sessionId", "session-1",
                "userId", "forged-body-user");

        SupervisorApprovalInteractionService.ApprovalRequest created = service.create(
                new SupervisorExecutionTraceService.TraceHandle("trace-1", "span-1", 1L, LocalDateTime.now()),
                agent(), config, tool, input, args, "需要确认", trustedIdentity("u-1"));

        ArgumentCaptor<RuntimeInteractionSessionEntity> captor =
                ArgumentCaptor.forClass(RuntimeInteractionSessionEntity.class);
        verify(sessionMapper).insert(captor.capture());
        RuntimeInteractionSessionEntity row = captor.getValue();
        assertTrue(row.getId().startsWith(SupervisorApprovalInteractionService.INTERACTION_PREFIX));
        assertEquals(SupervisorApprovalInteractionService.SOURCE_TYPE, row.getSourceType());
        assertEquals(SupervisorApprovalInteractionService.INTERACTION_TYPE, row.getInteractionType());
        assertEquals("agent-1", row.getAgentId());
        assertEquals("u-1", row.getUserId());
        assertEquals("WAITING_USER", row.getStatus());
        assertEquals("update_team", row.getNodeId());
        assertEquals("confirm", ((Map<?, ?>) created.uiRequest()).get("component"));
        assertTrue(created.uiRequest().toString().contains("[已隐藏]"));
        assertFalse(created.uiRequest().toString().contains("secret-value"));
        when(sessionMapper.selectForUpdate(row.getId())).thenReturn(row);
        when(sessionMapper.update(any(), any())).thenReturn(1);

        Map<String, Object> submission = Map.of(
                "sessionId", "session-1",
                "userId", "forged-body-user",
                "uiSubmit", Map.of("action", "confirm", "values", Map.of("confirm", true)));
        RuntimeSupervisorApprovalService.ResumeDecision resumed = prepareResume(row.getId(), submission);

        assertTrue(resumed.approved());
        assertFalse(resumed.rejected());
        assertEquals("update_team", resumed.grant().toolName());
        assertEquals("T-1", resumed.grant().approvedArgs().get("teamId"));
        assertEquals("u-1", resumed.grant().approvedBy());
        assertEquals("trace-1", resumed.originalInput().get("traceId"));
        assertEquals("RESUMING", row.getStatus());

        Map<String, Object> result = Map.of(
                "success", true,
                "answer", "done",
                "metadata", Map.of("code", "SUPERVISOR_COMPLETED"));
        assertEquals(result, approvals.completeResume(
                row.getId(), resumed.idempotencyKey(), resumed.submittedPayload(), result));
        assertEquals("COMPLETED", row.getStatus());

        RuntimeSupervisorApprovalService.ResumeDecision replay = prepareResume(row.getId(), submission);
        assertNotNull(replay.replayResult());
        assertEquals(true, replay.replayResult().get("success"));
        assertEquals(true, ((Map<?, ?>) replay.replayResult().get("metadata")).get("idempotentReplay"));
        verify(eventMapper, org.mockito.Mockito.atLeastOnce()).insert(any());
    }

    @Test
    void rejectsSessionMismatchExpiredAndConflictingDecision() throws Exception {
        RuntimeInteractionSessionEntity row = pendingRow();
        when(sessionMapper.selectForUpdate("spv_1")).thenReturn(row);
        when(sessionMapper.update(any(), any())).thenReturn(1);

        assertThrows(IllegalArgumentException.class, () -> prepareResume(
                "spv_1", Map.of("sessionId", "other", "uiSubmit", Map.of("action", "confirm"))));

        assertThrows(IllegalArgumentException.class, () -> approvals.prepareResume(
                "spv_1", Map.of(
                        "sessionId", "session-1",
                        "userId", "u-1",
                        "uiSubmit", Map.of("action", "confirm")), trustedIdentity("u-2")));
        assertThrows(IllegalArgumentException.class, () -> approvals.prepareResume(
                "spv_1", Map.of(
                        "sessionId", "session-1",
                        "userId", "u-1",
                        "uiSubmit", Map.of("action", "confirm")), null));

        assertThrows(IllegalArgumentException.class, () -> prepareResume(
                "spv_1", Map.of(
                        "sessionId", "session-1",
                        "userId", "u-1",
                        "uiSubmit", Map.of("action", "confirm", "values", Map.of("confirm", false)))));

        row.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        assertThrows(IllegalArgumentException.class, () -> prepareResume(
                "spv_1", Map.of(
                        "sessionId", "session-1",
                        "userId", "u-1",
                        "uiSubmit", Map.of("action", "confirm"))));
        assertEquals("EXPIRED", row.getStatus());
    }

    @Test
    void recordsUserRejectionWithoutIssuingGrant() throws Exception {
        RuntimeInteractionSessionEntity row = pendingRow();
        when(sessionMapper.selectForUpdate("spv_1")).thenReturn(row);
        when(sessionMapper.update(any(), any())).thenReturn(1);

        RuntimeSupervisorApprovalService.ResumeDecision decision = prepareResume(
                "spv_1", Map.of(
                        "sessionId", "session-1",
                        "userId", "u-1",
                        "uiSubmit", Map.of("action", "reject", "values", Map.of("confirm", false))));

        assertFalse(decision.approved());
        assertTrue(decision.rejected());
        assertEquals(null, decision.grant());
        assertEquals("RESUMING", row.getStatus());
    }

    @Test
    void returnsInProgressForSameAttemptAndRejectsDifferentAttempt() throws Exception {
        RuntimeInteractionSessionEntity row = pendingRow();
        when(sessionMapper.selectForUpdate("spv_1")).thenReturn(row);
        when(sessionMapper.update(any(), any())).thenReturn(1);
        Map<String, Object> submission = Map.of(
                "sessionId", "session-1",
                "userId", "u-1",
                "idempotencyKey", "attempt-1",
                "uiSubmit", Map.of("action", "confirm", "values", Map.of("confirm", true)));

        prepareResume("spv_1", submission);
        RuntimeSupervisorApprovalService.ResumeDecision duplicate = prepareResume("spv_1", submission);

        assertEquals(false, duplicate.replayResult().get("success"));
        assertEquals("SUPERVISOR_APPROVAL_RESUMING",
                ((Map<?, ?>) duplicate.replayResult().get("metadata")).get("code"));
        assertThrows(IllegalArgumentException.class, () -> prepareResume(
                "spv_1", Map.of(
                        "sessionId", "session-1",
                        "userId", "u-1",
                        "idempotencyKey", "attempt-2",
                        "uiSubmit", Map.of("action", "confirm", "values", Map.of("confirm", true)))));
    }

    private RuntimeAgentView agent() {
        return new RuntimeAgentView(
                "agent-1", 7L, "qmssmp", "team-assistant", "班组助手", null,
                "PROJECT", null, true,
                7L, 7L, 1, "ACTIVE", "AGENTSCOPE", 1, null, null);
    }

    private RuntimeSupervisorApprovalService.ResumeDecision prepareResume(
            String interactionId,
            Map<String, Object> submission) {
        return approvals.prepareResume(interactionId, submission, trustedIdentity("u-1"));
    }

    private WorkflowExecutionIdentity trustedIdentity(String userId) {
        return WorkflowExecutionIdentity.fromAgent("default", 7L, "qmssmp", userId);
    }

    private RuntimeInteractionSessionEntity pendingRow() throws Exception {
        RuntimeInteractionSessionEntity row = new RuntimeInteractionSessionEntity();
        row.setId("spv_1");
        row.setSourceType(SupervisorApprovalInteractionService.SOURCE_TYPE);
        row.setInteractionType(SupervisorApprovalInteractionService.INTERACTION_TYPE);
        row.setAgentId("agent-1");
        row.setTraceId("trace-1");
        row.setSessionId("session-1");
        row.setUserId("u-1");
        row.setTenantId("default");
        row.setNodeId("update_team");
        row.setStatus("WAITING_USER");
        row.setRevision(0);
        row.setResumeCheckpointJson(objectMapper.writeValueAsString(Map.of(
                "agentId", "agent-1",
                "projectId", 7L,
                "projectCode", "qmssmp",
                "agentConfigVersionId", 12L,
                "toolName", "update_team",
                "permissionKey", "team:write",
                "args", Map.of("teamId", "T-1"),
                "input", Map.of("agentId", "agent-1", "message", "修改班组"))));
        row.setContinuationJson(objectMapper.writeValueAsString(Map.of("agentId", "agent-1")));
        row.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        return row;
    }
}
