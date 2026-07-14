package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolEntity;
import com.enterprise.ai.runtime.interaction.RuntimeSkillInteractionEntity;
import com.enterprise.ai.runtime.interaction.RuntimeSkillInteractionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupervisorApprovalInteractionServiceTest {

    private final RuntimeSkillInteractionMapper mapper = mock(RuntimeSkillInteractionMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SupervisorApprovalInteractionService service =
            new SupervisorApprovalInteractionService(mapper, objectMapper);

    @Test
    void persistsApprovalAndResumesOnlyTheConfirmedToolAndArgs() {
        RuntimeAgentConfigVersionEntity config = new RuntimeAgentConfigVersionEntity();
        config.setId(12L);
        RuntimeAgentWorkflowToolEntity tool = new RuntimeAgentWorkflowToolEntity();
        tool.setToolName("update_team");
        tool.setPermissionKey("team:write");
        tool.setRiskLevel("WRITE");
        Map<String, Object> args = Map.of("teamId", "T-1", "name", "一班");
        Map<String, Object> input = Map.of(
                "agentId", "agent-1", "message", "修改班组", "sessionId", "session-1", "userId", "u-1");

        SupervisorApprovalInteractionService.ApprovalRequest created = service.create(
                new SupervisorExecutionTraceService.TraceHandle("trace-1", "span-1", 1L, LocalDateTime.now()),
                agent(), config, tool, input, args, "需要确认");

        ArgumentCaptor<RuntimeSkillInteractionEntity> captor =
                ArgumentCaptor.forClass(RuntimeSkillInteractionEntity.class);
        verify(mapper).insert(captor.capture());
        RuntimeSkillInteractionEntity row = captor.getValue();
        assertTrue(row.getId().startsWith(SupervisorApprovalInteractionService.INTERACTION_PREFIX));
        assertEquals("agent-1", row.getAgentId());
        assertEquals("PENDING", row.getStatus());
        assertEquals("confirm", ((Map<?, ?>) created.uiRequest()).get("component"));
        when(mapper.selectById(row.getId())).thenReturn(row);

        SupervisorApprovalInteractionService.ResumeDecision resumed = service.prepareResume(
                row.getId(), Map.of(
                        "sessionId", "session-1",
                        "userId", "u-1",
                        "uiSubmit", Map.of("action", "confirm", "values", Map.of("confirm", true))));

        assertTrue(resumed.approved());
        assertFalse(resumed.rejected());
        assertEquals("update_team", resumed.grant().toolName());
        assertEquals(args, resumed.grant().approvedArgs());
        assertEquals("SUBMITTED", row.getStatus());
    }

    @Test
    void rejectsSessionMismatchAndExpiredOrReusedApproval() throws Exception {
        RuntimeSkillInteractionEntity row = pendingRow();
        when(mapper.selectById("spv_1")).thenReturn(row);

        assertThrows(IllegalArgumentException.class, () -> service.prepareResume(
                "spv_1", Map.of("sessionId", "other", "uiSubmit", Map.of("action", "confirm"))));

        row.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        assertThrows(IllegalArgumentException.class, () -> service.prepareResume(
                "spv_1", Map.of("sessionId", "session-1", "uiSubmit", Map.of("action", "confirm"))));
        assertEquals("EXPIRED", row.getStatus());

        row = pendingRow();
        row.setStatus("SUBMITTED");
        when(mapper.selectById("spv_2")).thenReturn(row);
        assertThrows(IllegalArgumentException.class, () -> service.prepareResume(
                "spv_2", Map.of("sessionId", "session-1", "uiSubmit", Map.of("action", "confirm"))));
    }

    @Test
    void recordsUserRejectionWithoutIssuingGrant() throws Exception {
        RuntimeSkillInteractionEntity row = pendingRow();
        when(mapper.selectById("spv_1")).thenReturn(row);

        SupervisorApprovalInteractionService.ResumeDecision decision = service.prepareResume(
                "spv_1", Map.of(
                        "sessionId", "session-1",
                        "userId", "u-1",
                        "uiSubmit", Map.of("action", "reject", "values", Map.of("confirm", false))));

        assertFalse(decision.approved());
        assertTrue(decision.rejected());
        assertEquals(null, decision.grant());
        assertEquals("SUBMITTED", row.getStatus());
    }

    private RuntimeAgentView agent() {
        return new RuntimeAgentView(
                "agent-1", 7L, "qmssmp", "team-assistant", "班组助手", null,
                "PROJECT", null, true,
                7L, 7L, 1, "ACTIVE", "AGENTSCOPE", 1, null, null);
    }

    private RuntimeSkillInteractionEntity pendingRow() throws Exception {
        RuntimeSkillInteractionEntity row = new RuntimeSkillInteractionEntity();
        row.setId("spv_1");
        row.setTraceId("trace-1");
        row.setSessionId("session-1");
        row.setUserId("u-1");
        row.setAgentId("agent-1");
        row.setSkillName(SupervisorApprovalInteractionService.SKILL_PREFIX + "update_team");
        row.setStatus("PENDING");
        row.setSlotState(objectMapper.writeValueAsString(Map.of(
                "agentId", "agent-1",
                "toolName", "update_team",
                "permissionKey", "team:write",
                "args", Map.of("teamId", "T-1"),
                "input", Map.of("agentId", "agent-1", "message", "修改班组"))));
        row.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        return row;
    }
}
