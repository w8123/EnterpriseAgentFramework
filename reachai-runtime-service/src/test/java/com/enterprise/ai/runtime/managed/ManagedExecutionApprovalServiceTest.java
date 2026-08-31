package com.enterprise.ai.runtime.managed;

import com.enterprise.ai.runtime.execution.RuntimeInteractionEventEntity;
import com.enterprise.ai.runtime.execution.RuntimeInteractionEventMapper;
import com.enterprise.ai.runtime.execution.RuntimeInteractionSessionEntity;
import com.enterprise.ai.runtime.execution.RuntimeInteractionSessionMapper;
import com.enterprise.ai.runtime.managed.ManagedExecutionPayloadSanitizer.SanitizedEvent;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.ApprovalDecisionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagedExecutionApprovalServiceTest {

    @Mock
    private RuntimeInteractionSessionMapper sessionMapper;
    @Mock
    private RuntimeInteractionEventMapper eventMapper;
    @Mock
    private ManagedExecutionMapper executionMapper;

    private ManagedExecutionApprovalService service;

    @BeforeEach
    void setUp() {
        service = new ManagedExecutionApprovalService(
                sessionMapper, eventMapper, executionMapper, new ObjectMapper());
    }

    @Test
    void createsABoundedRuntimeInteractionAndOpensTheExecutionFence() {
        ManagedExecutionEntity execution = execution();
        when(sessionMapper.insert(any())).thenReturn(1);
        when(eventMapper.insert(any())).thenReturn(1);
        when(executionMapper.openApproval(anyString(), anyString(), anyString(), any())).thenReturn(1);

        var opened = service.onRequested(execution, requested());

        assertThat(opened.interactionId()).startsWith("mei_");
        assertThat(opened.approvalRequestId()).isEqualTo("approval-1");
        ArgumentCaptor<RuntimeInteractionSessionEntity> interaction =
                ArgumentCaptor.forClass(RuntimeInteractionSessionEntity.class);
        verify(sessionMapper).insert(interaction.capture());
        assertThat(interaction.getValue().getSourceType()).isEqualTo("MANAGED_EXECUTOR");
        assertThat(interaction.getValue().getInteractionType()).isEqualTo("CONFIRM_ACTION");
        assertThat(interaction.getValue().getRunId()).isEqualTo(execution.getExecutionId());
        assertThat(interaction.getValue().getUiRequestJson())
                .contains("git", "status", "approve_once")
                .doesNotContain("workerToken", "objective");
        assertThat(execution.getPendingInteractionId()).isEqualTo(opened.interactionId());
        assertThat(execution.getApprovalCount()).isEqualTo(1);
    }

    @Test
    void recordsAOneShotDecisionAndPublishesItThroughTheExecutionCommandSequence() {
        ManagedExecutionEntity execution = execution();
        execution.setPendingInteractionId("mei_approval_1");
        execution.setPendingApprovalRequestId("approval-1");
        execution.setCommandSequence(4L);
        RuntimeInteractionSessionEntity interaction = interaction(execution);
        when(sessionMapper.selectById("mei_approval_1")).thenReturn(interaction);
        when(sessionMapper.update(any(), any())).thenReturn(1);
        when(executionMapper.resolveApproval(
                eq(execution.getExecutionId()), eq("approval-1"), eq("mei_approval_1"), eq("accept"), any()))
                .thenReturn(1);
        when(eventMapper.insert(any())).thenReturn(1);

        var decision = service.resolve(
                execution,
                "user-a",
                "mei_approval_1",
                new ApprovalDecisionRequest("APPROVE", "decision-1"));

        assertThat(decision.decision()).isEqualTo("accept");
        assertThat(decision.commandSequence()).isEqualTo(5L);
        assertThat(decision.idempotentReplay()).isFalse();
        assertThat(execution.getApprovalDecision()).isEqualTo("accept");
        ArgumentCaptor<RuntimeInteractionEventEntity> event =
                ArgumentCaptor.forClass(RuntimeInteractionEventEntity.class);
        verify(eventMapper).insert(event.capture());
        assertThat(event.getValue().getPayloadJson()).contains("accept").doesNotContain("token");
    }

    @Test
    void rejectsADecisionFromAnotherUserBeforeMutatingTheApproval() {
        ManagedExecutionEntity execution = execution();
        execution.setPendingInteractionId("mei_approval_1");
        execution.setPendingApprovalRequestId("approval-1");
        RuntimeInteractionSessionEntity interaction = interaction(execution);
        when(sessionMapper.selectById("mei_approval_1")).thenReturn(interaction);

        assertThatThrownBy(() -> service.resolve(
                execution,
                "user-b",
                "mei_approval_1",
                new ApprovalDecisionRequest("APPROVE", "decision-1")))
                .isInstanceOfSatisfying(ManagedExecutionException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(403);
                    assertThat(failure.code()).isEqualTo("MANAGED_APPROVAL_ACTOR_FORBIDDEN");
                });

        verify(sessionMapper, never()).update(any(), any());
        verify(executionMapper, never()).resolveApproval(
                anyString(), anyString(), anyString(), anyString(), any());
        verify(eventMapper, never()).insert(any());
    }

    @Test
    void rejectsAWorkerDecisionThatDoesNotMatchRuntimeApproval() {
        ManagedExecutionEntity execution = execution();
        execution.setPendingInteractionId("mei_approval_1");
        execution.setPendingApprovalRequestId("approval-1");
        execution.setApprovalDecision("accept");

        assertThatThrownBy(() -> service.onResolved(execution, resolved("cancel")))
                .isInstanceOfSatisfying(ManagedExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("MANAGED_APPROVAL_DECISION_MISMATCH"));
        verify(executionMapper, never()).closeApproval(anyString(), anyString(), any());
    }

    private ManagedExecutionEntity execution() {
        ManagedExecutionEntity execution = new ManagedExecutionEntity();
        execution.setExecutionId("mex_approval_1");
        execution.setTenantId("tenant-a");
        execution.setProjectCode("PROJECT_A");
        execution.setRequestedByUserId("user-a");
        execution.setStatus("WAITING_APPROVAL");
        execution.setApprovalTimeoutSeconds(600);
        execution.setApprovalCount(0);
        execution.setCommandSequence(0L);
        return execution;
    }

    private RuntimeInteractionSessionEntity interaction(ManagedExecutionEntity execution) {
        RuntimeInteractionSessionEntity row = new RuntimeInteractionSessionEntity();
        row.setId(execution.getPendingInteractionId());
        row.setSourceType("MANAGED_EXECUTOR");
        row.setInteractionType("CONFIRM_ACTION");
        row.setRunId(execution.getExecutionId());
        row.setTenantId(execution.getTenantId());
        row.setUserId(execution.getRequestedByUserId());
        row.setStatus("WAITING_USER");
        row.setRevision(0);
        row.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        return row;
    }

    private SanitizedEvent requested() {
        return new SanitizedEvent(
                3,
                "mex_approval_1:3",
                Instant.now(),
                "APPROVAL_REQUESTED",
                "WAITING_APPROVAL",
                "OPERATOR",
                "Command approval required",
                "{\"approvalRequestId\":\"approval-1\",\"approvalKind\":\"COMMAND\","
                        + "\"command\":[\"git\",\"status\"]}",
                "a".repeat(64));
    }

    private SanitizedEvent resolved(String decision) {
        return new SanitizedEvent(
                4,
                "mex_approval_1:4",
                Instant.now(),
                "APPROVAL_RESOLVED",
                "RUNNING",
                "OPERATOR",
                "Approval request resolved",
                "{\"approvalRequestId\":\"approval-1\",\"approvalKind\":\"COMMAND\","
                        + "\"decision\":\"" + decision + "\"}",
                "b".repeat(64));
    }
}
